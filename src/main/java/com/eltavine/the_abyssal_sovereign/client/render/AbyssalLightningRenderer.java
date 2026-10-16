package com.eltavine.the_abyssal_sovereign.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Judgement lightning: a fractal braid, not a line.
 *
 * <p>Each bolt is a trunk that forks, and each fork forks again. Every step of a limb is drawn as
 * three twisted ribbons plus a barb, and the strike itself plants a small rose-and-spirograph sigil
 * on the ground. The path is seeded, so a bolt holds its shape while it fades instead of reshuffling
 * every frame. Bolts are client visuals only; the daemon decides where they fall.
 */
public final class AbyssalLightningRenderer {

    private record Bolt(double x, double y, double z, long seed, int startTick) {
    }

    private static final List<Bolt> ACTIVE = new ArrayList<>();
    /** Five bolts a tick at a ten-tick life is fifty alive at once; the cap sits well above that. */
    private static final int MAX_ACTIVE = 240;
    private static final int LIFETIME_TICKS = 10;
    private static final double TOP = 96.0D;
    private static final int LIMB_SEGMENTS = 7;

    private AbyssalLightningRenderer() {
    }

    public static void spawn(double x, double y, double z, long seed, int clientTick) {
        while (ACTIVE.size() >= MAX_ACTIVE) {
            ACTIVE.remove(0);
        }
        ACTIVE.add(new Bolt(x, y, z, seed, clientTick));
    }

    public static void clear() {
        ACTIVE.clear();
    }

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 camPos, int clientTick, float partialTick) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lightning());
        ACTIVE.removeIf(bolt -> clientTick - bolt.startTick() > LIFETIME_TICKS);

        for (Bolt bolt : ACTIVE) {
            float age = (clientTick - bolt.startTick()) + partialTick;
            float fade = Mth.clamp(1.0F - age / LIFETIME_TICKS, 0.0F, 1.0F);
            poseStack.pushPose();
            poseStack.translate(bolt.x() - camPos.x, bolt.y() - camPos.y, bolt.z() - camPos.z);
            Matrix4f matrix = poseStack.last().pose();
            limb(consumer, matrix, new Random(bolt.seed()), 0.0D, TOP, 0.0D, 0.0D, -TOP, 0.0D, 2, 1.15F, fade);
            sigil(consumer, matrix, fade, age);
            poseStack.popPose();
        }
    }

    /**
     * One limb of the bolt. {@code depth} remaining forks are emitted from the middle of the limb,
     * each narrower and shorter, so the shape is a tree rather than a single stroke.
     */
    private static void limb(VertexConsumer c, Matrix4f m, Random random,
                             double x, double y, double z, double dx, double dy, double dz,
                             int depth, float width, float fade) {
        double cx = x;
        double cy = y;
        double cz = z;
        for (int i = 0; i < LIMB_SEGMENTS; i++) {
            float along = (i + 1) / (float) LIMB_SEGMENTS;
            double spread = (1.0 - along) * (4.5 + depth * 2.0);
            double nx = cx + dx / LIMB_SEGMENTS + (random.nextDouble() - 0.5) * spread;
            double ny = cy + dy / LIMB_SEGMENTS;
            double nz = cz + dz / LIMB_SEGMENTS + (random.nextDouble() - 0.5) * spread;
            braid(c, m, cx, cy, cz, nx, ny, nz, width, fade, i);
            barb(c, m, random, cx, cy, cz, nx, ny, nz, width, fade);
            if (depth > 0 && i == LIMB_SEGMENTS / 2) {
                fork(c, m, random, nx, ny, nz, dx, dy, dz, depth - 1, width * 0.62F, fade);
                fork(c, m, random, nx, ny, nz, dx, dy, dz, depth - 1, width * 0.5F, fade);
            }
            cx = nx;
            cy = ny;
            cz = nz;
        }
    }

    private static void fork(VertexConsumer c, Matrix4f m, Random random,
                             double x, double y, double z, double dx, double dy, double dz,
                             int depth, float width, float fade) {
        double yaw = (random.nextDouble() - 0.5) * 1.4;
        double len = 0.55 + random.nextDouble() * 0.35;
        double fx = dx * len * Math.cos(yaw) - dz * len * Math.sin(yaw);
        double fz = dx * len * Math.sin(yaw) + dz * len * Math.cos(yaw);
        limb(c, m, random, x, y, z, fx, dy * len, fz, depth, width, fade * 0.9F);
    }

    /** Three ribbons wound around the segment, so the stroke reads as a cable rather than a line. */
    private static void braid(VertexConsumer c, Matrix4f m,
                              double x0, double y0, double z0, double x1, double y1, double z1,
                              float width, float fade, int index) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4) {
            return;
        }
        // A perpendicular in the XZ plane, plus the one crossing it through Y.
        double px = -dz / len;
        double pz = dx / len;
        int[] colors = {AbyssalPalette.RED_1, AbyssalPalette.RED_4, AbyssalPalette.WHITE_0};
        float[] widths = {width * 1.35F, width * 0.72F, width * 0.28F};
        int[] alphas = {(int) (fade * 120), (int) (fade * 210), (int) (fade * 255)};
        for (int strand = 0; strand < 3; strand++) {
            double twist = (index * 0.9 + strand * 2.094);
            double ox = (px * Math.cos(twist) ) * width * 0.55;
            double oz = (pz * Math.cos(twist) ) * width * 0.55;
            double oy = Math.sin(twist) * width * 0.35;
            ribbon(c, m,
                    x0 + ox, y0 + oy, z0 + oz,
                    x1 + ox, y1 + oy, z1 + oz,
                    widths[strand], colors[strand], alphas[strand]);
        }
    }

    /** A short angled spur off the segment, so the braid grows thorns instead of staying smooth. */
    private static void barb(VertexConsumer c, Matrix4f m, Random random,
                             double x0, double y0, double z0, double x1, double y1, double z1,
                             float width, float fade) {
        double mx = (x0 + x1) * 0.5;
        double my = (y0 + y1) * 0.5;
        double mz = (z0 + z1) * 0.5;
        double reach = 1.6 + random.nextDouble() * 2.4;
        double bx = mx + (random.nextDouble() - 0.5) * reach * 2.0;
        double by = my + (random.nextDouble() - 0.5) * reach;
        double bz = mz + (random.nextDouble() - 0.5) * reach * 2.0;
        ribbon(c, m, mx, my, mz, bx, by, bz, width * 0.22F, AbyssalPalette.WHITE_1, (int) (fade * 180));
    }

    private static void ribbon(VertexConsumer c, Matrix4f m,
                               double x0, double y0, double z0, double x1, double y1, double z1,
                               float width, int rgb, int alpha) {
        if (alpha <= 0) {
            return;
        }
        double dx = x1 - x0;
        double dz = z1 - z0;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double nx;
        double nz;
        if (horiz < 1.0E-4) {
            nx = width;
            nz = 0.0;
        } else {
            nx = -dz / horiz * width;
            nz = dx / horiz * width;
        }
        quad(c, m, x0 - nx, y0, z0 - nz, x0 + nx, y0, z0 + nz, x1 + nx, y1, z1 + nz, x1 - nx, y1, z1 - nz, rgb, alpha);
    }

    /** A rose inside a spirograph, planted where the trunk meets the ground. */
    private static void sigil(VertexConsumer c, Matrix4f m, float fade, float age) {
        int alpha = (int) (fade * 230);
        float rot = age * 0.4F;
        GeoDraw.rose(c, m, 6.5F, 7, 180, 0.28F, AbyssalPalette.RED_4, alpha, rot);
        GeoDraw.rose(c, m, 4.0F, 5, 160, 0.18F, AbyssalPalette.WHITE_0, alpha, -rot * 1.6F);
        GeoDraw.spiro(c, m, 8.0F, 2.4F, 3.2F, 4, 200, 0.16F, AbyssalPalette.RED_3, (int) (fade * 180), rot * 0.7F);
    }

    private static void quad(VertexConsumer c, Matrix4f m,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz,
                             int rgb, int alpha) {
        int r = AbyssalPalette.r(rgb);
        int g = AbyssalPalette.g(rgb);
        int b = AbyssalPalette.b(rgb);
        c.addVertex(m, (float) ax, (float) ay, (float) az).setColor(r, g, b, alpha);
        c.addVertex(m, (float) bx, (float) by, (float) bz).setColor(r, g, b, alpha);
        c.addVertex(m, (float) cx, (float) cy, (float) cz).setColor(r, g, b, alpha);
        c.addVertex(m, (float) dx, (float) dy, (float) dz).setColor(r, g, b, alpha);
    }
}
