package com.eltavine.the_abyssal_sovereign.client.effect;

import com.eltavine.the_abyssal_sovereign.bridge.BossCommand;
import com.eltavine.the_abyssal_sovereign.bridge.Protocol;
import com.eltavine.the_abyssal_sovereign.client.SovereignClient;
import com.eltavine.the_abyssal_sovereign.client.render.AbyssalLightningRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Turns the daemon's one-shot commands into things the player actually feels.
 *
 * <p>Runs on the client thread. In single player the integrated server is present, so damage, shove
 * and effects are applied authoritatively to the server-side player; on a multiplayer client there
 * is no such authority, so the same commands land as local feedback only (a hurt flash, a predicted
 * shove and client-side effects). Either way the shell invents nothing — it just maps ids to vanilla
 * sounds, particles and effects.
 */
public final class LocalPlayerEffector {

    private LocalPlayerEffector() {
    }

    public static void apply(BossCommand command) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (command instanceof BossCommand.Damage damage) {
            applyDamage(minecraft, player, damage);
        } else if (command instanceof BossCommand.Sound sound) {
            level.playLocalSound(sound.x(), sound.y(), sound.z(), mapSound(sound.id()), SoundSource.HOSTILE, sound.volume(), sound.pitch(), false);
        } else if (command instanceof BossCommand.Particle particle) {
            spawnParticles(level, particle);
        } else if (command instanceof BossCommand.Aura aura) {
            applyAura(minecraft, player, aura);
        } else if (command instanceof BossCommand.Lightning lightning) {
            AbyssalLightningRenderer.spawn(lightning.x(), lightning.y(), lightning.z(), lightning.seed(), SovereignClient.clientTick());
        }
    }

    private static void applyDamage(Minecraft minecraft, LocalPlayer player, BossCommand.Damage damage) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            // Single player: the integrated server owns the player, so apply it there for real.
            java.util.UUID uuid = player.getUUID();
            server.execute(() -> {
                ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
                if (serverPlayer == null) {
                    return;
                }
                serverPlayer.hurt(serverPlayer.serverLevel().damageSources().magic(), damage.amount());
                serverPlayer.push(damage.kx(), damage.ky(), damage.kz());
                serverPlayer.hurtMarked = true;
            });
            return;
        }
        // Multiplayer client: no authority, so this is feedback only.
        player.hurtTime = player.hurtDuration = 10;
        player.animateHurt(player.getYRot());
        player.push(damage.kx(), damage.ky(), damage.kz());
        player.hurtMarked = true;
    }

    private static void applyAura(Minecraft minecraft, LocalPlayer player, BossCommand.Aura aura) {
        Holder<MobEffect> effect = mapEffect(aura.id());
        if (effect == null) {
            return;
        }
        player.addEffect(new MobEffectInstance(effect, aura.duration(), aura.amplifier()));
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            java.util.UUID uuid = player.getUUID();
            server.execute(() -> {
                ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
                if (serverPlayer != null) {
                    serverPlayer.addEffect(new MobEffectInstance(effect, aura.duration(), aura.amplifier()));
                }
            });
        }
    }

    private static void spawnParticles(ClientLevel level, BossCommand.Particle particle) {
        ParticleOptions options = mapParticle(particle.id());
        int count = Math.min(particle.count(), 64);
        for (int i = 0; i < count; i++) {
            double ox = (level.random.nextDouble() - 0.5D) * 2.0D * particle.sx();
            double oy = (level.random.nextDouble() - 0.5D) * 2.0D * particle.sy();
            double oz = (level.random.nextDouble() - 0.5D) * 2.0D * particle.sz();
            level.addParticle(options, particle.x() + ox, particle.y() + oy, particle.z() + oz, 0.0D, particle.speed(), 0.0D);
        }
    }

    private static SoundEvent mapSound(int id) {
        return switch (id) {
            case Protocol.SND_ROAR -> SoundEvents.WARDEN_ROAR;
            case Protocol.SND_SONIC_CHARGE -> SoundEvents.WARDEN_SONIC_CHARGE;
            case Protocol.SND_SONIC_BOOM -> SoundEvents.WARDEN_SONIC_BOOM;
            case Protocol.SND_NEARBY_CLOSEST -> SoundEvents.WARDEN_NEARBY_CLOSEST;
            case Protocol.SND_TENDRIL_CLICKS -> SoundEvents.WARDEN_TENDRIL_CLICKS;
            case Protocol.SND_HEARTBEAT -> SoundEvents.WARDEN_HEARTBEAT;
            case Protocol.SND_SCULK_BLOOM -> SoundEvents.SCULK_CATALYST_BLOOM;
            case Protocol.SND_THUNDER -> SoundEvents.LIGHTNING_BOLT_THUNDER;
            default -> SoundEvents.WARDEN_HEARTBEAT;
        };
    }

    private static ParticleOptions mapParticle(int id) {
        return switch (id) {
            case Protocol.PT_SCULK_SOUL -> ParticleTypes.SCULK_SOUL;
            case Protocol.PT_SCULK_CHARGE_POP -> ParticleTypes.SCULK_CHARGE_POP;
            case Protocol.PT_EXPLOSION_EMITTER -> ParticleTypes.EXPLOSION_EMITTER;
            case Protocol.PT_SONIC_BOOM -> ParticleTypes.SONIC_BOOM;
            case Protocol.PT_ELECTRIC_SPARK -> ParticleTypes.ELECTRIC_SPARK;
            case Protocol.PT_END_ROD -> ParticleTypes.END_ROD;
            default -> ParticleTypes.SCULK_SOUL;
        };
    }

    private static Holder<MobEffect> mapEffect(int id) {
        return switch (id) {
            case Protocol.AURA_DARKNESS -> MobEffects.DARKNESS;
            case Protocol.AURA_SLOWNESS -> MobEffects.MOVEMENT_SLOWDOWN;
            case Protocol.AURA_WEAKNESS -> MobEffects.WEAKNESS;
            default -> null;
        };
    }
}
