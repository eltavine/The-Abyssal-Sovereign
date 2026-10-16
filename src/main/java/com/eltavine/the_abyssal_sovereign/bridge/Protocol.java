package com.eltavine.the_abyssal_sovereign.bridge;

/**
 * Wire protocol constants shared with the native daemon.
 *
 * <p>Every value here mirrors {@code native/src/protocol.zig} byte for byte. The two files are
 * the only definition of the protocol and must be changed together: a divergence desynchronises
 * every frame after the point of mismatch, so this class intentionally contains no logic beyond
 * the constants and a couple of frame-shape helpers.
 *
 * <p>Frame shape: {@code [u32 len little-endian][u8 opcode][payload (len-1 bytes)]}. All
 * multi-byte integers are little-endian; floats travel as their IEEE-754 bit pattern.
 */
public final class Protocol {

    private Protocol() {
    }

    public static final int PROTOCOL_VERSION = 1;

    /** Largest frame either side will read; oversized frames are treated as a desync. */
    public static final int MAX_FRAME = 1 << 16;

    // Opcodes: shell -> daemon.
    public static final int OP_HELLO = 0x01;
    public static final int OP_SNAPSHOT = 0x02;
    public static final int OP_GOODBYE = 0x03;

    // Opcodes: daemon -> shell.
    public static final int OP_HELLO_ACK = 0x81;
    public static final int OP_COMMAND_BATCH = 0x82;

    // Command types inside a COMMAND_BATCH.
    public static final int CMD_BOSS_STATE = 0x01;
    public static final int CMD_ATMOSPHERE = 0x02;
    public static final int CMD_DAMAGE_LOCAL = 0x03;
    public static final int CMD_SOUND = 0x04;
    public static final int CMD_PARTICLE = 0x05;
    public static final int CMD_LIGHTNING = 0x06;
    public static final int CMD_AURA = 0x07;

    // Boss animations (index the vanilla Warden model animations the shell drives).
    public static final int ANIM_IDLE = 0;
    public static final int ANIM_ATTACK = 1;
    public static final int ANIM_ROAR = 2;
    public static final int ANIM_SONIC_BOOM = 3;

    // Phase labels under the boss bar. The Sovereign is always enraged, so only CATACLYSM is
    // emitted, but the full ladder stays defined so the label table never has gaps.
    public static final int PHASE_DORMANT = 0;
    public static final int PHASE_STIRRING = 1;
    public static final int PHASE_ENRAGED = 2;
    public static final int PHASE_CATACLYSM = 3;

    // Sound ids, mapped to vanilla SoundEvents by the shell.
    public static final int SND_ROAR = 0;
    public static final int SND_SONIC_CHARGE = 1;
    public static final int SND_SONIC_BOOM = 2;
    public static final int SND_NEARBY_CLOSEST = 3;
    public static final int SND_TENDRIL_CLICKS = 4;
    public static final int SND_HEARTBEAT = 5;
    public static final int SND_SCULK_BLOOM = 6;
    public static final int SND_THUNDER = 7;

    // Particle ids, mapped to vanilla ParticleTypes by the shell.
    public static final int PT_SCULK_SOUL = 0;
    public static final int PT_SCULK_CHARGE_POP = 1;
    public static final int PT_EXPLOSION_EMITTER = 2;
    public static final int PT_SONIC_BOOM = 3;
    public static final int PT_ELECTRIC_SPARK = 4;
    public static final int PT_END_ROD = 5;

    // Aura ids, mapped to vanilla MobEffects by the shell. HEARTBEAT carries no effect.
    public static final int AURA_DARKNESS = 0;
    public static final int AURA_SLOWNESS = 1;
    public static final int AURA_WEAKNESS = 2;
    public static final int AURA_HEARTBEAT = 3;

    // Damage source kinds, so the shell can pick a feel per attack.
    public static final int DMG_GENERIC = 0;
    public static final int DMG_SONIC = 1;
    public static final int DMG_SLAM = 2;
    public static final int DMG_AURA = 3;

    // Snapshot flag bits (shell -> daemon).
    public static final int FLAG_IN_WORLD = 1;
    public static final int FLAG_ACTIVE = 1 << 1;
}
