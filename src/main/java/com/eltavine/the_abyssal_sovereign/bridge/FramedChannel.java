package com.eltavine.the_abyssal_sovereign.bridge;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed framing over a pair of streams (the daemon's stdio).
 *
 * <p>Deliberately dependency-free and synchronous: one side writes a frame, the other reads it.
 * The {@link Frame} payload excludes the opcode so callers decode straight into the record types.
 */
public final class FramedChannel implements AutoCloseable {

    /** A decoded frame: its opcode and the payload bytes that followed it. */
    public record Frame(int opcode, byte[] payload) {
    }

    private final DataInputStream in;
    private final OutputStream out;

    public FramedChannel(InputStream in, OutputStream out) {
        this.in = new DataInputStream(in);
        this.out = out;
    }

    public synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
        int len = 1 + payload.length;
        // u32 little-endian length header.
        out.write(len & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 24) & 0xFF);
        out.write(opcode & 0xFF);
        out.write(payload);
        out.flush();
    }

    public Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            throw new EOFException("daemon closed the stream");
        }
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        long len = ((long) b0) | ((long) b1 << 8) | ((long) b2 << 16) | ((long) b3 << 24);
        if (len < 1 || len > Protocol.MAX_FRAME) {
            throw new IOException("framing desync: implausible frame length " + len);
        }
        int opcode = in.readUnsignedByte();
        byte[] payload = new byte[(int) len - 1];
        in.readFully(payload);
        return new Frame(opcode, payload);
    }

    @Override
    public void close() throws IOException {
        try {
            in.close();
        } finally {
            out.close();
        }
    }
}
