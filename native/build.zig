const std = @import("std");

// Build the Abyssal Sovereign native daemon.
//
// The daemon is a standalone, dependency-free executable. The Java side extracts the
// binary matching the host platform from the mod jar and speaks to it over stdio using
// the length-prefixed binary protocol in `src/protocol.zig`.
//
// Cross compilation is driven entirely from Gradle, which invokes `zig build` once per
// target triple with `-Dtarget=...`. Keeping every target going through the same step
// means the four shipped binaries are produced by identical code paths.
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    // Default to ReleaseSafe: the daemon drives a boss that "cannot be shaken", so keeping
    // safety checks on in shipped binaries is worth the small cost.
    const optimize = b.standardOptimizeOption(.{ .preferred_optimize_mode = .ReleaseSafe });

    const exe = b.addExecutable(.{
        .name = "abyssal_sovereign_daemon",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/main.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    b.installArtifact(exe);

    const run_cmd = b.addRunArtifact(exe);
    run_cmd.step.dependOn(b.getInstallStep());
    const run_step = b.step("run", "Run the daemon (reads framed messages on stdin)");
    run_step.dependOn(&run_cmd.step);

    const unit_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/main.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    const run_unit_tests = b.addRunArtifact(unit_tests);
    const test_step = b.step("test", "Run daemon unit tests");
    test_step.dependOn(&run_unit_tests.step);
}
