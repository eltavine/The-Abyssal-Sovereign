package com.eltavine.the_abyssal_sovereign.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The tunables forwarded to the daemon in the HELLO frame.
 *
 * <p>Kept separate from the Minecraft-facing config loader so the bridge stays free of any game
 * dependency and can be unit tested on its own. The field order of {@link #encode()} mirrors
 * {@code protocol.decodeHello} in the daemon exactly.
 */
public record DaemonConfig(
        float engageRadius,
        float bossDistance,
        float bossScale,
        int attackIntervalTicks,
        int lightningIntervalTicks,
        int auraIntervalTicks,
        float damagePerAttack,
        float auraRadius) {

    public static DaemonConfig defaults() {
        return new DaemonConfig(42.0f, 60.0f, 100.0f, 48, 70, 40, 12.0f, 36.0f);
    }

    /** Encodes the HELLO payload (without the frame header or opcode). */
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 * 3 + 4 * 3 + 4 * 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(Protocol.PROTOCOL_VERSION);
        buf.putFloat(engageRadius);
        buf.putFloat(bossDistance);
        buf.putFloat(bossScale);
        buf.putInt(attackIntervalTicks);
        buf.putInt(lightningIntervalTicks);
        buf.putInt(auraIntervalTicks);
        buf.putFloat(damagePerAttack);
        buf.putFloat(auraRadius);
        return buf.array();
    }
}
