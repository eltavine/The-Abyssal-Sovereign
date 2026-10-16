package com.eltavine.the_abyssal_sovereign.config;

import com.eltavine.the_abyssal_sovereign.bridge.DaemonConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * File-based configuration, loaded without touching any Minecraft config API.
 *
 * <p>The mod deliberately registers nothing with the game, config system included. Settings live
 * in a plain {@code .properties} file under the instance's {@code config/} directory, are read with
 * the JDK's own {@link Properties}, and the daemon-relevant subset is forwarded in the HELLO frame.
 * Missing or malformed values fall back to defaults, and a fresh file is written on first run so it
 * is easy to discover and edit.
 */
public record SovereignConfig(
        float engageRadius,
        float bossDistance,
        float bossScale,
        int attackIntervalTicks,
        int lightningIntervalTicks,
        int auraIntervalTicks,
        float damagePerAttack,
        float auraRadius,
        int lightningPerTick,
        String invocationPhrase,
        String commandToken) {

    public static final String FILE_NAME = "abyssal_sovereign.properties";

    public static SovereignConfig defaults() {
        DaemonConfig d = DaemonConfig.defaults();
        return new SovereignConfig(
                d.engageRadius(),
                d.bossDistance(),
                d.bossScale(),
                d.attackIntervalTicks(),
                d.lightningIntervalTicks(),
                d.auraIntervalTicks(),
                d.damagePerAttack(),
                d.auraRadius(),
                d.lightningPerTick(),
                "the abyssal sovereign awakens",
                "sovereign manifest");
    }

    /** The subset the daemon needs, in the shape it decodes. */
    public DaemonConfig toDaemonConfig() {
        return new DaemonConfig(
                engageRadius,
                bossDistance,
                bossScale,
                attackIntervalTicks,
                lightningIntervalTicks,
                auraIntervalTicks,
                damagePerAttack,
                auraRadius,
                lightningPerTick);
    }

    /**
     * Loads {@code <configDir>/abyssal_sovereign.properties}, writing a defaults file first if it is
     * absent. Any read failure yields the defaults rather than throwing, so a bad config never stops
     * the game from loading.
     */
    public static SovereignConfig loadOrCreate(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(configDir);
                SovereignConfig defaults = defaults();
                defaults.write(file);
                return defaults;
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            SovereignConfig d = defaults();
            return new SovereignConfig(
                    parseFloat(props, "engage_radius", d.engageRadius),
                    parseFloat(props, "boss_distance", d.bossDistance),
                    parseFloat(props, "boss_scale", d.bossScale),
                    parseInt(props, "attack_interval_ticks", d.attackIntervalTicks),
                    parseInt(props, "lightning_interval_ticks", d.lightningIntervalTicks),
                    parseInt(props, "aura_interval_ticks", d.auraIntervalTicks),
                    parseFloat(props, "damage_per_attack", d.damagePerAttack),
                    parseFloat(props, "aura_radius", d.auraRadius),
                    parseInt(props, "lightning_per_tick", d.lightningPerTick),
                    props.getProperty("invocation_phrase", d.invocationPhrase).trim(),
                    props.getProperty("command_token", d.commandToken).trim());
        } catch (IOException e) {
            return defaults();
        }
    }

    private void write(Path file) throws IOException {
        Properties props = new Properties();
        props.setProperty("engage_radius", Float.toString(engageRadius));
        props.setProperty("boss_distance", Float.toString(bossDistance));
        props.setProperty("boss_scale", Float.toString(bossScale));
        props.setProperty("attack_interval_ticks", Integer.toString(attackIntervalTicks));
        props.setProperty("lightning_interval_ticks", Integer.toString(lightningIntervalTicks));
        props.setProperty("aura_interval_ticks", Integer.toString(auraIntervalTicks));
        props.setProperty("damage_per_attack", Float.toString(damagePerAttack));
        props.setProperty("aura_radius", Float.toString(auraRadius));
        props.setProperty("lightning_per_tick", Integer.toString(lightningPerTick));
        props.setProperty("invocation_phrase", invocationPhrase);
        props.setProperty("command_token", commandToken);
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Warden Titan: The Abyssal Sovereign - standalone configuration");
        }
    }

    private static float parseFloat(Properties props, String key, float fallback) {
        try {
            String v = props.getProperty(key);
            return v == null ? fallback : Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(Properties props, String key, int fallback) {
        try {
            String v = props.getProperty(key);
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
