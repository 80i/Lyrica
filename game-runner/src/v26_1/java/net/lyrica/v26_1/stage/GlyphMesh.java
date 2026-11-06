package net.lyrica.v26_1.stage;

import java.util.List;

/**
 * Geometry of one glyph at 10 pixels per em, so one em is 10 glyph pixels. y grows downward and the
 * baseline is at y = 0. Treat the arrays as read only; they are shared and not copied.
 *
 * <p>Vertices are on a 1/16384 pixel grid and stored exactly, so a vertex shared by a triangle, a
 * side wall and a rim edge has identical coordinates and extruded glyphs have no cracks.
 *
 * @param triangles front face: 6 floats per triangle (x0, y0, x1, y1, x2, y2). Every triangle has
 *                  positive shoelace area in y-down space, which is clockwise on screen while y
 *                  points down and counter-clockwise once y is flipped to point up.
 * @param sides     side walls, {@link #SIDE_STRIDE} floats per outline edge: (x0, y0, nx0, ny0, x1,
 *                  y1, nx1, ny1), with (nx, ny) the unit outward normal at that end. Normals are
 *                  averaged across shallow angles and split across corners. The wall is the quad
 *                  (p0, z front), (p1, z front), (p1, z back), (p0, z back). The outward direction
 *                  of an edge (dx, dy) is (dy, -dx).
 * @param rim       one entry per outline vertex, {@link #RIM_STRIDE} floats per edge in the same
 *                  order as {@code sides}: (x0, y0, ox0, oy0, x1, y1, ox1, oy1). (ox, oy) points
 *                  outward and is a miter vector of length 1 or more (clamped to 2), so that
 *                  p + k * o moves the edges out by k pixels. A fringe is the quad p0, p1,
 *                  p1 + k * o1, p0 + k * o0. Draw fringes without back-face culling: at a
 *                  concave corner a short edge's quad can flip.
 * @param advance   horizontal advance in glyph pixels
 * @param minX      left bound of the outline, 0 when empty
 * @param minY      top bound (y is negative above the baseline), 0 when empty
 * @param maxX      right bound, 0 when empty
 * @param maxY      bottom bound, 0 when empty
 */
public record GlyphMesh(float[] triangles, float[] sides, float[] rim, float advance,
                        float minX, float minY, float maxX, float maxY) {
    /** Floats per outline edge in {@link #sides()}. */
    public static final int SIDE_STRIDE = 8;
    /** Floats per outline edge in {@link #rim()}. */
    public static final int RIM_STRIDE = 8;

    private static final float[] NONE = new float[0];
    private static final double MITER_LIMIT = 2.0;

    /** A glyph with an advance but nothing to draw, such as a space. */
    public static GlyphMesh blank(float advance) {
        return new GlyphMesh(NONE, NONE, NONE, advance, 0, 0, 0, 0);
    }

    /**
     * Builds the walls and rim from rings out of {@link GlyphTriangulator#normalise}.
     *
     * @param smoothAngleDegrees turns up to this angle share one smooth normal, larger ones are hard
     */
    public static GlyphMesh build(List<float[]> rings, float[] triangles, float advance, float smoothAngleDegrees) {
        int edges = 0;
        for (int i = 0; i < rings.size(); i++) {
            edges += rings.get(i).length / 2;
        }
        double smoothCos = Math.cos(Math.toRadians(smoothAngleDegrees));
        float[] sides = new float[edges * SIDE_STRIDE];
        float[] rim = new float[edges * RIM_STRIDE];
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        int e = 0;
        for (int r = 0; r < rings.size(); r++) {
            float[] ring = rings.get(r);
            int n = ring.length / 2;
            double[] nx = new double[n];
            double[] ny = new double[n];
            for (int i = 0; i < n; i++) {
                int k = (i + 1) % n;
                double dx = ring[k * 2] - ring[i * 2];
                double dy = ring[k * 2 + 1] - ring[i * 2 + 1];
                double len = Math.hypot(dx, dy);
                nx[i] = dy / len;
                ny[i] = -dx / len;
                minX = Math.min(minX, ring[i * 2]);
                minY = Math.min(minY, ring[i * 2 + 1]);
                maxX = Math.max(maxX, ring[i * 2]);
                maxY = Math.max(maxY, ring[i * 2 + 1]);
            }
            for (int i = 0; i < n; i++) {
                int k = (i + 1) % n;
                int p = (i + n - 1) % n;
                int at = e * SIDE_STRIDE;
                writeEnd(sides, rim, at, ring, i, nx[p], ny[p], nx[i], ny[i], smoothCos, nx[i], ny[i]);
                writeEnd(sides, rim, at + 4, ring, k, nx[i], ny[i], nx[k], ny[k], smoothCos, nx[i], ny[i]);
                e++;
            }
        }
        return new GlyphMesh(triangles, sides, rim, advance, minX, minY, maxX, maxY);
    }

    /**
     * Writes one end of an edge: the vertex, its wall normal and its rim vector.
     *
     * @param inX    normal of the edge arriving at this vertex
     * @param outX   normal of the edge leaving it
     * @param ownX   normal of the edge being written, used when the vertex is a hard corner
     */
    private static void writeEnd(float[] sides, float[] rim, int at, float[] ring, int vertex,
                                 double inX, double inY, double outX, double outY, double smoothCos,
                                 double ownX, double ownY) {
        float x = ring[vertex * 2];
        float y = ring[vertex * 2 + 1];
        double dot = inX * outX + inY * outY;
        double sumX = inX + outX;
        double sumY = inY + outY;
        double wallX = ownX;
        double wallY = ownY;
        if (dot >= smoothCos) {
            double len = Math.hypot(sumX, sumY);
            wallX = sumX / len;
            wallY = sumY / len;
        }
        sides[at] = x;
        sides[at + 1] = y;
        sides[at + 2] = (float) wallX;
        sides[at + 3] = (float) wallY;

        double sumLen = Math.hypot(sumX, sumY);
        double rimX;
        double rimY;
        if (sumLen < 1e-9) {
            // The edges fold exactly back on each other, so outward is along the arriving edge.
            rimX = -inY * MITER_LIMIT;
            rimY = inX * MITER_LIMIT;
        } else {
            // The miter runs along the sum of two unit normals and is 2 / |sum| long.
            double len = Math.min(MITER_LIMIT, 2 / sumLen);
            rimX = sumX / sumLen * len;
            rimY = sumY / sumLen * len;
        }
        rim[at] = x;
        rim[at + 1] = y;
        rim[at + 2] = (float) rimX;
        rim[at + 3] = (float) rimY;
    }

    public int triangleCount() {
        return triangles.length / 6;
    }

    /** Outline edges, which is also the number of outline vertices. */
    public int edgeCount() {
        return sides.length / SIDE_STRIDE;
    }

    public boolean isEmpty() {
        return triangles.length == 0;
    }
}
