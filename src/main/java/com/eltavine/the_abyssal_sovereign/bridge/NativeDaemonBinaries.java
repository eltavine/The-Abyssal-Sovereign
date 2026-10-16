package com.eltavine.the_abyssal_sovereign.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Locates and extracts the daemon binary matching the host.
 *
 * <p>The build bundles one prebuilt binary per supported platform under {@code /native/<os>-<arch>/}
 * in the jar. At runtime exactly one applies; it is copied to a temp file and marked executable so
 * it can be spawned. A host with no bundled binary yields {@code null}, which the bridge turns into
 * the "daemon unavailable" path.
 */
public final class NativeDaemonBinaries {

    private NativeDaemonBinaries() {
    }

    /** The {@code <os>-<arch>} key for the host, or {@code null} if unsupported. */
    public static String hostKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osKey;
        if (os.contains("win")) {
            osKey = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osKey = "macos";
        } else if (os.contains("nux") || os.contains("nix")) {
            osKey = "linux";
        } else {
            return null;
        }

        String archKey;
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            archKey = "aarch64";
        } else if (arch.contains("amd64") || arch.contains("x86_64") || arch.equals("x64")) {
            archKey = "x86_64";
        } else {
            return null;
        }

        // macOS x86_64 is intentionally not shipped; only the four chosen targets exist.
        if (osKey.equals("macos") && archKey.equals("x86_64")) {
            return null;
        }
        return osKey + "-" + archKey;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String binaryName() {
        return isWindows() ? "abyssal_sovereign_daemon.exe" : "abyssal_sovereign_daemon";
    }

    /** The in-jar resource path for the host binary, or {@code null} if unsupported. */
    public static String resourcePath() {
        String key = hostKey();
        return key == null ? null : "/native/" + key + "/" + binaryName();
    }

    /**
     * Extracts the host binary to a temp file and marks it executable.
     *
     * @return the extracted path, or {@code null} if no binary is bundled for this host
     */
    public static Path extract() throws IOException {
        String path = resourcePath();
        if (path == null) {
            return null;
        }
        try (InputStream stream = NativeDaemonBinaries.class.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            Path dir = Files.createTempDirectory("abyssal-sovereign-daemon");
            dir.toFile().deleteOnExit();
            Path exe = dir.resolve(binaryName());
            Files.copy(stream, exe, StandardCopyOption.REPLACE_EXISTING);
            exe.toFile().deleteOnExit();
            exe.toFile().setExecutable(true, true);
            return exe;
        }
    }
}
