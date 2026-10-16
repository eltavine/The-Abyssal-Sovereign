//! The Abyssal Sovereign native daemon.
//!
//! A standalone, dependency-free executable spawned by the Java mod. It reads length-prefixed
//! frames on stdin and replies on stdout, acting as the sole brain for the boss (see
//! `brain.zig`). The Java side never decides behaviour; it forwards a snapshot each client
//! tick and executes whatever commands come back.

const std = @import("std");
const proto = @import("protocol.zig");
const brain = @import("brain.zig");

const DAEMON_BUILD: u32 = 1;
const MAX_FRAME: usize = 1 << 16;

pub fn main() !void {
    // Threaded is only consulted for async operations we never issue, so the page allocator
    // it is handed is never actually used to allocate.
    var threaded: std.Io.Threaded = .init(std.heap.page_allocator, .{});
    defer threaded.deinit();
    const io = threaded.io();

    const stdin_file = std.Io.File.stdin();
    const stdout_file = std.Io.File.stdout();

    var in_buf: [MAX_FRAME]u8 = undefined;
    var out_buf: [MAX_FRAME]u8 = undefined;
    var file_reader = stdin_file.readerStreaming(io, &in_buf);
    var file_writer = stdout_file.writerStreaming(io, &out_buf);
    const r = &file_reader.interface;
    const w = &file_writer.interface;

    var boss = brain.Boss.init();
    var scratch: [MAX_FRAME]u8 = undefined;

    while (true) {
        const frame = readFrame(r, &scratch) catch break;
        switch (frame.opcode) {
            proto.OP_HELLO => {
                boss.applyConfig(proto.decodeHello(frame.payload));
                writeHelloAck(w) catch break;
            },
            proto.OP_SNAPSHOT => {
                const snap = proto.decodeSnapshot(frame.payload) catch continue;
                var cmds = brain.CommandList{};
                boss.step(snap, &cmds);
                writeCommandBatch(w, &cmds) catch break;
            },
            proto.OP_GOODBYE => break,
            else => {}, // forward-compatible: ignore unknown opcodes
        }
    }
}

const Frame = struct { opcode: u8, payload: []const u8 };

fn readFrame(r: *std.Io.Reader, scratch: []u8) !Frame {
    var len_bytes: [4]u8 = undefined;
    try r.readSliceAll(&len_bytes);
    const len = std.mem.readInt(u32, &len_bytes, .little);
    if (len == 0 or len > scratch.len) return error.BadFrame;
    try r.readSliceAll(scratch[0..len]);
    return .{ .opcode = scratch[0], .payload = scratch[1..len] };
}

fn writeFrame(w: *std.Io.Writer, opcode: u8, payload: []const u8) !void {
    var header: [4]u8 = undefined;
    std.mem.writeInt(u32, &header, @intCast(1 + payload.len), .little);
    try w.writeAll(&header);
    try w.writeAll(&[_]u8{opcode});
    try w.writeAll(payload);
    try w.flush();
}

fn writeHelloAck(w: *std.Io.Writer) !void {
    var fb = proto.FrameBuilder(8){};
    fb.putU32(proto.PROTOCOL_VERSION);
    fb.putU32(DAEMON_BUILD);
    try writeFrame(w, proto.OP_HELLO_ACK, fb.bytes());
}

fn writeCommandBatch(w: *std.Io.Writer, cmds: *const brain.CommandList) !void {
    var fb = proto.FrameBuilder(MAX_FRAME - 8){};
    fb.putU16(@intCast(cmds.len));
    for (cmds.slice()) |cmd| encodeCommand(&fb, cmd);
    try writeFrame(w, proto.OP_COMMAND_BATCH, fb.bytes());
}

fn encodeCommand(fb: anytype, cmd: brain.Command) void {
    switch (cmd) {
        .boss_state => |b| {
            fb.putU8(proto.CMD_BOSS_STATE);
            fb.putU8(@intFromBool(b.present));
            fb.putF64(b.x);
            fb.putF64(b.y);
            fb.putF64(b.z);
            fb.putF32(b.yaw);
            fb.putF32(b.pitch);
            fb.putF32(b.scale);
            fb.putU8(b.anim);
            fb.putI64(b.anim_start);
            fb.putF32(b.progress);
            fb.putU8(b.phase);
        },
        .atmosphere => |a| {
            fb.putU8(proto.CMD_ATMOSPHERE);
            fb.putF32(a.darken);
            fb.putF32(a.fog);
            fb.putF32(a.sky);
        },
        .damage => |d| {
            fb.putU8(proto.CMD_DAMAGE_LOCAL);
            fb.putF32(d.amount);
            fb.putF32(d.kx);
            fb.putF32(d.ky);
            fb.putF32(d.kz);
            fb.putU8(d.source);
        },
        .sound => |s| {
            fb.putU8(proto.CMD_SOUND);
            fb.putU16(s.id);
            fb.putF64(s.x);
            fb.putF64(s.y);
            fb.putF64(s.z);
            fb.putF32(s.volume);
            fb.putF32(s.pitch);
        },
        .particle => |p| {
            fb.putU8(proto.CMD_PARTICLE);
            fb.putU16(p.id);
            fb.putF64(p.x);
            fb.putF64(p.y);
            fb.putF64(p.z);
            fb.putU16(p.count);
            fb.putF32(p.sx);
            fb.putF32(p.sy);
            fb.putF32(p.sz);
            fb.putF32(p.speed);
        },
        .lightning => |l| {
            fb.putU8(proto.CMD_LIGHTNING);
            fb.putF64(l.x);
            fb.putF64(l.y);
            fb.putF64(l.z);
            fb.putU64(l.seed);
        },
        .aura => |au| {
            fb.putU8(proto.CMD_AURA);
            fb.putU8(au.id);
            fb.putF32(au.radius);
            fb.putU8(au.amplifier);
            fb.putU16(au.duration);
        },
        .purge => |p| {
            fb.putU8(proto.CMD_PURGE);
            fb.putF64(p.x);
            fb.putF64(p.y);
            fb.putF64(p.z);
            fb.putF32(p.radius);
            fb.putU8(p.scope);
        },
    }
}

test {
    // Pull in the tests from the other modules so `zig build test` covers them all.
    _ = @import("protocol.zig");
    _ = @import("brain.zig");
}

test "command batch encodes a header and the reported count" {
    var cmds = brain.CommandList{};
    cmds.add(.{ .atmosphere = .{ .darken = 1.0, .fog = 0.5, .sky = 1.0 } });
    cmds.add(.{ .lightning = .{ .x = 1, .y = 2, .z = 3, .seed = 42 } });

    var fb = proto.FrameBuilder(MAX_FRAME){};
    fb.putU16(@intCast(cmds.len));
    for (cmds.slice()) |cmd| encodeCommand(&fb, cmd);

    const payload = fb.bytes();
    try std.testing.expectEqual(@as(u16, 2), std.mem.readInt(u16, payload[0..2], .little));
    // First command is the atmosphere type tag.
    try std.testing.expectEqual(proto.CMD_ATMOSPHERE, payload[2]);
}

test "purge encodes as tag, position, radius and scope" {
    var fb = proto.FrameBuilder(64){};
    encodeCommand(&fb, .{ .purge = .{ .x = 1, .y = 2, .z = 3, .radius = 0, .scope = proto.PURGE_GLOBAL } });
    const bytes = fb.bytes();
    // tag + 3 x f64 + f32 + u8; the Java decoder reads exactly this shape.
    try std.testing.expectEqual(@as(usize, 1 + 24 + 4 + 1), bytes.len);
    try std.testing.expectEqual(proto.CMD_PURGE, bytes[0]);
    try std.testing.expectEqual(proto.PURGE_GLOBAL, bytes[bytes.len - 1]);
}
