package com.eltavine.the_abyssal_sovereign.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The per-tick view of the world the shell forwards to the daemon.
 *
 * <p>This is the only information the brain gets: the shell makes no decisions, it just observes
 * and executes. The field order of {@link #encode()} mirrors {@code protocol.decodeSnapshot} in
 * the daemon exactly.
 */
public record Snapshot(
        long tick,
        long dimensionHash,
        double px,
        double py,
        double pz,
        float yaw,
        float pitch,
        float health,
        float maxHealth,
        double camX,
        double camY,
        double camZ,
        long worldTime,
        int flags) {

    public static final int BYTES = 8 + 8 + 24 + 8 + 8 + 24 + 8 + 1;

    /** Encodes the SNAPSHOT payload (without the frame header or opcode). */
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(tick);
        buf.putLong(dimensionHash);
        buf.putDouble(px);
        buf.putDouble(py);
        buf.putDouble(pz);
        buf.putFloat(yaw);
        buf.putFloat(pitch);
        buf.putFloat(health);
        buf.putFloat(maxHealth);
        buf.putDouble(camX);
        buf.putDouble(camY);
        buf.putDouble(camZ);
        buf.putLong(worldTime);
        buf.put((byte) flags);
        return buf.array();
    }
}
