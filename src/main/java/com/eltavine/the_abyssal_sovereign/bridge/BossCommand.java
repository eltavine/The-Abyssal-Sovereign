package com.eltavine.the_abyssal_sovereign.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * One instruction from the daemon for the shell to execute this frame.
 *
 * <p>The shell is a pure effector: it never invents a command, it only runs the ones decoded here.
 * The record layouts mirror {@code encodeCommand} in {@code native/src/main.zig} exactly.
 */
public sealed interface BossCommand {

    record BossState(
            boolean present,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float scale,
            int anim,
            long animStart,
            float progress,
            int phase) implements BossCommand {
    }

    record Atmosphere(float darken, float fog, float sky) implements BossCommand {
    }

    record Damage(float amount, float kx, float ky, float kz, int source) implements BossCommand {
    }

    record Sound(int id, double x, double y, double z, float volume, float pitch) implements BossCommand {
    }

    record Particle(
            int id,
            double x,
            double y,
            double z,
            int count,
            float sx,
            float sy,
            float sz,
            float speed) implements BossCommand {
    }

    record Lightning(double x, double y, double z, long seed) implements BossCommand {
    }

    record Aura(int id, float radius, int amplifier, int duration) implements BossCommand {
    }

    record Purge(double x, double y, double z, float radius, int scope) implements BossCommand {

        public boolean global() {
            return scope == Protocol.PURGE_GLOBAL;
        }
    }

    /**
     * Decodes a COMMAND_BATCH payload (the bytes after the frame header and opcode).
     *
     * <p>An unknown command type aborts decoding of the rest of the batch rather than guessing a
     * length, because the next bytes can no longer be located. The commands decoded before it are
     * still returned, so a forward-compatible daemon can append new command types without breaking
     * an older shell's handling of the ones it understands when they come first.
     */
    static List<BossCommand> decodeBatch(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int count = buf.getShort() & 0xFFFF;
        List<BossCommand> commands = new ArrayList<>(count);
        for (int i = 0; i < count && buf.hasRemaining(); i++) {
            int type = buf.get() & 0xFF;
            switch (type) {
                case Protocol.CMD_BOSS_STATE -> commands.add(new BossState(
                        buf.get() != 0,
                        buf.getDouble(), buf.getDouble(), buf.getDouble(),
                        buf.getFloat(), buf.getFloat(), buf.getFloat(),
                        buf.get() & 0xFF,
                        buf.getLong(),
                        buf.getFloat(),
                        buf.get() & 0xFF));
                case Protocol.CMD_ATMOSPHERE -> commands.add(new Atmosphere(
                        buf.getFloat(), buf.getFloat(), buf.getFloat()));
                case Protocol.CMD_DAMAGE_LOCAL -> commands.add(new Damage(
                        buf.getFloat(), buf.getFloat(), buf.getFloat(), buf.getFloat(),
                        buf.get() & 0xFF));
                case Protocol.CMD_SOUND -> commands.add(new Sound(
                        buf.getShort() & 0xFFFF,
                        buf.getDouble(), buf.getDouble(), buf.getDouble(),
                        buf.getFloat(), buf.getFloat()));
                case Protocol.CMD_PARTICLE -> commands.add(new Particle(
                        buf.getShort() & 0xFFFF,
                        buf.getDouble(), buf.getDouble(), buf.getDouble(),
                        buf.getShort() & 0xFFFF,
                        buf.getFloat(), buf.getFloat(), buf.getFloat(), buf.getFloat()));
                case Protocol.CMD_LIGHTNING -> commands.add(new Lightning(
                        buf.getDouble(), buf.getDouble(), buf.getDouble(), buf.getLong()));
                case Protocol.CMD_AURA -> commands.add(new Aura(
                        buf.get() & 0xFF, buf.getFloat(), buf.get() & 0xFF, buf.getShort() & 0xFFFF));
                case Protocol.CMD_PURGE -> commands.add(new Purge(
                        buf.getDouble(), buf.getDouble(), buf.getDouble(), buf.getFloat(), buf.get() & 0xFF));
                default -> {
                    return commands;
                }
            }
        }
        return commands;
    }
}
