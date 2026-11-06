package net.lyrica.v26_2.stage;

import net.lyrica.v26_2.words.SungParts;
import net.lyrica.v26_2.words.TimedLine;
import org.joml.Quaternionf;

/** One line standing in the world: where it is, which way it faces, and the smaller rows that travel with it. */
final class LineSlot {

  static final int BACKGROUND = 0;
  static final int NEXT = 1;
  static final int FIRST_EARLIER = 2;
  static final int EXTRA_ROWS = 5;

  final LineComposer composer = new LineComposer(true);
  final LetterPlates plates = new LetterPlates();
  final Extra[] extras = new Extra[EXTRA_ROWS];
  final Quaternionf orient = new Quaternionf();
  final float[] groundColumns = new float[SoftLight.SHADOW_COLUMNS];

  TimedLine line;
  int lineIndex;
  long seed;
  SungParts parts;
  ComposedLine composed;

  boolean placed;
  double anchorX;
  double anchorY;
  double anchorZ;
  /** Radians around Y; the face looks along (sin yaw, cos yaw) on the ground. */
  float yaw;
  /** Upright and allowed to turn or flip, against lying on or stuck to a surface, whose orientation is fixed. */
  boolean standing;
  /** The anchor is the foot of the block of rows, on the ground; otherwise it is the middle of the main line. */
  boolean onFoot;
  boolean bobbing;
  boolean thin;
  boolean farAway;
  /** Blocks per glyph pixel. */
  float scale;
  /** Extra length along the letters' height: what lies on the ground would otherwise be seen nearly edge-on. */
  float stretch;
  /** The radius of the band this line lies on, around where the player stood. 0 when the line curves by the look. */
  float orbitRadius;

  boolean hasGround;
  double groundY;
  /** Nothing of the line goes below this height, the highest ground found under all of it. */
  boolean hasFloor;
  double floorY;
  boolean columnsValid;

  boolean turning;
  float bendEase;

  int light;
  int lightAge;

  boolean evicted;
  long evictedAtMs;
  float age;


  float widthPx;
  float belowPx;
  float abovePx;
  float belowEase;
  /** The lowest point of all the rows at rest, in the main row's space: where the anchor sits on a line on foot. */
  float bottomPx;
  float bottomEase;
  /** The face the line's letters were last found to be ready in. */
  VectorFace readyFace;
  /** What is left of a move to new ground, so a line in plain sight eases there. */
  double settleY;
  /** Nothing was found under the line because its chunk had not arrived, so it looks again once that has. */
  boolean waitingForChunk;

  void begin(TimedLine timed, int index) {
    this.line = timed;
    this.lineIndex = index;
    this.seed = timed.startMs() * 31L + timed.text().hashCode();
    this.parts = timed.parts();
    this.composer.voice(timed.voice());
    this.composed = null;
    this.placed = false;
    this.turning = false;
    this.bendEase = 1.0F;
    this.lightAge = 0;
    this.evicted = false;
    this.age = 0.0F;
    this.belowEase = 0.0F;
    this.bottomEase = 0.0F;
    this.readyFace = null;
    this.hasFloor = false;
  }

  void release() {
    this.line = null;
    this.composed = null;
    this.parts = null;
    this.plates.clear();
    for (int i = 0; i < EXTRA_ROWS; i++) {
      if (this.extras[i] != null) {
        this.extras[i].plates.clear();
        this.extras[i].composed = null;
      }
    }
  }

  Extra extra(int row) {
    Extra known = this.extras[row];
    if (known == null) {
      known = new Extra();
      this.extras[row] = known;
    }

    return known;
  }

  /** A smaller row around the main line: what is sung behind it, the next line or an earlier one. */
  static final class Extra {

    final LineComposer composer = new LineComposer(true);
    final LetterPlates plates = new LetterPlates();
    /** Null while the row is not shown. */
    ComposedLine composed;
    float y;
    float alpha;
    private VectorFace readyFace;
    private String readyText;

    /** Whether this text's letters can be laid out without building any. It remembers, as it is asked every frame. */
    boolean ready(VectorFace face, String text) {
      if (face == null || (face == this.readyFace && text == this.readyText)) {
        return true;
      }

      if (!face.ready(text)) {
        return false;
      }

      this.readyFace = face;
      this.readyText = text;
      return true;
    }
  }
}
