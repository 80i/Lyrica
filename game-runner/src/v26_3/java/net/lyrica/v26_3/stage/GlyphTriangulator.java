package net.lyrica.v26_3.stage;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns flattened glyph contours into triangles: outlines are first normalised through
 * {@link Area} (so overlaps, any winding direction and either fill rule all reduce to plain
 * non-crossing rings), snapped to an integer grid, then ear clipped with exact integer
 * orientation tests and holes joined to their outer ring by bridges.
 *
 * <p>All output coordinates sit on a 1/16384 pixel grid, which a float stores exactly, so a vertex
 * shared by a triangle and a side wall has bit-identical coordinates and the mesh has no cracks.
 *
 * <p>Winding convention, in the y-down glyph space: outer rings and every triangle have a positive
 * shoelace area (x0*y1 - x1*y0 summed), holes a negative one. Walking a ring, the outward normal
 * of an edge (dx, dy) is (dy, -dx).
 */
public final class GlyphTriangulator {
    /** Grid cells per glyph pixel. */
    static final int SCALE = 1 << 14;
    /** Rings whose doubled grid area is under this are invisible slivers (1e-4 square pixels). */
    private static final long MIN_AREA2 = (long) (2 * 1e-4 * SCALE * SCALE);
    private static final int MAX_CURVE_STEPS = 64;
    /** A span whose mean distance from its chord is under this share of tolerance counts as straight. */
    private static final double NUDGE_STRAIGHT = 0.02;
    /** The furthest a point may be nudged, as a share of tolerance. */
    private static final double NUDGE_LIMIT = 1.0;
    /** Share of a ring's area it may be off before it is grown evenly to match. */
    private static final double SETTLE_AREA = 0.0005;
    /** A flattened edge this long, in pixels, is a drawn line and not a curve chord: its ends stay put. */
    private static final double LINE_EDGE = 0.4;
    /** The same for an edge exactly upright or flat, which a curve chord almost never is. */
    private static final double AXIS_EDGE = 0.05;
    /** Share of a ring's area it may still be off after settling before it is thinned less. */
    private static final double MISSED_AREA = 0.001;
    /** The finest tolerance a ring is thinned to in order to keep its area. */
    private static final double MIN_RETHIN = 0.002;
    /** Rounds of splitting edges that rounding to the grid made cross. One is nearly always enough. */
    private static final int MAX_SNAP_ROUNDS = 8;

    /** How often the clipper had to accept a non-ideal ear. Only read by the test harness. */
    static final AtomicLong RELAXED_EARS = new AtomicLong();
    /** How often a hole found no clean bridge. Only read by the test harness. */
    static final AtomicLong RELAXED_BRIDGES = new AtomicLong();

    private GlyphTriangulator() {
    }

    /**
     * Flattens a shape into closed contours of (x, y) pairs, keeping subpath order and direction.
     * Curves get an even step count sized from their curvature.
     *
     * @param tolerance the largest allowed chord error, in the shape's units
     */
    public static List<double[]> flatten(Shape shape, double tolerance) {
        List<double[]> out = new ArrayList<>();
        DoubleBuf cur = new DoubleBuf(64);
        double[] s = new double[6];
        double px = 0;
        double py = 0;
        double sx = 0;
        double sy = 0;
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(s)) {
                case PathIterator.SEG_MOVETO -> {
                    endContour(cur, out);
                    px = sx = s[0];
                    py = sy = s[1];
                    cur.add(px);
                    cur.add(py);
                }
                case PathIterator.SEG_LINETO -> {
                    px = s[0];
                    py = s[1];
                    cur.add(px);
                    cur.add(py);
                }
                case PathIterator.SEG_QUADTO -> {
                    double dd = Math.hypot(px - 2 * s[0] + s[2], py - 2 * s[1] + s[3]);
                    addCurve(cur, steps(dd / (4 * tolerance)), px, py, s[0], s[1], s[0], s[1], s[2], s[3], true);
                    px = s[2];
                    py = s[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    double d1 = Math.hypot(px - 2 * s[0] + s[2], py - 2 * s[1] + s[3]);
                    double d2 = Math.hypot(s[0] - 2 * s[2] + s[4], s[1] - 2 * s[3] + s[5]);
                    double dd = 6 * Math.max(d1, d2);
                    addCurve(cur, steps(dd / (8 * tolerance)), px, py, s[0], s[1], s[2], s[3], s[4], s[5], false);
                    px = s[4];
                    py = s[5];
                }
                default -> {
                    px = sx;
                    py = sy;
                    endContour(cur, out);
                }
            }
        }
        endContour(cur, out);
        return out;
    }

    private static int steps(double squared) {
        int n = (int) Math.ceil(Math.sqrt(squared));
        return Math.max(1, Math.min(MAX_CURVE_STEPS, n));
    }

    private static void addCurve(DoubleBuf cur, int n, double x0, double y0, double x1, double y1,
                                 double x2, double y2, double x3, double y3, boolean quad) {
        double[] p = new double[2];
        for (int i = 1; i < n; i++) {
            evaluate((double) i / n, x0, y0, x1, y1, x2, y2, x3, y3, quad, p);
            cur.add(p[0]);
            cur.add(p[1]);
        }
        cur.add(x3);
        cur.add(y3);
    }

    private static void evaluate(double t, double x0, double y0, double x1, double y1,
                                 double x2, double y2, double x3, double y3, boolean quad, double[] out) {
        double u = 1 - t;
        if (quad) {
            out[0] = u * u * x0 + 2 * u * t * x1 + t * t * x3;
            out[1] = u * u * y0 + 2 * u * t * y1 + t * t * y3;
        } else {
            double a = u * u * u;
            double b = 3 * u * u * t;
            double c = 3 * u * t * t;
            double d = t * t * t;
            out[0] = a * x0 + b * x1 + c * x2 + d * x3;
            out[1] = a * y0 + b * y1 + c * y2 + d * y3;
        }
    }

    private static void endContour(DoubleBuf cur, List<double[]> out) {
        if (cur.size >= 6) {
            out.add(cur.toArray());
        }
        cur.size = 0;
    }

    /**
     * Thins finely flattened contours down to the fewest points that stay within tolerance, then
     * moves the points on curves slightly so each ring keeps its true area. Plain chords always cut
     * inside a curve; the nudged polygon straddles it instead, which lowers the silhouette error
     * for the same point count and stops small dots from shrinking. Both ends of a drawn line are
     * always kept and never move, so stems stay parallel and flat tops stay flat.
     *
     * @param tolerance the largest distance from the input outline before nudging
     */
    public static List<double[]> simplify(List<double[]> contours, double tolerance) {
        List<double[]> out = new ArrayList<>(contours.size());
        for (int i = 0; i < contours.size(); i++) {
            double[] ring = simplifyRing(contours.get(i), tolerance);
            if (ring != null) {
                out.add(ring);
            }
        }
        return out;
    }

    private static double[] simplifyRing(double[] f, double tolerance) {
        double[] ring = thinRing(f, tolerance);
        double target = Math.abs(ringArea(f));
        // Curves between line ends cannot be nudged, so a ring that still misses its area keeps more of them.
        double step = tolerance;
        while (ring != null && step > MIN_RETHIN && Math.abs(Math.abs(ringArea(ring)) - target) > target * MISSED_AREA) {
            step *= 0.5;
            ring = thinRing(f, step);
        }
        return ring;
    }

    private static double[] thinRing(double[] f, double tolerance) {
        int n = f.length / 2;
        if (n < 3) {
            return null;
        }
        boolean[] pinned = lineEnds(f, n);
        int[] kept = keptPoints(f, n, tolerance, pinned);
        int m = kept.length;
        if (m < 3) {
            return null;
        }
        boolean[] fixed = new boolean[m];
        for (int j = 0; j < m; j++) {
            fixed[j] = pinned[kept[j]];
        }
        double[] base = new double[m * 2];
        for (int j = 0; j < m; j++) {
            base[j * 2] = f[kept[j] * 2];
            base[j * 2 + 1] = f[kept[j] * 2 + 1];
        }
        // Area each span between kept points loses against the outline it replaced.
        double[] spanArea = new double[m];
        boolean[] straight = new boolean[m];
        for (int j = 0; j < m; j++) {
            int from = kept[j];
            int to = kept[(j + 1) % m];
            int steps = (to - from + n) % n;
            double sum = 0;
            for (int k = 0; k < steps; k++) {
                int a = (from + k) % n;
                int b = (a + 1) % n;
                sum += f[a * 2] * f[b * 2 + 1] - f[b * 2] * f[a * 2 + 1];
            }
            sum += f[to * 2] * f[from * 2 + 1] - f[from * 2] * f[to * 2 + 1];
            spanArea[j] = sum * 0.5;
            double chord = Math.hypot(f[to * 2] - f[from * 2], f[to * 2 + 1] - f[from * 2 + 1]);
            straight[j] = steps == 1 || Math.abs(spanArea[j]) <= chord * tolerance * NUDGE_STRAIGHT;
        }
        // Each curved span hands its area to its ends, except ends that also hold a straight edge.
        double[] want = new double[m];
        boolean any = false;
        for (int j = 0; j < m; j++) {
            if (straight[j]) {
                continue;
            }
            int end = (j + 1) % m;
            boolean startFree = !straight[(j + m - 1) % m];
            boolean endFree = !straight[end];
            if (startFree && endFree) {
                want[j] += spanArea[j] * 0.5;
                want[end] += spanArea[j] * 0.5;
            } else if (startFree) {
                want[j] += spanArea[j];
            } else if (endFree) {
                want[end] += spanArea[j];
            }
            any |= startFree || endFree;
        }
        double target = ringArea(f);
        if (!any) {
            return settleArea(base, target, tolerance, fixed);
        }
        double[] move = new double[m * 2];
        double limit = tolerance * NUDGE_LIMIT;
        for (int j = 0; j < m; j++) {
            int a = (j + m - 1) % m;
            int c = (j + 1) % m;
            double ux = base[c * 2] - base[a * 2];
            double uy = base[c * 2 + 1] - base[a * 2 + 1];
            double len = Math.hypot(ux, uy);
            if (want[j] != 0 && len > 1e-9) {
                double amount = Math.max(-limit, Math.min(limit, 2 * want[j] / len));
                move[j * 2] = uy / len * amount;
                move[j * 2 + 1] = -ux / len * amount;
            }
        }
        double start = ringArea(base);
        double[] moved = new double[m * 2];
        double gain = nudged(base, move, 1, moved) - start;
        if (Math.abs(gain) < 1e-12) {
            return settleArea(base, target, tolerance, fixed);
        }
        double k = clampNudge((target - start) / gain);
        double got = nudged(base, move, k, moved);
        k = clampNudge(k + (target - got) / gain);
        nudged(base, move, k, moved);
        return settleArea(moved, target, tolerance, fixed);
    }

    /**
     * A ring that still misses its area (thin bars with rounded ends, whose every point holds a
     * straight edge) is grown evenly by a tiny amount. Rings already within SETTLE_AREA are untouched,
     * and so are the ends of drawn lines: a chord can miss a little area, but a stem must not lean.
     */
    private static double[] settleArea(double[] ring, double target, double tolerance, boolean[] fixed) {
        double missing = target - ringArea(ring);
        if (Math.abs(missing) <= Math.abs(target) * SETTLE_AREA) {
            return ring;
        }
        int m = ring.length / 2;
        double total = 0;
        for (int j = 0; j < m; j++) {
            if (fixed[j]) {
                continue;
            }
            int a = (j + m - 1) % m;
            int c = (j + 1) % m;
            total += Math.hypot(ring[c * 2] - ring[a * 2], ring[c * 2 + 1] - ring[a * 2 + 1]);
        }
        if (total <= 1e-9) {
            return ring;
        }
        double amount = Math.max(-tolerance, Math.min(tolerance, 2 * missing / total));
        double[] out = new double[m * 2];
        for (int j = 0; j < m; j++) {
            int a = (j + m - 1) % m;
            int c = (j + 1) % m;
            double ux = ring[c * 2] - ring[a * 2];
            double uy = ring[c * 2 + 1] - ring[a * 2 + 1];
            double len = Math.hypot(ux, uy);
            double scale = len > 1e-9 && !fixed[j] ? amount / len : 0;
            out[j * 2] = ring[j * 2] + uy * scale;
            out[j * 2 + 1] = ring[j * 2 + 1] - ux * scale;
        }
        return out;
    }

    private static double clampNudge(double k) {
        return Math.max(0, Math.min(1.25, k));
    }

    private static double nudged(double[] base, double[] move, double k, double[] out) {
        for (int i = 0; i < base.length; i++) {
            out[i] = base[i] + move[i] * k;
        }
        return ringArea(out);
    }

    static double ringArea(double[] c) {
        double sum = 0;
        int n = c.length / 2;
        double jx = c[(n - 1) * 2];
        double jy = c[(n - 1) * 2 + 1];
        for (int i = 0; i < n; i++) {
            double ix = c[i * 2];
            double iy = c[i * 2 + 1];
            sum += jx * iy - ix * jy;
            jx = ix;
            jy = iy;
        }
        return sum * 0.5;
    }

    /** The points at either end of a flattened edge long or square enough to be a drawn line. */
    private static boolean[] lineEnds(double[] f, int n) {
        boolean[] pinned = new boolean[n];
        for (int i = 0; i < n; i++) {
            int k = (i + 1) % n;
            double dx = f[k * 2] - f[i * 2];
            double dy = f[k * 2 + 1] - f[i * 2 + 1];
            double len = Math.hypot(dx, dy);
            if (len >= LINE_EDGE || (len >= AXIS_EDGE && (dx == 0 || dy == 0))) {
                pinned[i] = true;
                pinned[k] = true;
            }
        }
        return pinned;
    }

    /** Douglas-Peucker on a closed ring, starting from the pinned points: indices to keep, ascending. */
    private static int[] keptPoints(double[] f, int n, double tolerance, boolean[] pinned) {
        int a = 0;
        for (int i = 1; i < n; i++) {
            if (f[i * 2] < f[a * 2]) {
                a = i;
            }
        }
        int b = a;
        double far = -1;
        for (int i = 0; i < n; i++) {
            double d = Math.hypot(f[i * 2] - f[a * 2], f[i * 2 + 1] - f[a * 2 + 1]);
            if (d > far) {
                far = d;
                b = i;
            }
        }
        if (a == b) {
            return new int[0];
        }
        boolean[] keep = Arrays.copyOf(pinned, n);
        keep[a] = true;
        keep[b] = true;
        int[] stack = new int[64];
        int top = 0;
        int start = a;
        for (int step = 1; step <= n; step++) {
            int i = (a + step) % n;
            if (keep[i]) {
                if (top + 2 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                stack[top++] = start;
                stack[top++] = (i - start + n) % n;
                start = i;
            }
        }
        int forced = -1;
        double forcedDist = -1;
        while (top > 0) {
            int steps = stack[--top];
            int from = stack[--top];
            int to = (from + steps) % n;
            int best = -1;
            double bestDist = -1;
            for (int k = 1; k < steps; k++) {
                int i = (from + k) % n;
                double d = segmentDistance(f[i * 2], f[i * 2 + 1], f[from * 2], f[from * 2 + 1], f[to * 2], f[to * 2 + 1]);
                if (d > bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            if (best >= 0 && bestDist > forcedDist) {
                forcedDist = bestDist;
                forced = best;
            }
            if (best >= 0 && bestDist > tolerance) {
                keep[best] = true;
                if (top + 4 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                stack[top++] = from;
                stack[top++] = (best - from + n) % n;
                stack[top++] = best;
                stack[top++] = (to - best + n) % n;
            }
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                count++;
            }
        }
        if (count < 3 && forced >= 0 && !keep[forced]) {
            keep[forced] = true;
            count++;
        }
        int[] kept = new int[count];
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                kept[w++] = i;
            }
        }
        return kept;
    }

    private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 <= 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    /** Builds a polygon path, keeping the fill rule. */
    public static Path2D.Double toPath(List<double[]> contours, int windingRule) {
        Path2D.Double path = new Path2D.Double(windingRule);
        for (int i = 0; i < contours.size(); i++) {
            double[] c = contours.get(i);
            if (c.length < 6) {
                continue;
            }
            path.moveTo(c[0], c[1]);
            for (int k = 2; k + 1 < c.length; k += 2) {
                path.lineTo(c[k], c[k + 1]);
            }
            path.closePath();
        }
        return path;
    }

    /**
     * Normalises contours through {@link Area}: overlaps are united, crossings split, collinear and
     * duplicate points and slivers dropped, and the result is ordered as each outer ring (positive
     * area) followed by its own holes (negative area).
     *
     * @param contours closed contours of (x, y) pairs, in any direction
     * @param windingRule {@link PathIterator#WIND_NON_ZERO} or {@link PathIterator#WIND_EVEN_ODD}
     * @return rings of (x, y) float pairs on the 1/16384 grid, the input of {@link #triangulateClean}
     */
    public static List<float[]> normalise(List<double[]> contours, int windingRule) {
        return normalise(new Area(toPath(contours, windingRule)));
    }

    /**
     * The same as {@link #normalise(List, int)} for an area that was built from straight lines.
     *
     * <p>{@link Area} may cut a shape into pieces that meet along a horizontal seam, as two rings
     * running the same edge in opposite directions. That is harmless for filling but would put walls
     * and fringes inside a letter, so the rings are broken into edges, seams cancelled, and rings
     * traced again, each visiting a point at most once.
     */
    public static List<float[]> normalise(Area area) {
        List<int[]> raw = new ArrayList<>();
        DoubleBuf cur = new DoubleBuf(64);
        double[] s = new double[6];
        for (PathIterator it = area.getPathIterator(null, 0.001); !it.isDone(); it.next()) {
            switch (it.currentSegment(s)) {
                case PathIterator.SEG_MOVETO -> {
                    addRing(cur, raw);
                    cur.add(s[0]);
                    cur.add(s[1]);
                }
                case PathIterator.SEG_LINETO -> {
                    cur.add(s[0]);
                    cur.add(s[1]);
                }
                default -> addRing(cur, raw);
            }
        }
        addRing(cur, raw);
        List<int[]> traced = traceRings(cancelSeams(raw));
        long[] shared = sharedPoints(traced);
        List<int[]> rings = new ArrayList<>(traced.size());
        for (int i = 0; i < traced.size(); i++) {
            int[] ring = traced.get(i);
            int[] clean = cleanRing(ring, ring.length / 2, shared);
            if (clean != null) {
                rings.add(clean);
            }
        }
        return order(rings);
    }

    private static void addRing(DoubleBuf cur, List<int[]> rings) {
        int n = cur.size / 2;
        if (n >= 3) {
            int[] xy = new int[n * 2];
            for (int i = 0; i < n * 2; i++) {
                xy[i] = (int) Math.round(cur.data[i] * SCALE);
            }
            rings.add(xy);
        }
        cur.size = 0;
    }

    /**
     * All edges of the rings as (x0, y0, x1, y1), split wherever another edge's point lies on them,
     * with any edge covered an even number of times gone. Rounding to the grid can make edges that
     * only touched cross by a hair; those are cut at the grid point nearest the crossing, and the
     * cuts checked again, so the edges returned meet only at their ends.
     */
    private static List<int[]> cancelSeams(List<int[]> rings) {
        int total = 0;
        for (int r = 0; r < rings.size(); r++) {
            total += rings.get(r).length / 2;
        }
        List<int[]> edges = new ArrayList<>(total);
        for (int r = 0; r < rings.size(); r++) {
            int[] ring = rings.get(r);
            int n = ring.length / 2;
            for (int i = 0; i < n; i++) {
                int k = (i + 1) % n;
                int[] edge = canonical(ring[i * 2], ring[i * 2 + 1], ring[k * 2], ring[k * 2 + 1]);
                if (edge[0] != edge[2] || edge[1] != edge[3]) {
                    edges.add(edge);
                }
            }
        }
        List<int[]> kept = oddEdges(splitAtPoints(edges));
        for (int round = 0; round < MAX_SNAP_ROUNDS; round++) {
            List<int[]> cut = cutCrossings(kept);
            if (cut == null) {
                break;
            }
            kept = oddEdges(splitAtPoints(cut));
        }
        return kept;
    }

    private static List<int[]> splitAtPoints(List<int[]> edges) {
        long[] points = new long[edges.size() * 2];
        int w = 0;
        for (int i = 0; i < edges.size(); i++) {
            int[] e = edges.get(i);
            points[w++] = pointKey(e[0], e[1]);
            points[w++] = pointKey(e[2], e[3]);
        }
        Arrays.sort(points);
        List<int[]> out = new ArrayList<>(edges.size());
        LongBuf between = new LongBuf();
        for (int i = 0; i < edges.size(); i++) {
            addSplit(edges.get(i), points, between, out);
        }
        return out;
    }

    /** Sorted edges, keeping one of each edge covered an odd number of times. */
    private static List<int[]> oddEdges(List<int[]> edges) {
        edges.sort(GlyphTriangulator::compareEdges);
        List<int[]> kept = new ArrayList<>(edges.size());
        int i = 0;
        while (i < edges.size()) {
            int j = i + 1;
            while (j < edges.size() && compareEdges(edges.get(i), edges.get(j)) == 0) {
                j++;
            }
            if (((j - i) & 1) != 0) {
                kept.add(edges.get(i));
            }
            i = j;
        }
        return kept;
    }

    /** The edges again with every proper crossing cut at its nearest grid point, or null if none cross. */
    private static List<int[]> cutCrossings(List<int[]> sorted) {
        int n = sorted.size();
        LongBuf[] cuts = null;
        for (int i = 0; i < n; i++) {
            int[] e = sorted.get(i);
            int minY = Math.min(e[1], e[3]);
            int maxY = Math.max(e[1], e[3]);
            for (int j = i + 1; j < n; j++) {
                int[] f = sorted.get(j);
                if (f[0] > e[2]) {
                    break;
                }
                if (Math.max(f[1], f[3]) < minY || Math.min(f[1], f[3]) > maxY) {
                    continue;
                }
                long o1 = orient(e[0], e[1], e[2], e[3], f[0], f[1]);
                long o2 = orient(e[0], e[1], e[2], e[3], f[2], f[3]);
                if (o1 == 0 || o2 == 0 || (o1 > 0) == (o2 > 0)) {
                    continue;
                }
                long o3 = orient(f[0], f[1], f[2], f[3], e[0], e[1]);
                long o4 = orient(f[0], f[1], f[2], f[3], e[2], e[3]);
                if (o3 == 0 || o4 == 0 || (o3 > 0) == (o4 > 0)) {
                    continue;
                }
                double t = (double) o1 / ((double) o1 - o2);
                long key = pointKey((int) Math.round(f[0] + t * (f[2] - f[0])), (int) Math.round(f[1] + t * (f[3] - f[1])));
                if (cuts == null) {
                    cuts = new LongBuf[n];
                }
                addCut(cuts, i, key);
                addCut(cuts, j, key);
            }
        }
        if (cuts == null) {
            return null;
        }
        List<int[]> out = new ArrayList<>(n + 8);
        for (int i = 0; i < n; i++) {
            int[] e = sorted.get(i);
            if (cuts[i] == null) {
                out.add(e);
            } else {
                addThrough(e, cuts[i], out);
            }
        }
        return out;
    }

    private static void addCut(LongBuf[] cuts, int edge, long key) {
        if (cuts[edge] == null) {
            cuts[edge] = new LongBuf();
        }
        cuts[edge].add(key);
    }

    /** Replaces an edge by a chain through its cut points, in order along it. */
    private static void addThrough(int[] e, LongBuf cuts, List<int[]> out) {
        long dx = (long) e[2] - e[0];
        long dy = (long) e[3] - e[1];
        long[] along = new long[cuts.size];
        for (int i = 0; i < cuts.size; i++) {
            along[i] = (keyX(cuts.data[i]) - e[0]) * dx + (keyY(cuts.data[i]) - e[1]) * dy;
            for (int k = i; k > 0 && along[k - 1] > along[k]; k--) {
                long a = along[k];
                along[k] = along[k - 1];
                along[k - 1] = a;
                long c = cuts.data[k];
                cuts.data[k] = cuts.data[k - 1];
                cuts.data[k - 1] = c;
            }
        }
        int px = e[0];
        int py = e[1];
        for (int i = 0; i < cuts.size; i++) {
            int x = keyX(cuts.data[i]);
            int y = keyY(cuts.data[i]);
            if (x != px || y != py) {
                out.add(canonical(px, py, x, y));
                px = x;
                py = y;
            }
        }
        if (px != e[2] || py != e[3]) {
            out.add(canonical(px, py, e[2], e[3]));
        }
    }

    /** Adds a canonical edge in pieces, cut at every point lying inside it. */
    private static void addSplit(int[] e, long[] points, LongBuf between, List<int[]> out) {
        between.size = 0;
        int minY = Math.min(e[1], e[3]);
        int maxY = Math.max(e[1], e[3]);
        int from = Arrays.binarySearch(points, pointKey(e[0], Integer.MIN_VALUE));
        from = from < 0 ? -from - 1 : from;
        for (int i = from; i < points.length; i++) {
            int x = keyX(points[i]);
            if (x > e[2]) {
                break;
            }
            int y = keyY(points[i]);
            if (y >= minY && y <= maxY && orient(e[0], e[1], e[2], e[3], x, y) == 0
                    && !(x == e[0] && y == e[1]) && !(x == e[2] && y == e[3])) {
                between.add(points[i]);
            }
        }
        int px = e[0];
        int py = e[1];
        if (between.size > 0) {
            // Canonical edges run toward larger x, or larger y when upright: the key order is the same.
            long[] sorted = Arrays.copyOf(between.data, between.size);
            Arrays.sort(sorted);
            for (int i = 0; i < sorted.length; i++) {
                int x = keyX(sorted[i]);
                int y = keyY(sorted[i]);
                if (x != px || y != py) {
                    out.add(canonical(px, py, x, y));
                    px = x;
                    py = y;
                }
            }
        }
        out.add(canonical(px, py, e[2], e[3]));
    }

    private static int[] canonical(int x0, int y0, int x1, int y1) {
        boolean swap = x0 > x1 || (x0 == x1 && y0 > y1);
        return swap ? new int[]{x1, y1, x0, y0} : new int[]{x0, y0, x1, y1};
    }

    private static int compareEdges(int[] a, int[] b) {
        for (int i = 0; i < 4; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    /** Orders by x, then by y, as signed values. */
    private static long pointKey(int x, int y) {
        return ((long) x << 32) | (y + 0x80000000L);
    }

    private static int keyX(long key) {
        return (int) (key >> 32);
    }

    private static int keyY(long key) {
        return (int) ((key & 0xFFFFFFFFL) - 0x80000000L);
    }

    /** Joins loose edges into closed rings, closing a ring as soon as the walk meets itself. */
    private static List<int[]> traceRings(List<int[]> edges) {
        Map<Long, IntBuf> at = new HashMap<>();
        for (int e = 0; e < edges.size(); e++) {
            int[] g = edges.get(e);
            at.computeIfAbsent(pointKey(g[0], g[1]), k -> new IntBuf()).add(e);
            at.computeIfAbsent(pointKey(g[2], g[3]), k -> new IntBuf()).add(e);
        }
        boolean[] used = new boolean[edges.size()];
        List<int[]> rings = new ArrayList<>();
        Map<Long, Integer> onPath = new HashMap<>();
        for (int first = 0; first < edges.size(); first++) {
            if (used[first]) {
                continue;
            }
            IntBuf path = new IntBuf();
            onPath.clear();
            int[] g = edges.get(first);
            int px = g[0];
            int py = g[1];
            int cx = g[2];
            int cy = g[3];
            used[first] = true;
            path.add(px);
            path.add(py);
            onPath.put(pointKey(px, py), 0);
            while (true) {
                Integer seen = onPath.get(pointKey(cx, cy));
                if (seen != null) {
                    // A closed loop: lift it off the path and carry on from the same point.
                    int start = seen;
                    rings.add(path.slice(start * 2, path.size));
                    for (int i = start + 1; i < path.size / 2; i++) {
                        onPath.remove(pointKey(path.data[i * 2], path.data[i * 2 + 1]));
                    }
                    path.size = (start + 1) * 2;
                } else {
                    onPath.put(pointKey(cx, cy), path.size / 2);
                    path.add(cx);
                    path.add(cy);
                }
                int next = nextEdge(edges, at.get(pointKey(cx, cy)), used, px, py, cx, cy);
                if (next < 0) {
                    break;
                }
                used[next] = true;
                int[] h = edges.get(next);
                boolean forward = h[0] == cx && h[1] == cy;
                px = cx;
                py = cy;
                cx = forward ? h[2] : h[0];
                cy = forward ? h[3] : h[1];
            }
        }
        return rings;
    }

    /** The unused edge at (cx, cy) that turns sharpest one way, which keeps traced rings from crossing. */
    private static int nextEdge(List<int[]> edges, IntBuf here, boolean[] used, int px, int py, int cx, int cy) {
        int best = -1;
        double bestTurn = Double.POSITIVE_INFINITY;
        double back = Math.atan2((double) py - cy, (double) px - cx);
        for (int i = 0; i < here.size; i++) {
            int e = here.data[i];
            if (used[e]) {
                continue;
            }
            int[] g = edges.get(e);
            boolean forward = g[0] == cx && g[1] == cy;
            int nx = forward ? g[2] : g[0];
            int ny = forward ? g[3] : g[1];
            double turn = Math.atan2((double) ny - cy, (double) nx - cx) - back;
            while (turn <= 0) {
                turn += 2 * Math.PI;
            }
            if (turn < bestTurn) {
                bestTurn = turn;
                best = e;
            }
        }
        return best;
    }

    /** Points used by more than one ring, sorted. These stay in a ring even where it runs straight. */
    private static long[] sharedPoints(List<int[]> rings) {
        int total = 0;
        for (int r = 0; r < rings.size(); r++) {
            total += rings.get(r).length / 2;
        }
        long[] all = new long[total];
        int w = 0;
        for (int r = 0; r < rings.size(); r++) {
            int[] ring = rings.get(r);
            for (int i = 0; i + 1 < ring.length; i += 2) {
                all[w++] = pointKey(ring[i], ring[i + 1]);
            }
        }
        Arrays.sort(all);
        int kept = 0;
        for (int i = 1; i < total; i++) {
            if (all[i] == all[i - 1] && (kept == 0 || all[kept - 1] != all[i])) {
                all[kept++] = all[i];
            }
        }
        return Arrays.copyOf(all, kept);
    }

    /**
     * Removes duplicates, spikes and straight points, except straight points another ring touches;
     * null when too little is left to matter.
     */
    private static int[] cleanRing(int[] xy, int points, long[] shared) {
        int count = points;
        boolean changed = true;
        while (changed && count >= 3) {
            changed = false;
            int w = 0;
            for (int i = 0; i < count; i++) {
                int ax = w > 0 ? xy[(w - 1) * 2] : xy[(count - 1) * 2];
                int ay = w > 0 ? xy[(w - 1) * 2 + 1] : xy[(count - 1) * 2 + 1];
                int bx = xy[i * 2];
                int by = xy[i * 2 + 1];
                int cx = i + 1 < count ? xy[(i + 1) * 2] : xy[0];
                int cy = i + 1 < count ? xy[(i + 1) * 2 + 1] : xy[1];
                if (orient(ax, ay, bx, by, cx, cy) == 0) {
                    long dot = ((long) ax - bx) * ((long) cx - bx) + ((long) ay - by) * ((long) cy - by);
                    if (dot >= 0 || Arrays.binarySearch(shared, pointKey(bx, by)) < 0) {
                        changed = true;
                        continue;
                    }
                }
                xy[w * 2] = bx;
                xy[w * 2 + 1] = by;
                w++;
            }
            count = w;
        }
        if (count < 3 || Math.abs(area2(xy, count)) < MIN_AREA2) {
            return null;
        }
        return Arrays.copyOf(xy, count * 2);
    }

    private static long area2(int[] xy, int count) {
        long sum = 0;
        int jx = xy[(count - 1) * 2];
        int jy = xy[(count - 1) * 2 + 1];
        for (int i = 0; i < count; i++) {
            int ix = xy[i * 2];
            int iy = xy[i * 2 + 1];
            sum += (long) jx * iy - (long) ix * jy;
            jx = ix;
            jy = iy;
        }
        return sum;
    }

    /** Sets each ring's direction from its nesting depth, then lists outers each followed by holes. */
    private static List<float[]> order(List<int[]> rings) {
        int n = rings.size();
        long[] areas = new long[n];
        int[][] box = new int[n][];
        int[] depth = new int[n];
        for (int i = 0; i < n; i++) {
            int[] r = rings.get(i);
            areas[i] = Math.abs(area2(r, r.length / 2));
            box[i] = bounds(r);
        }
        boolean[][] inside = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && areas[j] > areas[i] && encloses(box[j], box[i])
                        && ringInside(rings.get(i), rings.get(j))) {
                    inside[i][j] = true;
                    depth[i]++;
                }
            }
        }
        List<float[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if ((depth[i] & 1) != 0) {
                continue;
            }
            out.add(toFloats(rings.get(i), true));
            for (int h = 0; h < n; h++) {
                if (depth[h] == depth[i] + 1 && inside[h][i]) {
                    out.add(toFloats(rings.get(h), false));
                }
            }
        }
        return out;
    }

    private static int[] bounds(int[] r) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i + 1 < r.length; i += 2) {
            b[0] = Math.min(b[0], r[i]);
            b[1] = Math.min(b[1], r[i + 1]);
            b[2] = Math.max(b[2], r[i]);
            b[3] = Math.max(b[3], r[i + 1]);
        }
        return b;
    }

    private static boolean encloses(int[] a, int[] b) {
        return a[0] <= b[0] && a[1] <= b[1] && a[2] >= b[2] && a[3] >= b[3];
    }

    /** Rings never cross after {@link Area}, so any point of the inner ring off the outer settles it. */
    private static boolean ringInside(int[] inner, int[] outer) {
        int n = inner.length / 2;
        for (int i = 0; i < n; i++) {
            int x = inner[i * 2] * 2;
            int y = inner[i * 2 + 1] * 2;
            if (!onRingDoubled(outer, x, y)) {
                return inRingDoubled(outer, x, y);
            }
        }
        for (int i = 0; i < n; i++) {
            int k = (i + 1) % n;
            int x = inner[i * 2] + inner[k * 2];
            int y = inner[i * 2 + 1] + inner[k * 2 + 1];
            if (!onRingDoubled(outer, x, y)) {
                return inRingDoubled(outer, x, y);
            }
        }
        return false;
    }

    private static boolean onRingDoubled(int[] r, int x, int y) {
        int n = r.length / 2;
        int ax = r[(n - 1) * 2] * 2;
        int ay = r[(n - 1) * 2 + 1] * 2;
        for (int i = 0; i < n; i++) {
            int bx = r[i * 2] * 2;
            int by = r[i * 2 + 1] * 2;
            if (orient(ax, ay, bx, by, x, y) == 0 && x >= Math.min(ax, bx) && x <= Math.max(ax, bx)
                    && y >= Math.min(ay, by) && y <= Math.max(ay, by)) {
                return true;
            }
            ax = bx;
            ay = by;
        }
        return false;
    }

    private static boolean inRingDoubled(int[] r, int x, int y) {
        boolean in = false;
        int n = r.length / 2;
        int ax = r[(n - 1) * 2] * 2;
        int ay = r[(n - 1) * 2 + 1] * 2;
        for (int i = 0; i < n; i++) {
            int bx = r[i * 2] * 2;
            int by = r[i * 2 + 1] * 2;
            if ((ay > y) != (by > y)) {
                long side = orient(ax, ay, bx, by, x, y);
                if ((by > ay) == (side > 0)) {
                    in = !in;
                }
            }
            ax = bx;
            ay = by;
        }
        return in;
    }

    private static float[] toFloats(int[] ring, boolean positive) {
        int n = ring.length / 2;
        boolean flip = (area2(ring, n) > 0) != positive;
        float[] f = new float[ring.length];
        for (int i = 0; i < n; i++) {
            int k = flip ? n - 1 - i : i;
            f[i * 2] = ring[k * 2] / (float) SCALE;
            f[i * 2 + 1] = ring[k * 2 + 1] / (float) SCALE;
        }
        return f;
    }

    /**
     * Full pipeline: normalises any flattened contours and triangulates them.
     *
     * @return (x, y) triples, 6 floats per triangle, each with positive shoelace area
     */
    public static float[] triangulate(List<double[]> contours, int windingRule) {
        return triangulateClean(normalise(contours, windingRule));
    }

    /** Triangulates the rings from {@link #normalise}: each outer ring with the holes right after it. */
    public static float[] triangulateClean(List<float[]> clean) {
        List<float[]> rings = sharesPoint(clean) ? regions(clean) : clean;
        FloatBuf out = new FloatBuf(256);
        int i = 0;
        int n = rings.size();
        while (i < n) {
            int j = i + 1;
            while (j < n && signedArea(rings.get(j)) < 0) {
                j++;
            }
            new Clipper(rings, i, j, out).run();
            i = j;
        }
        return out.toArray();
    }

    private static boolean sharesPoint(List<float[]> rings) {
        int total = 0;
        for (int r = 0; r < rings.size(); r++) {
            total += rings.get(r).length / 2;
        }
        long[] all = new long[total];
        int w = 0;
        for (int r = 0; r < rings.size(); r++) {
            float[] ring = rings.get(r);
            for (int i = 0; i + 1 < ring.length; i += 2) {
                all[w++] = pointKey(Math.round(ring[i] * SCALE), Math.round(ring[i + 1] * SCALE));
            }
        }
        Arrays.sort(all);
        for (int i = 1; i < total; i++) {
            if (all[i] == all[i - 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rings that touch cannot be joined by bridges alone: two contacts between a hole and its outer
     * ring cut the filled part in two. So the same edges are walked again along the filled side, always
     * taking the next edge around the shared point, which gives one closed boundary per filled region.
     * A hole touching its region once becomes part of that region's boundary; anything left is a
     * separate hole that touches nothing of its region. Returned as outer loops each followed by its
     * holes. A loop may pass through a point twice but never crosses itself.
     */
    private static List<float[]> regions(List<float[]> rings) {
        int total = 0;
        for (int r = 0; r < rings.size(); r++) {
            total += rings.get(r).length / 2;
        }
        int[] fromX = new int[total];
        int[] fromY = new int[total];
        int[] toX = new int[total];
        int[] toY = new int[total];
        Map<Long, IntBuf> leaving = new HashMap<>();
        int e = 0;
        for (int r = 0; r < rings.size(); r++) {
            float[] ring = rings.get(r);
            int n = ring.length / 2;
            for (int i = 0; i < n; i++) {
                int k = (i + 1) % n;
                fromX[e] = Math.round(ring[i * 2] * SCALE);
                fromY[e] = Math.round(ring[i * 2 + 1] * SCALE);
                toX[e] = Math.round(ring[k * 2] * SCALE);
                toY[e] = Math.round(ring[k * 2 + 1] * SCALE);
                leaving.computeIfAbsent(pointKey(fromX[e], fromY[e]), key -> new IntBuf()).add(e);
                e++;
            }
        }
        boolean[] used = new boolean[total];
        List<int[]> outers = new ArrayList<>();
        List<int[]> holes = new ArrayList<>();
        for (int first = 0; first < total; first++) {
            if (used[first]) {
                continue;
            }
            IntBuf loop = new IntBuf();
            int at = first;
            while (at >= 0 && !used[at]) {
                used[at] = true;
                loop.add(fromX[at]);
                loop.add(fromY[at]);
                at = nextAlongFill(at, fromX, fromY, toX, toY, leaving);
            }
            int[] xy = loop.slice(0, loop.size);
            long area = area2(xy, xy.length / 2);
            if (area > 0) {
                outers.add(xy);
            } else if (area < 0) {
                holes.add(xy);
            }
        }
        int[] owner = new int[holes.size()];
        for (int h = 0; h < holes.size(); h++) {
            owner[h] = -1;
            long best = Long.MAX_VALUE;
            for (int o = 0; o < outers.size(); o++) {
                long area = area2(outers.get(o), outers.get(o).length / 2);
                if (area < best && ringInside(holes.get(h), outers.get(o))) {
                    best = area;
                    owner[h] = o;
                }
            }
        }
        List<float[]> out = new ArrayList<>(outers.size() + holes.size());
        for (int o = 0; o < outers.size(); o++) {
            out.add(toFloats(outers.get(o), true));
            for (int h = 0; h < holes.size(); h++) {
                if (owner[h] == o) {
                    out.add(toFloats(holes.get(h), false));
                }
            }
        }
        return out;
    }

    /**
     * The edge that continues the boundary of the filled region lying on edge e's inner side: from the
     * way back, turn through that region to the first edge leaving the point.
     */
    private static int nextAlongFill(int e, int[] fromX, int[] fromY, int[] toX, int[] toY, Map<Long, IntBuf> leaving) {
        IntBuf here = leaving.get(pointKey(toX[e], toY[e]));
        if (here == null) {
            return -1;
        }
        double back = Math.atan2((double) fromY[e] - toY[e], (double) fromX[e] - toX[e]);
        int best = -1;
        double bestTurn = Double.POSITIVE_INFINITY;
        for (int i = 0; i < here.size; i++) {
            int g = here.data[i];
            double turn = back - Math.atan2((double) toY[g] - fromY[g], (double) toX[g] - fromX[g]);
            while (turn <= 0) {
                turn += 2 * Math.PI;
            }
            if (turn < bestTurn) {
                bestTurn = turn;
                best = g;
            }
        }
        return best;
    }

    /** Shoelace area of an (x, y) ring: positive for outer rings, negative for holes. */
    public static double signedArea(float[] ring) {
        double sum = 0;
        int n = ring.length / 2;
        double jx = ring[(n - 1) * 2];
        double jy = ring[(n - 1) * 2 + 1];
        for (int i = 0; i < n; i++) {
            double ix = ring[i * 2];
            double iy = ring[i * 2 + 1];
            sum += jx * iy - ix * jy;
            jx = ix;
            jy = iy;
        }
        return sum * 0.5;
    }

    /** Doubled shoelace area of a ring, exact, in grid cells. */
    static long gridArea2(float[] ring) {
        int n = ring.length / 2;
        int[] xy = new int[n * 2];
        for (int i = 0; i < n * 2; i++) {
            xy[i] = Math.round(ring[i] * SCALE);
        }
        return area2(xy, n);
    }

    /** Doubled signed area of a triangle, exact, in grid cells. */
    static long gridOrient(float x0, float y0, float x1, float y1, float x2, float y2) {
        return orient(Math.round(x0 * SCALE), Math.round(y0 * SCALE), Math.round(x1 * SCALE), Math.round(y1 * SCALE),
                Math.round(x2 * SCALE), Math.round(y2 * SCALE));
    }

    /** Doubled signed triangle area, exact: positive when a, b, c turn the way outer rings do. */
    private static long orient(long ax, long ay, long bx, long by, long cx, long cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /** One outer ring with its holes, joined into a single linked list and then clipped. */
    private static final class Clipper {
        private static final byte UNKNOWN = 0;
        private static final byte EAR = 1;
        private static final byte NOT_EAR = 2;
        private static final byte GONE = 3;

        private final int[] x;
        private final int[] y;
        private final int[] prev;
        private final int[] next;
        private final byte[] state;
        private final FloatBuf out;
        private int used;
        private int head;
        private int alive;

        Clipper(List<float[]> rings, int from, int to, FloatBuf out) {
            this.out = out;
            int total = 0;
            for (int i = from; i < to; i++) {
                total += rings.get(i).length / 2 + 2;
            }
            x = new int[total];
            y = new int[total];
            prev = new int[total];
            next = new int[total];
            state = new byte[total];
            head = addCycle(rings.get(from));
            int holes = to - from - 1;
            if (holes > 0) {
                int[] rightmost = new int[holes];
                for (int h = 0; h < holes; h++) {
                    int first = addCycle(rings.get(from + 1 + h));
                    int best = first;
                    for (int n = next[first]; n != first; n = next[n]) {
                        if (x[n] > x[best]) {
                            best = n;
                        }
                    }
                    rightmost[h] = best;
                }
                for (int a = 1; a < holes; a++) {
                    int v = rightmost[a];
                    int b = a - 1;
                    while (b >= 0 && x[rightmost[b]] < x[v]) {
                        rightmost[b + 1] = rightmost[b];
                        b--;
                    }
                    rightmost[b + 1] = v;
                }
                for (int h = 0; h < holes; h++) {
                    bridge(rightmost[h]);
                }
            }
            alive = ringSize();
        }

        private int ringSize() {
            int count = 0;
            int n = head;
            do {
                count++;
                n = next[n];
            } while (n != head);
            return count;
        }

        private int addCycle(float[] ring) {
            int first = used;
            int n = ring.length / 2;
            for (int i = 0; i < n; i++) {
                int id = used++;
                x[id] = Math.round(ring[i * 2] * SCALE);
                y[id] = Math.round(ring[i * 2 + 1] * SCALE);
                prev[id] = i == 0 ? first + n - 1 : id - 1;
                next[id] = i == n - 1 ? first : id + 1;
            }
            return first;
        }

        private boolean same(int a, int b) {
            return x[a] == x[b] && y[a] == y[b];
        }

        private long orient3(int a, int b, int c) {
            return orient(x[a], y[a], x[b], y[b], x[c], y[c]);
        }

        private boolean properCross(int a, int b, int c, int d) {
            long o1 = orient3(a, b, c);
            long o2 = orient3(a, b, d);
            if (o1 == 0 || o2 == 0 || (o1 > 0) == (o2 > 0)) {
                return false;
            }
            long o3 = orient3(c, d, a);
            long o4 = orient3(c, d, b);
            return o3 != 0 && o4 != 0 && (o3 > 0) != (o4 > 0);
        }

        /** Whether the direction from b to target leaves b into the filled side. */
        private boolean inCone(int b, int target) {
            int a = prev[b];
            int c = next[b];
            long turn = orient3(a, b, c);
            long o1 = orient3(a, b, target);
            long o2 = orient3(b, c, target);
            if (turn > 0) {
                return o1 > 0 && o2 > 0;
            }
            if (turn < 0) {
                return o1 > 0 || o2 > 0;
            }
            return o1 > 0;
        }

        private boolean visible(int m, int v) {
            if (same(m, v) || !inCone(v, m) || !inCone(m, v)) {
                return false;
            }
            int minX = Math.min(x[m], x[v]);
            int maxX = Math.max(x[m], x[v]);
            int minY = Math.min(y[m], y[v]);
            int maxY = Math.max(y[m], y[v]);
            for (int i = 0; i < used; i++) {
                if (!same(i, m) && !same(i, v) && orient3(m, v, i) == 0
                        && x[i] >= minX && x[i] <= maxX && y[i] >= minY && y[i] <= maxY) {
                    return false;
                }
                if (properCross(m, v, i, next[i])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Joins the hole holding m, its rightmost point, to the main ring by a bridge that crosses
         * nothing. A hole can pass through that point twice, and only one of the two faces the ring,
         * so all points at that x are tried, then the rest of the hole.
         */
        private void bridge(int m) {
            int n = m;
            do {
                if (x[n] == x[m] && bridgeFrom(n, false)) {
                    return;
                }
                n = next[n];
            } while (n != m);
            n = m;
            do {
                if (x[n] != x[m] && bridgeFrom(n, false)) {
                    return;
                }
                n = next[n];
            } while (n != m);
            RELAXED_BRIDGES.incrementAndGet();
            bridgeFrom(m, true);
        }

        /** Links m to the nearest ring point it can see, or with force to the nearest it may at all. */
        private boolean bridgeFrom(int m, boolean force) {
            int count = ringSize();
            int[] nodes = new int[count];
            long[] dist = new long[count];
            int n = head;
            for (int i = 0; i < count; i++) {
                long dx = (long) x[n] - x[m];
                long dy = (long) y[n] - y[m];
                nodes[i] = n;
                dist[i] = dx * dx + dy * dy;
                n = next[n];
            }
            int nearest = -1;
            int nearestCone = -1;
            for (int tries = 0; tries < count; tries++) {
                int pick = -1;
                for (int i = 0; i < count; i++) {
                    if (dist[i] >= 0 && (pick < 0 || dist[i] < dist[pick])) {
                        pick = i;
                    }
                }
                int v = nodes[pick];
                dist[pick] = -1;
                if (nearest < 0) {
                    nearest = v;
                }
                if (nearestCone < 0 && inCone(v, m)) {
                    nearestCone = v;
                }
                if (visible(m, v)) {
                    link(m, v);
                    return true;
                }
            }
            if (force) {
                link(m, nearestCone >= 0 ? nearestCone : nearest);
            }
            return force;
        }

        private void link(int m, int v) {
            int m2 = used++;
            int v2 = used++;
            x[m2] = x[m];
            y[m2] = y[m];
            x[v2] = x[v];
            y[v2] = y[v];
            int vn = next[v];
            int mp = prev[m];
            next[v] = m;
            prev[m] = v;
            next[mp] = m2;
            prev[m2] = mp;
            next[m2] = v2;
            prev[v2] = m2;
            next[v2] = vn;
            prev[vn] = v2;
        }

        private boolean isEar(int b) {
            int a = prev[b];
            int c = next[b];
            if (orient3(a, b, c) <= 0) {
                return false;
            }
            int minX = Math.min(x[a], Math.min(x[b], x[c]));
            int maxX = Math.max(x[a], Math.max(x[b], x[c]));
            int minY = Math.min(y[a], Math.min(y[b], y[c]));
            int maxY = Math.max(y[a], Math.max(y[b], y[c]));
            for (int p = next[c]; p != a; p = next[p]) {
                if (x[p] < minX || x[p] > maxX || y[p] < minY || y[p] > maxY || same(p, a) || same(p, c)) {
                    continue;
                }
                if (same(p, b)) {
                    // Another chain touching the tip may still cut across the new diagonal.
                    if (properCross(a, c, p, next[p]) || properCross(a, c, p, prev[p])) {
                        return false;
                    }
                    continue;
                }
                if (orient3(a, b, p) >= 0 && orient3(b, c, p) >= 0 && orient3(c, a, p) >= 0) {
                    return false;
                }
            }
            return true;
        }

        private boolean noVertexStrictlyInside(int b) {
            int a = prev[b];
            int c = next[b];
            for (int p = next[c]; p != a; p = next[p]) {
                if (orient3(a, b, p) > 0 && orient3(b, c, p) > 0 && orient3(c, a, p) > 0) {
                    return false;
                }
            }
            return true;
        }

        private void remove(int n) {
            int a = prev[n];
            int c = next[n];
            next[a] = c;
            prev[c] = a;
            state[n] = GONE;
            state[a] = UNKNOWN;
            state[c] = UNKNOWN;
            if (head == n) {
                head = c;
            }
            alive--;
        }

        /** Drops a repeated point or a zero-width fold at n, walking back while more appear. */
        private void prune(int start) {
            int n = start;
            while (alive >= 3 && state[n] != GONE) {
                int a = prev[n];
                int c = next[n];
                boolean drop = same(n, a) || same(n, c);
                if (!drop && orient3(a, n, c) == 0) {
                    long dot = ((long) x[a] - x[n]) * ((long) x[c] - x[n])
                            + ((long) y[a] - y[n]) * ((long) y[c] - y[n]);
                    drop = dot > 0;
                }
                if (!drop) {
                    return;
                }
                remove(n);
                n = a;
            }
        }

        private void emit(int a, int b, int c) {
            out.add(x[a] / (float) SCALE);
            out.add(y[a] / (float) SCALE);
            out.add(x[b] / (float) SCALE);
            out.add(y[b] / (float) SCALE);
            out.add(x[c] / (float) SCALE);
            out.add(y[c] / (float) SCALE);
        }

        private void clip(int b) {
            int a = prev[b];
            int c = next[b];
            emit(a, b, c);
            remove(b);
            prune(a);
            prune(c);
        }

        void run() {
            boolean stale = false;
            while (alive >= 3) {
                int best = -1;
                long bestLen = Long.MAX_VALUE;
                int n = head;
                do {
                    if (state[n] == UNKNOWN) {
                        state[n] = isEar(n) ? EAR : NOT_EAR;
                    }
                    if (state[n] == EAR) {
                        long dx = (long) x[prev[n]] - x[next[n]];
                        long dy = (long) y[prev[n]] - y[next[n]];
                        long len = dx * dx + dy * dy;
                        if (len < bestLen) {
                            bestLen = len;
                            best = n;
                        }
                    }
                    n = next[n];
                } while (n != head);
                if (best >= 0) {
                    clip(best);
                    stale = true;
                } else if (stale) {
                    // Clipping can only unblock others, so look again before giving ground.
                    n = head;
                    do {
                        state[n] = UNKNOWN;
                        n = next[n];
                    } while (n != head);
                    stale = false;
                } else if (relaxedStep()) {
                    stale = true;
                } else {
                    return;
                }
            }
        }

        /** Nothing is a clean ear: drops a straight point, or else takes the least bad convex corner. */
        private boolean relaxedStep() {
            int n = head;
            do {
                if (orient3(prev[n], n, next[n]) == 0) {
                    remove(n);
                    return true;
                }
                n = next[n];
            } while (n != head);
            RELAXED_EARS.incrementAndGet();
            int convex = -1;
            n = head;
            do {
                if (orient3(prev[n], n, next[n]) > 0) {
                    if (noVertexStrictlyInside(n)) {
                        clip(n);
                        return true;
                    }
                    if (convex < 0) {
                        convex = n;
                    }
                }
                n = next[n];
            } while (n != head);
            if (convex < 0) {
                return false;
            }
            clip(convex);
            return true;
        }
    }

    private static final class LongBuf {
        long[] data = new long[8];
        int size;

        void add(long v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }
    }

    private static final class IntBuf {
        int[] data = new int[8];
        int size;

        void add(int v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }

        int[] slice(int from, int to) {
            return Arrays.copyOfRange(data, from, to);
        }

    }

    private static final class DoubleBuf {
        double[] data;
        int size;

        DoubleBuf(int capacity) {
            data = new double[capacity];
        }

        void add(double v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }

        double[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static final class FloatBuf {
        float[] data;
        int size;

        FloatBuf(int capacity) {
            data = new float[capacity];
        }

        void add(float v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
