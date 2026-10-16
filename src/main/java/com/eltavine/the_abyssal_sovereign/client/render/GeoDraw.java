package com.eltavine.the_abyssal_sovereign.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Shared flat-geometry primitives for the Sovereign's layered VFX.
 *
 * <p>Everything is drawn on the current pose's {@code y = 0} plane, so callers compose 3D structures
 * by rotating/translating the {@link PoseStack} and calling these. Primitives go to the additive
 * {@code lightning} render type via {@link #glowBuffer}: overlapping reds and whites brighten where
 * they stack while black adds nothing, which is exactly the black/red/white layering effect — and it
 * makes dozens of coplanar layers read as glow rather than fight over opaque pixels.
 */
final class GeoDraw {

    private GeoDraw() {
    }

    /** The additive buffer every geometry layer writes to. */
    static VertexConsumer glowBuffer(MultiBufferSource bufferSource) {
        return bufferSource.getBuffer(RenderType.lightning());
    }

    static void vertex(VertexConsumer c, Matrix4f m, float x, float y, float z, int rgb, int alpha) {
        c.addVertex(m, x, y, z)
                .setColor(AbyssalPalette.r(rgb), AbyssalPalette.g(rgb), AbyssalPalette.b(rgb), alpha);
    }

    /** A flat quad on the current plane (y constant), four corners in winding order. */
    static void quad(VertexConsumer c, Matrix4f m,
                     float x0, float z0, float x1, float z1, float x2, float z2, float x3, float z3,
                     float y, int rgb, int alpha) {
        vertex(c, m, x0, y, z0, rgb, alpha);
        vertex(c, m, x1, y, z1, rgb, alpha);
        vertex(c, m, x2, y, z2, rgb, alpha);
        vertex(c, m, x3, y, z3, rgb, alpha);
    }

    /** A filled annulus (or disc when inner = 0) on the y = 0 plane. */
    static void band(VertexConsumer c, Matrix4f m, float inner, float outer, int segments, int rgb, int alpha) {
        for (int i = 0; i < segments; i++) {
            float a0 = i * Mth.TWO_PI / segments;
            float a1 = (i + 1) * Mth.TWO_PI / segments;
            float c0 = Mth.cos(a0);
            float s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1);
            float s1 = Mth.sin(a1);
            quad(c, m,
                    c0 * inner, s0 * inner,
                    c0 * outer, s0 * outer,
                    c1 * outer, s1 * outer,
                    c1 * inner, s1 * inner,
                    0.0F, rgb, alpha);
        }
    }

    /**
     * A regular or star polygon outline drawn as a ribbon of the given thickness.
     *
     * @param step corners skipped per edge; {@code 1} is a convex polygon, higher values draw star
     *     polygons ({@code sides=7, step=3} is a heptagram), which is where the "uncommon geometry"
     *     comes from
     */
    static void polygon(VertexConsumer c, Matrix4f m, float radius, int sides, int step, float thickness,
                        int rgb, int alpha, float rotDegrees) {
        float rot = rotDegrees * Mth.DEG_TO_RAD;
        float half = thickness * 0.5F;
        for (int i = 0; i < sides; i++) {
            float aA = rot + (i * step) * Mth.TWO_PI / sides;
            float aB = rot + ((i + 1) * step) * Mth.TWO_PI / sides;
            float ax = Mth.cos(aA) * radius;
            float az = Mth.sin(aA) * radius;
            float bx = Mth.cos(aB) * radius;
            float bz = Mth.sin(aB) * radius;
            float dx = bx - ax;
            float dz = bz - az;
            float len = Mth.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-4F) {
                continue;
            }
            float nx = -dz / len * half;
            float nz = dx / len * half;
            quad(c, m, ax - nx, az - nz, ax + nx, az + nz, bx + nx, bz + nz, bx - nx, bz - nz, 0.0F, rgb, alpha);
        }
    }

    /** Radial spokes/teeth around a ring: {@code count} short tangential ribbons. */
    static void teeth(VertexConsumer c, Matrix4f m, float radius, int count, float length, float width,
                      int rgb, int alpha, float rotDegrees) {
        float rot = rotDegrees * Mth.DEG_TO_RAD;
        for (int i = 0; i < count; i++) {
            float a = rot + i * Mth.TWO_PI / count;
            float ca = Mth.cos(a);
            float sa = Mth.sin(a);
            float inner = radius - length * 0.5F;
            float outer = radius + length * 0.5F;
            float px = -sa * width * 0.5F;
            float pz = ca * width * 0.5F;
            quad(c, m,
                    ca * inner - px, sa * inner - pz,
                    ca * inner + px, sa * inner + pz,
                    ca * outer + px, sa * outer + pz,
                    ca * outer - px, sa * outer - pz,
                    0.0F, rgb, alpha);
        }
    }

    /**
     * Scratch for parametric curves. Rendering happens on the client thread, so one buffer is enough
     * as long as a curve is stroked before the next one is sampled into it.
     */
    private static final float[] CURVE_X = new float[360];
    private static final float[] CURVE_Z = new float[360];

    /** A sampled polyline as a ribbon of constant thickness. The samples are what make the shape. */
    static void stroke(VertexConsumer c, Matrix4f m, float[] xs, float[] zs, int count, float thickness, int rgb, int alpha) {
        float half = thickness * 0.5F;
        for (int i = 1; i < count; i++) {
            float ax = xs[i - 1];
            float az = zs[i - 1];
            float bx = xs[i];
            float bz = zs[i];
            float dx = bx - ax;
            float dz = bz - az;
            float len = Mth.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-4F) {
                continue;
            }
            float nx = -dz / len * half;
            float nz = dx / len * half;
            quad(c, m, ax - nx, az - nz, ax + nx, az + nz, bx + nx, bz + nz, bx - nx, bz - nz, 0.0F, rgb, alpha);
        }
    }

    /** Rhodonea curve {@code r = cos(petals · θ)}. Petals ≥ 2, so this is a flower, never a circle. */
    static void rose(VertexConsumer c, Matrix4f m, float radius, int petals, int samples, float thickness,
                     int rgb, int alpha, float rotRadians) {
        int n = sampleRose(radius, petals, samples, rotRadians);
        stroke(c, m, CURVE_X, CURVE_Z, n, thickness, rgb, alpha);
    }

    /**
     * Spirograph (hypotrochoid). {@code R}, {@code r} and {@code d} are chosen so the ratio is not 1,
     * which is the degenerate case that collapses into a plain ring.
     */
    static void spiro(VertexConsumer c, Matrix4f m, float bigR, float smallR, float d, int turns, int samples,
                      float thickness, int rgb, int alpha, float rotRadians) {
        int n = sampleSpiro(bigR, smallR, d, turns, samples, rotRadians);
        stroke(c, m, CURVE_X, CURVE_Z, n, thickness, rgb, alpha);
    }

    /** Lissajous figure. {@code a} and {@code b} must differ, or the figure degenerates into an ellipse. */
    static void lissajous(VertexConsumer c, Matrix4f m, float ax, float az, int a, int b, float phase, int samples,
                          float thickness, int rgb, int alpha, float rotRadians) {
        int n = sampleLissajous(ax, az, a, b, phase, samples, rotRadians);
        stroke(c, m, CURVE_X, CURVE_Z, n, thickness, rgb, alpha);
    }

    private static int sampleRose(float radius, int petals, int samples, float rot) {
        int n = Math.min(samples, CURVE_X.length);
        float c = Mth.cos(rot);
        float s = Mth.sin(rot);
        for (int i = 0; i < n; i++) {
            float t = i * Mth.TWO_PI / (n - 1);
            float radial = radius * Mth.cos(petals * t);
            float x = Mth.cos(t) * radial;
            float z = Mth.sin(t) * radial;
            CURVE_X[i] = x * c - z * s;
            CURVE_Z[i] = x * s + z * c;
        }
        return n;
    }

    private static int sampleSpiro(float bigR, float smallR, float d, int turns, int samples, float rot) {
        int n = Math.min(samples, CURVE_X.length);
        float c = Mth.cos(rot);
        float s = Mth.sin(rot);
        float k = (bigR - smallR) / smallR;
        for (int i = 0; i < n; i++) {
            float t = i * (Mth.TWO_PI * turns) / (n - 1);
            float x = (bigR - smallR) * Mth.cos(t) + d * Mth.cos(k * t);
            float z = (bigR - smallR) * Mth.sin(t) - d * Mth.sin(k * t);
            CURVE_X[i] = x * c - z * s;
            CURVE_Z[i] = x * s + z * c;
        }
        return n;
    }

    private static int sampleLissajous(float ax, float az, int a, int b, float phase, int samples, float rot) {
        int n = Math.min(samples, CURVE_X.length);
        float c = Mth.cos(rot);
        float s = Mth.sin(rot);
        for (int i = 0; i < n; i++) {
            float t = i * Mth.TWO_PI / (n - 1);
            float x = ax * Mth.sin(a * t + phase);
            float z = az * Mth.sin(b * t);
            CURVE_X[i] = x * c - z * s;
            CURVE_Z[i] = x * s + z * c;
        }
        return n;
    }
}
