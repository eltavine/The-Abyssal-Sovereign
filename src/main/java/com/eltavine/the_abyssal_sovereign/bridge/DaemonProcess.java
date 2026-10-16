package com.eltavine.the_abyssal_sovereign.bridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A spawned daemon process and the framed exchange with it.
 *
 * <p>One {@link #exchange(Snapshot)} call is one request/response round trip: the shell writes a
 * snapshot and reads back the batch of commands the brain produced for it. The lock-step design
 * keeps the two sides from ever disagreeing about whose turn it is to write.
 */
public final class DaemonProcess implements AutoCloseable {

    private final Process process;
    private final FramedChannel channel;

    private DaemonProcess(Process process, FramedChannel channel) {
        this.process = process;
        this.channel = channel;
    }

    /**
     * Spawns the daemon at {@code exe} and completes the HELLO handshake.
     *
     * @throws IOException if the process cannot start or the handshake fails
     */
    public static DaemonProcess start(Path exe, DaemonConfig config) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(exe.toAbsolutePath().toString());
        // The daemon's stderr is its own diagnostics; fold it into the game log.
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();

        FramedChannel channel = new FramedChannel(process.getInputStream(), process.getOutputStream());
        channel.writeFrame(Protocol.OP_HELLO, config.encode());

        FramedChannel.Frame ack = channel.readFrame();
        if (ack.opcode() != Protocol.OP_HELLO_ACK) {
            process.destroyForcibly();
            throw new IOException("daemon did not answer HELLO with HELLO_ACK (got opcode " + ack.opcode() + ")");
        }
        return new DaemonProcess(process, channel);
    }

    /** Sends a snapshot and returns the commands the daemon produced for it. */
    public List<BossCommand> exchange(Snapshot snapshot) throws IOException {
        channel.writeFrame(Protocol.OP_SNAPSHOT, snapshot.encode());
        FramedChannel.Frame frame = channel.readFrame();
        if (frame.opcode() != Protocol.OP_COMMAND_BATCH) {
            return List.of();
        }
        return BossCommand.decodeBatch(frame.payload());
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            channel.writeFrame(Protocol.OP_GOODBYE, new byte[0]);
        } catch (IOException ignored) {
            // The process is going away regardless.
        }
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
