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
 * The Sovereign's judgement lightning: black/red/white, layered and forked.
 *
 * <p>Each bolt is drawn as three overlaid strokes along the same jagged path — a wide black umbra so
 * it reads against the sky, a red aura, and a thin white core — plus a couple of branching forks that
 * get the same treatment. No entities are involved; bolts are short-lived visuals spawned from the
 * daemon's LIGHTNING commands and drawn on the client thread.
 */
public final class AbyssalLightningRenderer {

    private record Bolt(double x, double y, double z, long seed, int startTick) {
    }

    private static final List<Bolt> ACTIVE = new ArrayList<>();
    private static final int LIFETIME_TICKS = 9;
    private static final int SEGMENTS = 22;
    private static final double TOP_OFFSET = 120.0D;

    private AbyssalLightningRenderer() {
    }

    public static void spawn(double x, double y, double z, long seed, int clientTick) {
        if (ACTIVE.size() > 64) {
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

        Matrix4f matrix = poseStack.last().pose();
        for (Bolt bolt : ACTIVE) {
            float age = (clientTick - bolt.startTick()) + partialTick;
            float fade = Mth.clamp(1.0F - age / LIFETIME_TICKS, 0.0F, 1.0F);
            drawBolt(consumer, matrix, bolt, camPos, age, fade);
        }
    }

    private static void drawBolt(VertexConsumer c, Matrix4f m, Bolt bolt, Vec3 camPos, float age, float fade) {
        Random random = new Random(bolt.seed());
        double baseX = bolt.x() - camPos.x;
        double baseY = bolt.y() - camPos.y;
        double baseZ = bolt.z() - camPos.z;

        double[][] main = path(random, baseX, baseY + TOP_OFFSET, baseZ, baseX, baseY, baseZ, SEGMENTS, 7.0F);
        strokes(c, m, main, fade);

        // A couple of forks peeling off partway down.
        int forks = 2;
        for (int f = 0; f < forks; f++) {
            int from = SEGMENTS / 3 + random.nextInt(SEGMENTS / 3);
            double[] origin = main[from];
            double tx = origin[0] + (random.nextDouble() - 0.5D) * 24.0D;
            double tz = origin[2] + (random.nextDouble() - 0.5D) * 24.0D;
            double[][] branch = path(random, origin[0], origin[1], origin[2], tx, baseY, tz, SEGMENTS / 2, 5.0F);
            strokes(c, m, branch, fade * 0.8F);
        }
    }

    /** The three overlaid strokes (umbra, aura, core) that make one layered bolt. */
    private static void strokes(VertexConsumer c, Matrix4f m, double[][] points, float fade) {
        drawStroke(c, m, points, 1.3F, AbyssalPalette.BLACK_1, (int) (fade * 140));
        drawStroke(c, m, points, 0.8F, AbyssalPalette.RED_4, (int) (fade * 220));
        drawStroke(c, m, points, 0.32F, AbyssalPalette.WHITE_0, (int) (fade * 255));
    }

    private static double[][] path(Random random, double sx, double sy, double sz, double ex, double ey, double ez, int segments, float jitter) {
        double[][] points = new double[segments + 1][3];
        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;
            double x = Mth.lerp(t, sx, ex);
            double y = Mth.lerp(t, sy, ey);
            double z = Mth.lerp(t, sz, ez);
            if (i != 0 && i != segments) {
                double wobble = (1.0 - t) * jitter;
                x += (random.nextDouble() - 0.5D) * wobble;
                z += (random.nextDouble() - 0.5D) * wobble;
            }
            points[i][0] = x;
            points[i][1] = y;
            points[i][2] = z;
        }
        return points;
    }

    private static void drawStroke(VertexConsumer c, Matrix4f m, double[][] points, float width, int rgb, int alpha) {
        if (alpha <= 0) {
            return;
        }
        for (int i = 1; i < points.length; i++) {
            double[] a = points[i - 1];
            double[] b = points[i];
            crossedQuads(c, m, a[0], a[1], a[2], b[0], b[1], b[2], width, rgb, alpha);
        }
    }

    private static void crossedQuads(VertexConsumer c, Matrix4f m,
                                     double x0, double y0, double z0, double x1, double y1, double z1,
                                     float w, int rgb, int a) {
        quad(c, m, x0 - w, y0, z0, x0 + w, y0, z0, x1 + w, y1, z1, x1 - w, y1, z1, rgb, a);
        quad(c, m, x0, y0, z0 - w, x0, y0, z0 + w, x1, y1, z1 + w, x1, y1, z1 - w, rgb, a);
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
