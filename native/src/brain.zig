//! The Abyssal Sovereign's behaviour, as pure logic.
//!
//! The daemon is the only brain: the Java shell forwards a world snapshot each client tick
//! and this module decides everything the boss does in response — where it stands, which
//! move it plays, what it does to the player, and the atmosphere around it. Nothing here
//! touches IO, so the whole behaviour is exercised by `zig build test` without a game.
//!
//! The Sovereign is "always enraged": there is no phase ladder and no calm state. Once
//! invoked it binds itself to the player — every tick it stands beside them wherever they
//! are, across teleports, deaths and dimensions — calls down a ceaseless storm of judgement
//! lightning, and orders every other living thing in the world erased.

const std = @import("std");
const proto = @import("protocol.zig");

pub const Command = union(enum) {
    boss_state: BossState,
    atmosphere: Atmosphere,
    damage: Damage,
    sound: Sound,
    particle: Particle,
    lightning: Lightning,
    aura: Aura,
    purge: Purge,
};

pub const BossState = struct {
    present: bool,
    x: f64,
    y: f64,
    z: f64,
    yaw: f32,
    pitch: f32,
    scale: f32,
    anim: u8,
    anim_start: i64,
    progress: f32,
    phase: u8,
};

pub const Atmosphere = struct { darken: f32, fog: f32, sky: f32 };
pub const Damage = struct { amount: f32, kx: f32, ky: f32, kz: f32, source: u8 };
pub const Sound = struct { id: u16, x: f64, y: f64, z: f64, volume: f32, pitch: f32 };
pub const Particle = struct {
    id: u16,
    x: f64,
    y: f64,
    z: f64,
    count: u16,
    sx: f32,
    sy: f32,
    sz: f32,
    speed: f32,
};
pub const Lightning = struct { x: f64, y: f64, z: f64, seed: u64 };
pub const Aura = struct { id: u8, radius: f32, amplifier: u8, duration: u16 };
pub const Purge = struct { x: f64, y: f64, z: f64, radius: f32, scope: u8 };

/// Upper bound on bolts in one tick, so a misconfigured count can never crowd the boss
/// state out of the bounded command list.
const MAX_BOLTS_PER_TICK: u32 = 48;
/// Bolts land in a ring around the player: never on top of them, never out of sight.
const STRIKE_MIN_RADIUS: f64 = 6.0;
const STRIKE_MAX_RADIUS: f64 = 110.0;
/// One thunder roll per this many ticks; a sound per bolt would exhaust the sound pool.
const THUNDER_EVERY_TICKS: i64 = 4;

/// Fixed-capacity command collector. One client tick produces a bounded number of commands,
/// so a fixed array keeps the brain allocation-free and makes overflow a visible drop rather
/// than a reallocation.
pub const CommandList = struct {
    items: [128]Command = undefined,
    len: usize = 0,

    pub fn add(self: *CommandList, cmd: Command) void {
        if (self.len >= self.items.len) return;
        self.items[self.len] = cmd;
        self.len += 1;
    }

    pub fn slice(self: *const CommandList) []const Command {
        return self.items[0..self.len];
    }

    pub fn countOf(self: *const CommandList, comptime tag: std.meta.Tag(Command)) usize {
        var n: usize = 0;
        for (self.slice()) |cmd| {
            if (cmd == tag) n += 1;
        }
        return n;
    }
};

const Scheduled = struct { at_tick: i64 = 0, cmd: Command = undefined, armed: bool = false };

const SENTINEL_TICK: i64 = std.math.minInt(i64);

/// Whether `interval` ticks have passed since `last`. A sentinel `last` (never happened)
/// counts as due, and is checked first so the subtraction never underflows against
/// `minInt(i64)`.
fn due(now: i64, last: i64, interval: u32) bool {
    if (last == SENTINEL_TICK) return true;
    return now - last >= interval;
}

pub const Boss = struct {
    cfg: proto.Config = .{},
    active: bool = false,
    /// Whether the bearing has been captured for this invocation.
    anchored: bool = false,
    /// Unit vector from the player to where the titan stands, fixed at invocation so the
    /// titan keeps the same side of the player instead of swinging round as they turn.
    bx: f64 = 0,
    bz: f64 = 1,
    ax: f64 = 0,
    ay: f64 = 0,
    az: f64 = 0,
    yaw: f32 = 0,
    anim: u8 = proto.ANIM_IDLE,
    anim_start: i64 = SENTINEL_TICK,
    last_attack_tick: i64 = SENTINEL_TICK,
    last_aura_tick: i64 = SENTINEL_TICK,
    rng: u64 = 0x9E3779B97F4A7C15,
    schedule: [16]Scheduled = @splat(.{}),

    pub fn init() Boss {
        return .{};
    }

    pub fn initSeed(seed: u64) Boss {
        return .{ .rng = seed | 1 };
    }

    pub fn applyConfig(self: *Boss, cfg: proto.Config) void {
        self.cfg = cfg;
    }

    fn nextRandom(self: *Boss) u64 {
        var x = self.rng;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.rng = x;
        return x;
    }

    fn randUnit(self: *Boss) f64 {
        // 53-bit mantissa worth of entropy mapped to [0,1).
        const bits = self.nextRandom() >> 11;
        return @as(f64, @floatFromInt(bits)) / @as(f64, @floatFromInt(@as(u64, 1) << 53));
    }

    fn arm(self: *Boss, at_tick: i64, cmd: Command) void {
        for (&self.schedule) |*slot| {
            if (!slot.armed) {
                slot.* = .{ .at_tick = at_tick, .cmd = cmd, .armed = true };
                return;
            }
        }
    }

    fn flushDue(self: *Boss, now: i64, cmds: *CommandList) void {
        for (&self.schedule) |*slot| {
            if (slot.armed and now >= slot.at_tick) {
                cmds.add(slot.cmd);
                slot.armed = false;
            }
        }
    }

    fn reset(self: *Boss) void {
        self.active = false;
        self.anchored = false;
        self.anim = proto.ANIM_IDLE;
        self.last_attack_tick = SENTINEL_TICK;
        self.last_aura_tick = SENTINEL_TICK;
        for (&self.schedule) |*slot| slot.armed = false;
    }

    /// Advance one tick. Emits every command the shell should execute this frame.
    pub fn step(self: *Boss, snap: proto.Snapshot, cmds: *CommandList) void {
        if (!snap.inWorld() or !snap.active()) {
            self.reset();
            cmds.add(.{ .boss_state = absentState() });
            return;
        }

        self.follow(snap);
        self.active = true;

        const dx = snap.px - self.ax;
        const dz = snap.pz - self.az;
        self.yaw = yawToward(dx, dz);
        const dist = @sqrt(dx * dx + dz * dz);

        // Return to idle once the current animation has played out. Purely visual; the
        // ability cadence below is independent so moves never stall waiting on an anim.
        if (self.anim != proto.ANIM_IDLE and snap.tick - self.anim_start >= animDuration(self.anim)) {
            self.anim = proto.ANIM_IDLE;
        }

        self.maybeAttack(snap, dist);
        self.lightningBarrage(snap, cmds);
        self.maybeAura(snap, dist);
        self.flushDue(snap.tick, cmds);

        // Nothing else that lives is permitted to share the world with the Sovereign.
        cmds.add(.{ .purge = .{
            .x = snap.px,
            .y = snap.py,
            .z = snap.pz,
            .radius = 0,
            .scope = proto.PURGE_GLOBAL,
        } });

        // Enraged means never calm: darkness floors well above zero and deepens as the
        // player closes in.
        const closeness: f32 = 1.0 - std.math.clamp(@as(f32, @floatCast(dist)) / @max(1.0, self.cfg.boss_distance), 0.0, 1.0);
        const darken = std.math.clamp(0.35 + closeness * 0.65, 0.35, 1.0);
        cmds.add(.{ .atmosphere = .{ .darken = darken, .fog = darken * 0.6, .sky = darken } });

        cmds.add(.{ .boss_state = .{
            .present = true,
            .x = self.ax,
            .y = self.ay,
            .z = self.az,
            .yaw = self.yaw,
            .pitch = 0.0,
            .scale = self.cfg.boss_scale,
            .anim = self.anim,
            .anim_start = self.anim_start,
            .progress = 1.0,
            .phase = proto.PHASE_CATACLYSM,
        } });
    }

    fn follow(self: *Boss, snap: proto.Snapshot) void {
        // The bearing is taken once, along the player's facing at invocation; from then on
        // the titan is re-placed beside the player every tick. Nothing about where the player
        // is — distance, teleport, respawn or dimension — survives a single tick of separation.
        if (!self.anchored) {
            const yaw_rad = std.math.degreesToRadians(snap.yaw);
            self.bx = -@sin(yaw_rad);
            self.bz = @cos(yaw_rad);
            self.anchored = true;
            self.anim = proto.ANIM_IDLE;
            self.anim_start = snap.tick;
        }
        const d: f64 = self.cfg.boss_distance;
        self.ax = snap.px + self.bx * d;
        self.az = snap.pz + self.bz * d;
        self.ay = snap.py;
    }

    /// Range for an effect gated on distance. The titan always stands boss_distance away,
    /// so a configured radius below that would mean the effect could never fire.
    fn reach(self: *const Boss, radius: f32) f64 {
        return @as(f64, @max(radius, self.cfg.boss_distance)) + 1.0;
    }

    fn startAnim(self: *Boss, anim: u8, tick: i64) void {
        self.anim = anim;
        self.anim_start = tick;
    }

    fn maybeAttack(self: *Boss, snap: proto.Snapshot, dist: f64) void {
        if (!due(snap.tick, self.last_attack_tick, self.cfg.attack_interval_ticks)) return;
        self.last_attack_tick = snap.tick;

        const in_range = dist <= self.reach(self.cfg.engage_radius);
        const dir = awayDirection(snap.px - self.ax, snap.pz - self.az);
        const choice = self.pickAbility();
        switch (choice) {
            .melee => {
                self.startAnim(proto.ANIM_ATTACK, snap.tick);
                self.arm(snap.tick + 1, .{ .sound = soundAt(proto.SND_TENDRIL_CLICKS, snap, 4.0, 0.5) });
                if (in_range) self.arm(snap.tick + 8, .{ .damage = .{
                    .amount = self.cfg.damage_per_attack,
                    .kx = dir[0] * 1.2,
                    .ky = 0.5,
                    .kz = dir[1] * 1.2,
                    .source = proto.DMG_GENERIC,
                } });
            },
            .sonic => {
                self.startAnim(proto.ANIM_SONIC_BOOM, snap.tick);
                self.arm(snap.tick + 1, .{ .sound = soundAt(proto.SND_SONIC_CHARGE, snap, 3.0, 1.0) });
                self.arm(snap.tick + 34, .{ .sound = soundAt(proto.SND_SONIC_BOOM, snap, 3.0, 1.0) });
                self.arm(snap.tick + 34, .{ .particle = .{
                    .id = proto.PT_SONIC_BOOM,
                    .x = snap.px,
                    .y = snap.py + 1.0,
                    .z = snap.pz,
                    .count = 1,
                    .sx = 0,
                    .sy = 0,
                    .sz = 0,
                    .speed = 0,
                } });
                if (in_range) self.arm(snap.tick + 34, .{ .damage = .{
                    .amount = self.cfg.damage_per_attack * 1.5,
                    .kx = dir[0] * 0.8,
                    .ky = 0.2,
                    .kz = dir[1] * 0.8,
                    .source = proto.DMG_SONIC,
                } });
            },
            .roar => {
                self.startAnim(proto.ANIM_ROAR, snap.tick);
                self.arm(snap.tick + 25, .{ .sound = soundAt(proto.SND_ROAR, snap, 3.0, 1.0) });
            },
            .slam => {
                self.startAnim(proto.ANIM_ATTACK, snap.tick);
                self.arm(snap.tick + 1, .{ .sound = soundAt(proto.SND_TENDRIL_CLICKS, snap, 4.0, 0.5) });
                self.arm(snap.tick + 30, .{ .sound = soundAt(proto.SND_SONIC_BOOM, snap, 6.0, 0.4) });
                self.arm(snap.tick + 30, .{ .particle = .{
                    .id = proto.PT_EXPLOSION_EMITTER,
                    .x = snap.px,
                    .y = snap.py,
                    .z = snap.pz,
                    .count = 8,
                    .sx = 6.0,
                    .sy = 1.0,
                    .sz = 6.0,
                    .speed = 0,
                } });
                if (in_range) self.arm(snap.tick + 30, .{ .damage = .{
                    .amount = self.cfg.damage_per_attack * 1.2,
                    .kx = dir[0] * 2.6,
                    .ky = 1.1,
                    .kz = dir[1] * 2.6,
                    .source = proto.DMG_SLAM,
                } });
            },
            .summon => {
                // Client-only architecture: there is no server to spawn real mobs into, so
                // "summons" manifest as a sculk eruption plus escalating pressure rather
                // than entities. The theme survives; the entity does not.
                self.startAnim(proto.ANIM_SONIC_BOOM, snap.tick);
                self.arm(snap.tick + 1, .{ .sound = soundAt(proto.SND_NEARBY_CLOSEST, snap, 5.0, 0.5) });
                var i: i64 = 0;
                while (i < 4) : (i += 1) {
                    self.arm(snap.tick + 20 + i * 10, .{ .sound = soundAt(proto.SND_SCULK_BLOOM, snap, 1.4, 0.6) });
                    self.arm(snap.tick + 20 + i * 10, .{ .particle = .{
                        .id = proto.PT_SCULK_SOUL,
                        .x = snap.px,
                        .y = snap.py + 1.0,
                        .z = snap.pz,
                        .count = 12,
                        .sx = 0.6,
                        .sy = 0.6,
                        .sz = 0.6,
                        .speed = 0.02,
                    } });
                }
            },
        }
    }

    /// The judgement storm: `lightning_per_tick` bolts every tick (100 a second at the
    /// default), each at a fresh random point in a ring around the player. They go straight
    /// into the batch rather than the schedule, which is far too small for this volume.
    fn lightningBarrage(self: *Boss, snap: proto.Snapshot, cmds: *CommandList) void {
        const count = @min(self.cfg.lightning_per_tick, MAX_BOLTS_PER_TICK);
        var i: u32 = 0;
        while (i < count) : (i += 1) {
            const seed = self.nextRandom();
            const angle = self.randUnit() * (2.0 * std.math.pi);
            const radius = STRIKE_MIN_RADIUS + self.randUnit() * (STRIKE_MAX_RADIUS - STRIKE_MIN_RADIUS);
            cmds.add(.{ .lightning = .{
                .x = snap.px + @cos(angle) * radius,
                .y = snap.py,
                .z = snap.pz + @sin(angle) * radius,
                .seed = seed,
            } });
        }
        if (count > 0 and @mod(snap.tick, THUNDER_EVERY_TICKS) == 0) {
            const pitch: f32 = @floatCast(0.55 + self.randUnit() * 0.6);
            cmds.add(.{ .sound = soundAt(proto.SND_THUNDER, snap, 3.0, pitch) });
        }
    }

    fn maybeAura(self: *Boss, snap: proto.Snapshot, dist: f64) void {
        if (!due(snap.tick, self.last_aura_tick, self.cfg.aura_interval_ticks)) return;
        self.last_aura_tick = snap.tick;
        // The heartbeat is heard whether or not the player is in range: the Sovereign is
        // always present.
        self.arm(snap.tick, .{ .sound = soundAt(proto.SND_HEARTBEAT, snap, 2.0, 1.0) });
        if (dist > self.reach(self.cfg.aura_radius)) return;
        // Amplifier 9 is what the game displays as level X.
        self.arm(snap.tick, .{ .aura = .{ .id = proto.AURA_SLOWNESS, .radius = self.cfg.aura_radius, .amplifier = 9, .duration = 80 } });
        self.arm(snap.tick, .{ .aura = .{ .id = proto.AURA_WEAKNESS, .radius = self.cfg.aura_radius, .amplifier = 9, .duration = 80 } });
    }

    const Ability = enum { melee, sonic, roar, slam, summon };

    fn pickAbility(self: *Boss) Ability {
        const weights = [_]u32{ 30, 20, 25, 25, 15 };
        var total: u32 = 0;
        for (weights) |w| total += w;
        var pick = @as(u32, @intCast(self.nextRandom() % total));
        inline for (weights, 0..) |w, idx| {
            if (pick < w) return @enumFromInt(idx);
            pick -= w;
        }
        return .melee;
    }
};

fn absentState() BossState {
    return .{
        .present = false,
        .x = 0,
        .y = 0,
        .z = 0,
        .yaw = 0,
        .pitch = 0,
        .scale = 0,
        .anim = proto.ANIM_IDLE,
        .anim_start = SENTINEL_TICK,
        .progress = 0,
        .phase = proto.PHASE_DORMANT,
    };
}

fn soundAt(id: u16, snap: proto.Snapshot, volume: f32, pitch: f32) Sound {
    return .{ .id = id, .x = snap.px, .y = snap.py, .z = snap.pz, .volume = volume, .pitch = pitch };
}

fn yawToward(dx: f64, dz: f64) f32 {
    return @floatCast(std.math.radiansToDegrees(std.math.atan2(-dx, dz)));
}

fn awayDirection(dx: f64, dz: f64) [2]f32 {
    const len = @sqrt(dx * dx + dz * dz);
    if (len < 1.0e-4) return .{ 1.0, 0.0 };
    return .{ @floatCast(dx / len), @floatCast(dz / len) };
}

fn animDuration(anim: u8) i64 {
    return switch (anim) {
        proto.ANIM_ATTACK => 20,
        proto.ANIM_ROAR => 84,
        proto.ANIM_SONIC_BOOM => 60,
        else => 0,
    };
}

// ---- tests ----

fn makeSnapshot(tick: i64, px: f64, flags: u8) proto.Snapshot {
    return makeSnapshotAt(tick, px, 0.0, flags);
}

fn makeSnapshotAt(tick: i64, px: f64, pz: f64, flags: u8) proto.Snapshot {
    return makeSnapshotIn(tick, 0, px, 64.0, pz, flags);
}

fn makeSnapshotIn(tick: i64, dim_hash: u64, px: f64, py: f64, pz: f64, flags: u8) proto.Snapshot {
    return .{
        .tick = tick,
        .dim_hash = dim_hash,
        .px = px,
        .py = py,
        .pz = pz,
        .yaw = 0.0,
        .pitch = 0.0,
        .health = 20.0,
        .max_health = 20.0,
        .cam_x = px,
        .cam_y = 65.0,
        .cam_z = 0.0,
        .world_time = tick,
        .flags = flags,
    };
}

test "inactive emits an absent boss" {
    var boss = Boss.init();
    var cmds = CommandList{};
    boss.step(makeSnapshot(0, 0, proto.FLAG_IN_WORLD), &cmds);
    try std.testing.expectEqual(@as(usize, 1), cmds.len);
    try std.testing.expect(cmds.items[0].boss_state.present == false);
}

test "active anchors the boss and keeps it present" {
    var boss = Boss.init();
    var cmds = CommandList{};
    boss.step(makeSnapshot(0, 0, proto.FLAG_IN_WORLD | proto.FLAG_ACTIVE), &cmds);
    try std.testing.expect(boss.anchored);
    try std.testing.expect(boss.active);
    try std.testing.expect(cmds.countOf(.boss_state) == 1);
    try std.testing.expect(cmds.countOf(.atmosphere) == 1);
    // Anchored boss_distance away along +Z (yaw 0 faces +Z).
    try std.testing.expect(@abs(boss.az - boss.cfg.boss_distance) < 1.0e-6);
}

const ACTIVE_FLAGS = proto.FLAG_IN_WORLD | proto.FLAG_ACTIVE;

test "a fleeing player is still struck and attacked over time" {
    var boss = Boss.initSeed(0xDEADBEEFCAFEBABE);
    var damage_seen = false;
    var lightning_seen = false;
    var tick: i64 = 0;
    while (tick < 400) : (tick += 1) {
        var cmds = CommandList{};
        // The player sprints away the whole time; the titan keeps pace, so attacks land.
        const px = @as(f64, @floatFromInt(tick)) * 5.6;
        boss.step(makeSnapshotAt(tick, px, 0.0, ACTIVE_FLAGS), &cmds);
        if (cmds.countOf(.damage) > 0) damage_seen = true;
        if (cmds.countOf(.lightning) > 0) lightning_seen = true;
    }
    try std.testing.expect(damage_seen);
    try std.testing.expect(lightning_seen);
}

test "the titan stands beside the player wherever they go" {
    var boss = Boss.init();
    var cmds = CommandList{};
    boss.step(makeSnapshotIn(0, 0, 0.0, 64.0, 0.0, ACTIVE_FLAGS), &cmds);

    // Far teleports, a respawn high in the sky, a deep cave and another dimension entirely.
    const Spot = struct { dim: u64, x: f64, y: f64, z: f64 };
    const spots = [_]Spot{
        .{ .dim = 0, .x = 5000.0, .y = 64.0, .z = -3000.0 },
        .{ .dim = 0, .x = -120000.0, .y = 310.0, .z = 77.0 },
        .{ .dim = 0, .x = 12.0, .y = -58.0, .z = 9.0 },
        .{ .dim = 0x7A11, .x = 625.0, .y = 32.0, .z = -400.0 },
    };
    for (spots, 1..) |s, t| {
        cmds = CommandList{};
        boss.step(makeSnapshotIn(@intCast(t), s.dim, s.x, s.y, s.z, ACTIVE_FLAGS), &cmds);
        const dx = boss.ax - s.x;
        const dz = boss.az - s.z;
        try std.testing.expectApproxEqAbs(@as(f64, boss.cfg.boss_distance), @sqrt(dx * dx + dz * dz), 1.0e-6);
        try std.testing.expectEqual(s.y, boss.ay);
        try std.testing.expectEqual(@as(usize, 1), cmds.countOf(.boss_state));
    }
}

test "one hundred random judgement bolts fall every second" {
    var boss = Boss.initSeed(7);
    var bolts: usize = 0;
    var first: ?Lightning = null;
    var distinct = false;
    var tick: i64 = 0;
    while (tick < 20) : (tick += 1) {
        var cmds = CommandList{};
        boss.step(makeSnapshotAt(tick, 100.0, -50.0, ACTIVE_FLAGS), &cmds);
        for (cmds.slice()) |cmd| {
            if (cmd != .lightning) continue;
            const l = cmd.lightning;
            bolts += 1;
            const r = @sqrt((l.x - 100.0) * (l.x - 100.0) + (l.z + 50.0) * (l.z + 50.0));
            try std.testing.expect(r >= STRIKE_MIN_RADIUS - 1.0e-9 and r <= STRIKE_MAX_RADIUS + 1.0e-9);
            if (first) |f| {
                if (f.x != l.x or f.z != l.z or f.seed != l.seed) distinct = true;
            } else first = l;
        }
    }
    try std.testing.expectEqual(@as(usize, 100), bolts);
    try std.testing.expect(distinct);
}

test "every active tick orders a world-wide purge, and only then" {
    var boss = Boss.init();
    var cmds = CommandList{};
    boss.step(makeSnapshot(0, 0, ACTIVE_FLAGS), &cmds);
    try std.testing.expectEqual(@as(usize, 1), cmds.countOf(.purge));
    for (cmds.slice()) |cmd| {
        if (cmd == .purge) try std.testing.expectEqual(proto.PURGE_GLOBAL, cmd.purge.scope);
    }
    cmds = CommandList{};
    boss.step(makeSnapshot(1, 0, proto.FLAG_IN_WORLD), &cmds);
    try std.testing.expectEqual(@as(usize, 0), cmds.countOf(.purge));
}

test "the aura blinds no one and lands slowness and weakness at level X" {
    var boss = Boss.init();
    var cmds = CommandList{};
    // The first active tick is an aura tick, and the titan stands within its reach.
    boss.step(makeSnapshot(0, 0, ACTIVE_FLAGS), &cmds);
    var slowness = false;
    var weakness = false;
    for (cmds.slice()) |cmd| {
        if (cmd != .aura) continue;
        try std.testing.expect(cmd.aura.id != proto.AURA_DARKNESS);
        try std.testing.expectEqual(@as(u8, 9), cmd.aura.amplifier);
        if (cmd.aura.id == proto.AURA_SLOWNESS) slowness = true;
        if (cmd.aura.id == proto.AURA_WEAKNESS) weakness = true;
    }
    try std.testing.expect(slowness and weakness);
    try std.testing.expectEqual(@as(usize, 2), cmds.countOf(.aura));
}

test "ability selection is deterministic for a fixed seed" {
    var a = Boss.initSeed(12345);
    var b = Boss.initSeed(12345);
    var i: usize = 0;
    while (i < 20) : (i += 1) {
        try std.testing.expectEqual(a.pickAbility(), b.pickAbility());
    }
}

test "leaving the world resets the boss" {
    var boss = Boss.init();
    var cmds = CommandList{};
    boss.step(makeSnapshot(0, 0, proto.FLAG_IN_WORLD | proto.FLAG_ACTIVE), &cmds);
    try std.testing.expect(boss.anchored);
    cmds = CommandList{};
    boss.step(makeSnapshot(1, 0, 0), &cmds); // no flags: not in world
    try std.testing.expect(!boss.anchored);
    try std.testing.expect(cmds.items[0].boss_state.present == false);
}
