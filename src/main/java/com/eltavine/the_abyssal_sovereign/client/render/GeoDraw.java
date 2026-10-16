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
}
