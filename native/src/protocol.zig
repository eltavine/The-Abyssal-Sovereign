//! Wire protocol shared by the native daemon and the Java effector shell.
//!
//! Every message is a length-prefixed frame:
//!
//!     [u32 len little-endian][u8 opcode][payload... (len-1 bytes)]
//!
//! `len` counts the opcode plus the payload. All multi-byte integers are little-endian;
//! floats are IEEE-754 transmitted as their integer bit pattern. The Java side mirrors
//! these constants exactly in `Protocol.java`; the two must be changed together because a
//! mismatch desynchronises every frame after it.

const std = @import("std");

pub const PROTOCOL_VERSION: u32 = 1;

// Opcodes: shell -> daemon.
pub const OP_HELLO: u8 = 0x01;
pub const OP_SNAPSHOT: u8 = 0x02;
pub const OP_GOODBYE: u8 = 0x03;

// Opcodes: daemon -> shell.
pub const OP_HELLO_ACK: u8 = 0x81;
pub const OP_COMMAND_BATCH: u8 = 0x82;

// Command types inside a COMMAND_BATCH.
pub const CMD_BOSS_STATE: u8 = 0x01;
pub const CMD_ATMOSPHERE: u8 = 0x02;
pub const CMD_DAMAGE_LOCAL: u8 = 0x03;
pub const CMD_SOUND: u8 = 0x04;
pub const CMD_PARTICLE: u8 = 0x05;
pub const CMD_LIGHTNING: u8 = 0x06;
pub const CMD_AURA: u8 = 0x07;
pub const CMD_PURGE: u8 = 0x08;

// Purge scopes. GLOBAL ignores the position and radius: every non-player creature in
// every loaded level is erased. LOCAL limits the sweep to a sphere.
pub const PURGE_LOCAL: u8 = 0;
pub const PURGE_GLOBAL: u8 = 1;

// Boss animations. These index the vanilla Warden model animations the shell drives.
pub const ANIM_IDLE: u8 = 0;
pub const ANIM_ATTACK: u8 = 1;
pub const ANIM_ROAR: u8 = 2;
pub const ANIM_SONIC_BOOM: u8 = 3;

// Phase labels shown under the boss bar. The Sovereign is "always enraged", so the brain
// only ever emits CATACLYSM, but the full ladder stays on the wire so the shell's label
// table is stable if the policy ever changes.
pub const PHASE_DORMANT: u8 = 0;
pub const PHASE_STIRRING: u8 = 1;
pub const PHASE_ENRAGED: u8 = 2;
pub const PHASE_CATACLYSM: u8 = 3;

// Sound ids, mapped to vanilla SoundEvents on the shell side.
pub const SND_ROAR: u16 = 0;
pub const SND_SONIC_CHARGE: u16 = 1;
pub const SND_SONIC_BOOM: u16 = 2;
pub const SND_NEARBY_CLOSEST: u16 = 3;
pub const SND_TENDRIL_CLICKS: u16 = 4;
pub const SND_HEARTBEAT: u16 = 5;
pub const SND_SCULK_BLOOM: u16 = 6;
pub const SND_THUNDER: u16 = 7;

// Particle ids, mapped to vanilla ParticleTypes on the shell side.
pub const PT_SCULK_SOUL: u16 = 0;
pub const PT_SCULK_CHARGE_POP: u16 = 1;
pub const PT_EXPLOSION_EMITTER: u16 = 2;
pub const PT_SONIC_BOOM: u16 = 3;
pub const PT_ELECTRIC_SPARK: u16 = 4;
pub const PT_END_ROD: u16 = 5;

// Aura ids, mapped to vanilla MobEffects on the shell side. HEARTBEAT carries no effect;
// it is a pure audio pulse the shell turns into a sound.
pub const AURA_DARKNESS: u8 = 0;
pub const AURA_SLOWNESS: u8 = 1;
pub const AURA_WEAKNESS: u8 = 2;
pub const AURA_HEARTBEAT: u8 = 3;

// Damage source kinds, so the shell can pick a death message / feel per attack.
pub const DMG_GENERIC: u8 = 0;
pub const DMG_SONIC: u8 = 1;
pub const DMG_SLAM: u8 = 2;
pub const DMG_AURA: u8 = 3;

// Snapshot flag bits (shell -> daemon).
pub const FLAG_IN_WORLD: u8 = 1 << 0;
pub const FLAG_ACTIVE: u8 = 1 << 1; // the Sovereign has been invoked in this session

/// Tunables the shell loads from its config file and forwards in the HELLO frame, so the
/// daemon's behaviour is configurable without recompiling it.
pub const Config = struct {
    version: u32 = PROTOCOL_VERSION,
    engage_radius: f32 = 42.0,
    boss_distance: f32 = 60.0,
    boss_scale: f32 = 100.0,
    attack_interval_ticks: u32 = 48,
    lightning_interval_ticks: u32 = 70,
    aura_interval_ticks: u32 = 40,
    damage_per_attack: f32 = 12.0,
    aura_radius: f32 = 36.0,
    /// Judgement bolts per client tick; 5 at 20 ticks/s is 100 bolts a second.
    lightning_per_tick: u32 = 5,
};

/// Decoded SNAPSHOT payload (opcode already stripped).
pub const Snapshot = struct {
    tick: i64,
    dim_hash: u64,
    px: f64,
    py: f64,
    pz: f64,
    yaw: f32,
    pitch: f32,
    health: f32,
    max_health: f32,
    cam_x: f64,
    cam_y: f64,
    cam_z: f64,
    world_time: i64,
    flags: u8,

    pub fn inWorld(self: Snapshot) bool {
        return (self.flags & FLAG_IN_WORLD) != 0;
    }

    pub fn active(self: Snapshot) bool {
        return (self.flags & FLAG_ACTIVE) != 0;
    }
};

pub const SnapshotError = error{ShortSnapshot};

/// A tiny little-endian cursor over a byte slice. Returns ShortSnapshot rather than
/// reading out of bounds, so a truncated frame fails cleanly instead of corrupting state.
const Cursor = struct {
    bytes: []const u8,
    pos: usize = 0,

    fn take(self: *Cursor, comptime n: usize) SnapshotError!*const [n]u8 {
        if (self.pos + n > self.bytes.len) return error.ShortSnapshot;
        const slice = self.bytes[self.pos .. self.pos + n];
        self.pos += n;
        return slice[0..n];
    }

    fn u8v(self: *Cursor) SnapshotError!u8 {
        const b = try self.take(1);
        return b[0];
    }

    fn i64v(self: *Cursor) SnapshotError!i64 {
        return @bitCast(std.mem.readInt(u64, try self.take(8), .little));
    }

    fn u64v(self: *Cursor) SnapshotError!u64 {
        return std.mem.readInt(u64, try self.take(8), .little);
    }

    fn f64v(self: *Cursor) SnapshotError!f64 {
        return @bitCast(std.mem.readInt(u64, try self.take(8), .little));
    }

    fn f32v(self: *Cursor) SnapshotError!f32 {
        return @bitCast(std.mem.readInt(u32, try self.take(4), .little));
    }
};

pub fn decodeSnapshot(payload: []const u8) SnapshotError!Snapshot {
    var c = Cursor{ .bytes = payload };
    return Snapshot{
        .tick = try c.i64v(),
        .dim_hash = try c.u64v(),
        .px = try c.f64v(),
        .py = try c.f64v(),
        .pz = try c.f64v(),
        .yaw = try c.f32v(),
        .pitch = try c.f32v(),
        .health = try c.f32v(),
        .max_health = try c.f32v(),
        .cam_x = try c.f64v(),
        .cam_y = try c.f64v(),
        .cam_z = try c.f64v(),
        .world_time = try c.i64v(),
        .flags = try c.u8v(),
    };
}

pub fn decodeHello(payload: []const u8) Config {
    // Lenient: any field the sender omitted keeps its default, so an older shell still
    // produces a sane configuration rather than a decode error.
    var cfg = Config{};
    var c = Cursor{ .bytes = payload };
    cfg.version = @as(u32, @bitCast((c.take(4) catch return cfg).*)) ;
    cfg.engage_radius = c.f32v() catch return cfg;
    cfg.boss_distance = c.f32v() catch return cfg;
    cfg.boss_scale = c.f32v() catch return cfg;
    cfg.attack_interval_ticks = @bitCast((c.take(4) catch return cfg).*);
    cfg.lightning_interval_ticks = @bitCast((c.take(4) catch return cfg).*);
    cfg.aura_interval_ticks = @bitCast((c.take(4) catch return cfg).*);
    cfg.damage_per_attack = c.f32v() catch return cfg;
    cfg.aura_radius = c.f32v() catch return cfg;
    cfg.lightning_per_tick = @bitCast((c.take(4) catch return cfg).*);
    return cfg;
}

/// Fixed-capacity frame builder. No allocation: the daemon's frames are small and bounded,
/// and avoiding the heap keeps the hot path (one batch per client tick) allocation-free.
pub fn FrameBuilder(comptime capacity: usize) type {
    return struct {
        buf: [capacity]u8 = undefined,
        len: usize = 0,

        const Self = @This();

        pub fn reset(self: *Self) void {
            self.len = 0;
        }

        pub fn bytes(self: *const Self) []const u8 {
            return self.buf[0..self.len];
        }

        pub fn putU8(self: *Self, v: u8) void {
            self.buf[self.len] = v;
            self.len += 1;
        }

        pub fn putU16(self: *Self, v: u16) void {
            std.mem.writeInt(u16, self.buf[self.len..][0..2], v, .little);
            self.len += 2;
        }

        pub fn putU32(self: *Self, v: u32) void {
            std.mem.writeInt(u32, self.buf[self.len..][0..4], v, .little);
            self.len += 4;
        }

        pub fn putU64(self: *Self, v: u64) void {
            std.mem.writeInt(u64, self.buf[self.len..][0..8], v, .little);
            self.len += 8;
        }

        pub fn putI64(self: *Self, v: i64) void {
            self.putU64(@bitCast(v));
        }

        pub fn putF32(self: *Self, v: f32) void {
            self.putU32(@bitCast(v));
        }

        pub fn putF64(self: *Self, v: f64) void {
            self.putU64(@bitCast(v));
        }
    };
}

test "snapshot decode roundtrip" {
    var fb = FrameBuilder(256){};
    fb.putI64(123);
    fb.putU64(0xABCDEF);
    fb.putF64(10.0);
    fb.putF64(64.0);
    fb.putF64(-20.0);
    fb.putF32(90.0);
    fb.putF32(0.0);
    fb.putF32(20.0);
    fb.putF32(20.0);
    fb.putF64(10.0);
    fb.putF64(65.0);
    fb.putF64(-20.0);
    fb.putI64(6000);
    fb.putU8(FLAG_IN_WORLD | FLAG_ACTIVE);

    const snap = try decodeSnapshot(fb.bytes());
    try std.testing.expectEqual(@as(i64, 123), snap.tick);
    try std.testing.expectEqual(@as(u64, 0xABCDEF), snap.dim_hash);
    try std.testing.expectEqual(@as(f64, 10.0), snap.px);
    try std.testing.expectEqual(@as(f32, 90.0), snap.yaw);
    try std.testing.expect(snap.inWorld());
    try std.testing.expect(snap.active());
}

test "short snapshot is rejected" {
    const payload = [_]u8{ 1, 2, 3 };
    try std.testing.expectError(error.ShortSnapshot, decodeSnapshot(&payload));
}

fn helloPayload(fb: anytype, with_lightning: bool) void {
    fb.putU32(PROTOCOL_VERSION);
    fb.putF32(42.0);
    fb.putF32(60.0);
    fb.putF32(100.0);
    fb.putU32(48);
    fb.putU32(70);
    fb.putU32(40);
    fb.putF32(12.0);
    fb.putF32(36.0);
    if (with_lightning) fb.putU32(9);
}

test "hello carries the bolts-per-tick tunable" {
    var fb = FrameBuilder(64){};
    helloPayload(&fb, true);
    const cfg = decodeHello(fb.bytes());
    try std.testing.expectEqual(@as(u32, 9), cfg.lightning_per_tick);
    try std.testing.expectEqual(@as(f32, 36.0), cfg.aura_radius);
}

test "an older hello without the bolt count keeps 100 bolts a second" {
    var fb = FrameBuilder(64){};
    helloPayload(&fb, false);
    const cfg = decodeHello(fb.bytes());
    try std.testing.expectEqual(@as(u32, 5), cfg.lightning_per_tick);
}
