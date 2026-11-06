package net.lyrica.v26_3.stage;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the addon's TrueType fonts and hands out cached {@link GlyphMesh} geometry. Thread safe.
 * Nothing here throws on a bad font: calls return null and the problem is logged once. That goes for any Error too,
 * as a system without usable fonts or fontconfig can throw InternalError, UnsatisfiedLinkError or a plain Error.
 *
 * <p>A glyph takes under a millisecond to a few milliseconds to build, and the first use of a font
 * takes over 100 ms on a cold JVM, so build a song's characters with {@link #prepare} before drawing
 * them. Cached lookups are about 20 ns.
 */
public final class GlyphOutlines {
    /** Best quality, for letters near the camera. */
    public static final int FINE = 0;
    /** About 3 times fewer outline points, for letters far away. */
    public static final int COARSE = 1;

    /** Receives each font problem once. */
    public interface Log {
        void warn(String message, Throwable cause);
    }

    /** Curves are first flattened this finely, in glyph pixels, one em being 10 of them. */
    private static final double BASE_FLATNESS = 0.001;
    /** Outline tolerance per detail level, in glyph pixels. */
    static final double FINE_FLATNESS = 0.005;
    static final double COARSE_FLATNESS = 0.045;
    /** Corner angle up to which a wall normal is smooth, per detail level. */
    static final float FINE_SMOOTH_DEGREES = 38f;
    static final float COARSE_SMOOTH_DEGREES = 65f;
    private static final int MAX_FINER_ATTEMPTS = 3;
    private static final double OVERLAP_SLACK = 0.002;
    private static final double MAX_OVERLAP_DRIFT = 0.003;
    /** Thinning may lose a ring smaller than this many squared tolerances, but never a larger one. */
    private static final double SMALL_RING = 4.0;
    /**
     * The JDK snaps outlines to 1/64 of a font pixel, so glyphs are read at a large size and scaled
     * down to 10 pixels per em, which leaves the snapping at 1/6400 of a glyph pixel.
     */
    private static final float READ_SIZE = 1000f;
    private static final double READ_TO_GLYPH_PX = 0.01;
    private static final float STROKE_MITER_LIMIT = 3f;
    /** Stroke widths are rounded to 1/20 of a glyph pixel, so a pulsing width reuses its meshes. */
    private static final int WIDTH_STEPS_PER_PX = 20;
    private static final int MAX_WIDTH_STEP = 80;
    /** A face drops its outlined meshes past this many, which caps them at about 40 MB a face. */
    private static final int MAX_OUTLINED_MESHES = 4096;
    private static final String FONT_PATH = "/assets/lyrica/font/";
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final AffineTransform READ_TO_GLYPH = AffineTransform.getScaleInstance(READ_TO_GLYPH_PX, READ_TO_GLYPH_PX);
    private static final GlyphMesh MISSING = GlyphMesh.blank(-1f);
    private static final GlyphMesh NOTHING = GlyphMesh.blank(0f);
    private static final Face NO_FACE = new Face(null, "", false);
    /** Tried in this order. "Dialog" stands for whatever Java itself falls back on, on any system. */
    private static final String[] FALLBACK_FAMILIES = {"Segoe UI", "Tahoma", "Microsoft YaHei", "Yu Gothic",
        "Malgun Gothic", "Nirmala UI", "Leelawadee UI", "Ebrima", "Gadugi", "Segoe UI Symbol", "Arial", "Dialog"};

    private static final Map<String, Face> REGULAR = new ConcurrentHashMap<>();
    private static final Map<String, Face> BOLD = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static volatile Log log;
    private static volatile Font[] plainFallbacks;
    private static volatile Font[] boldFallbacks;

    private GlyphOutlines() {
    }

    /** Sets where font problems go. Without one they are dropped. */
    public static void setLog(Log value) {
        log = value;
    }

    /**
     * The mesh for one code point.
     *
     * @param font      file name without extension, as in /assets/lyrica/font/&lt;font&gt;.ttf
     * @param bold      uses &lt;font&gt;_bold.ttf when that file exists, otherwise the regular file
     * @param codePoint the character
     * @param detail    {@link #FINE} or {@link #COARSE}
     * @return the mesh; an empty mesh with an advance for anything that draws nothing (a space, a
     *         control or format character such as U+200B, an empty private-use glyph); or null when
     *         the font has no real glyph for it and the caller should fall back
     */
    public static GlyphMesh get(String font, boolean bold, int codePoint, int detail) {
        return lookup(font, bold, codePoint, detail, 0);
    }

    /**
     * The glyph grown by a stroke, for drawing an outline behind the plain glyph: the shape united
     * with {@link BasicStroke#createStrokedShape}, with miter joins so corners stay crisp.
     *
     * @param widthPx the stroke's width in glyph pixels; edges move outward by half of it. It goes
     *                through {@link #snapWidth} first.
     * @return as {@link #get}; the plain glyph when the snapped width is 0
     */
    public static GlyphMesh outlined(String font, boolean bold, int codePoint, int detail, float widthPx) {
        return lookup(font, bold, codePoint, detail, widthStep(widthPx));
    }

    /**
     * The stroke width {@link #outlined} really uses: rounded to the nearest 0.05 glyph pixel and at
     * most 4. Anything under 0.05, and NaN, gives 0.
     */
    public static float snapWidth(float widthPx) {
        return widthStep(widthPx) / (float) WIDTH_STEPS_PER_PX;
    }

    private static int widthStep(float width) {
        if (!(width >= 1f / WIDTH_STEPS_PER_PX)) {
            return 0;
        }
        return width >= (float) MAX_WIDTH_STEP / WIDTH_STEPS_PER_PX ? MAX_WIDTH_STEP : Math.round(width * WIDTH_STEPS_PER_PX);
    }

    /**
     * Builds and caches every character of a text in both detail levels, so drawing it never stalls.
     * Call it from any thread when the lyrics load, or once per frame with a small budget until it
     * returns true. Each call builds at least one missing glyph and picks up where the last one
     * stopped, as finished glyphs are simply cached.
     *
     * @param widthPx     also builds the outlined meshes for this stroke width, when above 0
     * @param budgetNanos stops building once this much time has passed; one glyph can overrun it
     * @return true when the whole text is ready
     */
    public static boolean prepare(String font, boolean bold, CharSequence text, float widthPx, long budgetNanos) {
        if (font == null || text == null) {
            return true;
        }
        long start = System.nanoTime();
        int step = widthStep(widthPx);
        try {
            Face face = face(font, bold);
            if (face == NO_FACE) {
                return true;
            }
            int length = text.length();
            for (int i = 0; i < length; ) {
                int cp = Character.codePointAt(text, i);
                i += Character.charCount(cp);
                for (int level = FINE; level <= COARSE; level++) {
                    if (buildIfAbsent(face, cp, level, 0) && System.nanoTime() - start >= budgetNanos) {
                        return false;
                    }
                    if (step > 0 && buildIfAbsent(face, cp, level, step) && System.nanoTime() - start >= budgetNanos) {
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException | Error e) {
            warnOnce("lookup:" + font, "Could not read glyphs of font " + font, e);
            return true;
        }
    }

    /** Whether this call had to build the mesh. */
    private static boolean buildIfAbsent(Face face, int codePoint, int level, int step) {
        if (face.meshes(step).containsKey(key(codePoint, level, step))) {
            return false;
        }
        meshOf(face, codePoint, level, step);
        return true;
    }

    /** Forgets every font and mesh. */
    public static void clear() {
        synchronized (GlyphOutlines.class) {
            REGULAR.clear();
            BOLD.clear();
        }
    }

    private static GlyphMesh lookup(String font, boolean bold, int codePoint, int detail, int step) {
        if (font == null) {
            return null;
        }
        try {
            Face face = face(font, bold);
            if (face == NO_FACE) {
                return null;
            }
            GlyphMesh mesh = meshOf(face, codePoint, detail == COARSE ? COARSE : FINE, step);
            return mesh == MISSING ? null : mesh;
        } catch (RuntimeException | Error e) {
            warnOnce("lookup:" + font, "Could not read glyphs of font " + font, e);
            return null;
        }
    }

    private static long key(int codePoint, int level, int step) {
        return ((long) step << 34) | (((long) codePoint & 0xFFFFFFFFL) << 1) | level;
    }

    private static GlyphMesh meshOf(Face face, int codePoint, int level, int step) {
        long key = key(codePoint, level, step);
        Map<Long, GlyphMesh> meshes = face.meshes(step);
        GlyphMesh mesh = meshes.get(key);
        if (mesh == null) {
            mesh = buildSafely(face, codePoint, level, step);
            if (step > 0 && meshes.size() >= MAX_OUTLINED_MESHES) {
                meshes.clear();
            }
            GlyphMesh raced = meshes.putIfAbsent(key, mesh);
            if (raced != null) {
                mesh = raced;
            }
        }
        return mesh;
    }

    private static Face face(String name, boolean bold) {
        Map<String, Face> map = bold ? BOLD : REGULAR;
        Face face = map.get(name);
        if (face != null) {
            return face;
        }
        synchronized (GlyphOutlines.class) {
            face = map.get(name);
            if (face != null) {
                return face;
            }
            if (bold) {
                face = load(name + "_bold", false, true);
                if (face == null) {
                    face = face(name, false);
                }
            } else {
                face = load(name, true, false);
                if (face == null) {
                    face = NO_FACE;
                }
            }
            map.put(name, face);
            return face;
        }
    }

    /** Null when the file is absent or broken; absence is logged only when the file is required. */
    private static Face load(String file, boolean required, boolean bold) {
        String path = FONT_PATH + file + ".ttf";
        try (InputStream in = GlyphOutlines.class.getResourceAsStream(path)) {
            if (in == null) {
                if (required) {
                    warnOnce("font:" + file, "Font not found: " + path, null);
                }
                return null;
            }
            return new Face(Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(READ_SIZE), file, bold);
        } catch (FontFormatException | IOException | RuntimeException | Error e) {
            warnOnce("font:" + file, "Could not load font " + path, e);
            return null;
        }
    }

    private static GlyphMesh buildSafely(Face face, int codePoint, int detail, int step) {
        try {
            GlyphMesh mesh = build(face, codePoint, detail, step);
            return mesh == null ? MISSING : mesh;
        } catch (RuntimeException | Error e) {
            warnOnce("glyph:" + face.file, "Could not build glyph " + codePoint + " of font " + face.file, e);
            return MISSING;
        }
    }

    private static GlyphMesh build(Face face, int codePoint, int detail, int step) {
        if (!Character.isValidCodePoint(codePoint)) {
            return null;
        }
        Font font = face.font;
        if (!font.canDisplay(codePoint) && !isControl(codePoint)) {
            font = fallback(codePoint, face.bold);
        }
        if (font == null || !font.canDisplay(codePoint)) {
            return isControl(codePoint) ? NOTHING : null;
        }
        GlyphVector glyphs = font.createGlyphVector(FRC, Character.toChars(codePoint));
        if (glyphs.getNumGlyphs() < 1 || glyphs.getGlyphCode(0) == font.getMissingGlyphCode()) {
            return isControl(codePoint) ? NOTHING : null;
        }
        float advance = (float) (glyphs.getGlyphMetrics(0).getAdvanceX() * READ_TO_GLYPH_PX);
        Shape read = glyphs.getOutline(0f, 0f);
        if (read.getPathIterator(null).isDone()) {
            return emptyOnPurpose(codePoint) ? GlyphMesh.blank(advance) : null;
        }
        Shape outline = READ_TO_GLYPH.createTransformedShape(read);
        double tolerance = tolerance(detail);
        List<double[]> base;
        List<float[]> exact = null;
        int rule;
        if (step > 0) {
            exact = strokedExact(outline, step / (float) WIDTH_STEPS_PER_PX);
            base = toContours(exact);
            rule = PathIterator.WIND_NON_ZERO;
        } else {
            base = GlyphTriangulator.flatten(outline, BASE_FLATNESS);
            rule = outline.getPathIterator(null).getWindingRule();
        }
        float smooth = detail == COARSE ? COARSE_SMOOTH_DEGREES : FINE_SMOOTH_DEGREES;
        for (int attempt = 0; attempt <= MAX_FINER_ATTEMPTS; attempt++) {
            List<float[]> rings = thinned(base, rule, exact, tolerance);
            if (rings.isEmpty()) {
                return GlyphMesh.blank(advance);
            }
            float[] triangles = GlyphTriangulator.triangulateClean(rings);
            if (areasAgree(rings, triangles)) {
                return GlyphMesh.build(rings, triangles, advance, smooth);
            }
            tolerance *= 0.5;
        }
        warnOnce("mesh:" + face.file + ":" + codePoint,
                "Triangles do not cover glyph " + codePoint + " of font " + face.file, null);
        return null;
    }

    /**
     * The first installed font that has this character, for the scripts the addon's own fonts leave out: they are Latin
     * only, while lyrics come in every script. Null when nothing on this PC has it.
     */
    private static Font fallback(int codePoint, boolean bold) {
        Font[] fonts = bold ? boldFallbacks : plainFallbacks;
        if (fonts == null) {
            fonts = new Font[FALLBACK_FAMILIES.length];
            for (int i = 0; i < fonts.length; i++) {
                fonts[i] = new Font(FALLBACK_FAMILIES[i], bold ? Font.BOLD : Font.PLAIN, 1).deriveFont(READ_SIZE);
            }
            if (bold) {
                boldFallbacks = fonts;
            } else {
                plainFallbacks = fonts;
            }
        }
        for (int i = 0; i < fonts.length; i++) {
            if (fonts[i].canDisplay(codePoint)) {
                return fonts[i];
            }
        }
        return null;
    }

    /** Control and format characters draw nothing in any font, so a fallback would only show a box. */
    private static boolean isControl(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    /**
     * Whether a font's empty glyph is meant to be empty. One for a letter or a symbol is a gap in the
     * font, and for those the caller's fallback is the better answer.
     */
    private static boolean emptyOnPurpose(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || isControl(codePoint)
                || Character.getType(codePoint) == Character.PRIVATE_USE
                || Character.getType(codePoint) == Character.NON_SPACING_MARK;
    }

    /** The clean rings of an outline at one detail level. */
    static List<float[]> rings(Shape outline, int detail) {
        return thinned(GlyphTriangulator.flatten(outline, BASE_FLATNESS), outline.getPathIterator(null).getWindingRule(),
                null, tolerance(detail));
    }

    /**
     * Thins flattened contours and normalises them. Thinning keeps each contour's own area, so only a
     * glyph built from overlapping contours can drift, and a hairline can still pinch apart or a
     * narrow gap close. Both are redone finer when they happen.
     *
     * @param exact the contours already normalised, or null to do that only when needed
     */
    private static List<float[]> thinned(List<double[]> base, int rule, List<float[]> exact, double start) {
        List<float[]> truth = exact;
        double tolerance = start;
        for (int attempt = 0; ; attempt++) {
            List<double[]> contours = GlyphTriangulator.simplify(base, tolerance);
            List<float[]> rings = GlyphTriangulator.normalise(contours, rule);
            double area = totalArea(rings);
            double summed = 0;
            for (int i = 0; i < contours.size(); i++) {
                summed += GlyphTriangulator.ringArea(contours.get(i));
            }
            summed = Math.abs(summed);
            if (attempt == MAX_FINER_ATTEMPTS
                    || (rings.size() == base.size() && Math.abs(summed - area) <= summed * OVERLAP_SLACK)) {
                return rings;
            }
            if (truth == null) {
                truth = GlyphTriangulator.normalise(base, rule);
            }
            double truthArea = totalArea(truth);
            if (sameShape(rings, truth, start) && Math.abs(area - truthArea) <= truthArea * MAX_OVERLAP_DRIFT) {
                return rings;
            }
            tolerance *= 0.5;
        }
    }

    /** Nothing added, and of the rings that matter at this tolerance nothing lost. */
    private static boolean sameShape(List<float[]> rings, List<float[]> truth, double tolerance) {
        if (rings.size() > truth.size()) {
            return false;
        }
        double small = SMALL_RING * tolerance * tolerance;
        return largeRings(rings, small, true) >= largeRings(truth, small, true)
                && largeRings(rings, small, false) >= largeRings(truth, small, false);
    }

    private static int largeRings(List<float[]> rings, double small, boolean outer) {
        int count = 0;
        for (int i = 0; i < rings.size(); i++) {
            double area = GlyphTriangulator.signedArea(rings.get(i));
            if ((area > 0) == outer && Math.abs(area) >= small) {
                count++;
            }
        }
        return count;
    }

    /**
     * The glyph united with its stroke, before any thinning. The stroker folds back on itself at
     * corners; only the union of the unthinned shapes closes those folds without leaving slits.
     */
    private static List<float[]> strokedExact(Shape outline, float width) {
        int rule = outline.getPathIterator(null).getWindingRule();
        BasicStroke stroke = new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, STROKE_MITER_LIMIT);
        Area area = new Area(GlyphTriangulator.toPath(GlyphTriangulator.flatten(outline, BASE_FLATNESS), rule));
        area.add(new Area(GlyphTriangulator.toPath(GlyphTriangulator.flatten(stroke.createStrokedShape(outline), BASE_FLATNESS),
                PathIterator.WIND_NON_ZERO)));
        return GlyphTriangulator.normalise(area);
    }

    private static List<double[]> toContours(List<float[]> rings) {
        List<double[]> contours = new ArrayList<>(rings.size());
        for (int r = 0; r < rings.size(); r++) {
            float[] ring = rings.get(r);
            double[] contour = new double[ring.length];
            for (int i = 0; i < ring.length; i++) {
                contour[i] = ring[i];
            }
            contours.add(contour);
        }
        return contours;
    }

    private static double tolerance(int detail) {
        return detail == COARSE ? COARSE_FLATNESS : FINE_FLATNESS;
    }

    private static double totalArea(List<float[]> rings) {
        double sum = 0;
        for (int i = 0; i < rings.size(); i++) {
            sum += GlyphTriangulator.signedArea(rings.get(i));
        }
        return sum;
    }

    /** A last check before a glyph is shown. Vertices sit on a grid, so the areas compare exactly. */
    private static boolean areasAgree(List<float[]> rings, float[] t) {
        long ringArea = 0;
        for (int r = 0; r < rings.size(); r++) {
            ringArea += GlyphTriangulator.gridArea2(rings.get(r));
        }
        long triArea = 0;
        for (int i = 0; i + 5 < t.length; i += 6) {
            triArea += GlyphTriangulator.gridOrient(t[i], t[i + 1], t[i + 2], t[i + 3], t[i + 4], t[i + 5]);
        }
        return ringArea > 0 && triArea == ringArea;
    }

    private static void warnOnce(String key, String message, Throwable cause) {
        if (!LOGGED.add(key)) {
            return;
        }
        Log sink = log;
        if (sink != null) {
            try {
                sink.warn(message, cause);
            } catch (RuntimeException | Error ignored) {
                // A broken log must not break rendering.
            }
        }
    }

    private static final class Face {
        final Font font;
        final String file;
        /** Whether this is a font's bold file, so that a character it lacks comes from a bold fallback too. */
        final boolean bold;
        final Map<Long, GlyphMesh> plain = new ConcurrentHashMap<>();
        final Map<Long, GlyphMesh> outlined = new ConcurrentHashMap<>();

        Face(Font font, String file, boolean bold) {
            this.font = font;
            this.file = file;
            this.bold = bold;
        }

        Map<Long, GlyphMesh> meshes(int step) {
            return step > 0 ? outlined : plain;
        }
    }
}
