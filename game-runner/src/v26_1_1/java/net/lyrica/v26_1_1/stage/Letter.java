package net.lyrica.v26_1_1.stage;

import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * One letter as it stands this frame, or one whole row for right-to-left text. Objects are reused from frame to frame,
 * so do not keep one past the next compose.
 *
 * <p>Space is glyph pixels with y running down and z toward the viewer, the origin being the middle of the top edge of
 * the line (see {@link ComposedLine}). To draw a letter: translate to (x, y, z), turn by {@code tiltY}, then
 * {@code tiltX}, then {@code tiltZ}, scale by {@code scale}, and draw the glyph at (-width / 2, -font.lineHeight / 2).
 *
 * <p>With rotations as {@code Axis.XP}, {@code YP} and {@code ZP} in that space: a positive tiltX leans the top back
 * away from the viewer, a positive tiltY turns the right edge away, a positive tiltZ turns clockwise as seen by the
 * viewer. A spin can take the back of the letter round to the viewer.
 */
public final class Letter {

  /** What to draw this frame. Usually the letter itself; GLITCH puts a wrong one here for a moment. */
  public String glyph = "";
  /** Centre of the letter. */
  public float x;
  public float y;
  public float z;
  /** Includes the look's size. */
  public float scale = 1.0F;
  public float tiltX;
  public float tiltY;
  public float tiltZ;
  /** Alpha 0 means nothing to draw: not yet arrived or already gone. */
  public int argb;
  /** Width of {@link #glyph} in font pixels, before {@link #scale}. */
  public float width;

  /** Which whitespace-separated word this letter belongs to. On a whole row, the row. */
  public int word;
  public int row;

  int index;
  int letterInWord;
  int wordLetters = 1;
  boolean wholeRow;

  String homeGlyph = "";
  float homeWidth;
  float homeX;
  float homeY;
  float homeZ;
  float homeScale = 1.0F;
  float homeTiltZ;

  // Where the letter sits in the line, 0 to 1, for gradients and evenly timed karaoke.
  float spanX;
  float spanY;
  float linePosition;

  // From the line's word timings, in milliseconds from its start. Only meant when timed is true.
  boolean timed;
  float singFrom;
  float singFor;
  float wordFrom;
  float wordTo;

  float alpha = 1.0F;
  float brightness = 1.0F;
  /** How far the letter has broken into pieces, 0 to 1; 0 while it is whole. */
  float shards;

  // The outlines of the glyph, kept for as long as it stays the same one, and which detail level it last used.
  VectorFace.Glyph vector;
  String vectorFor;
  VectorFace vectorFace;
  boolean coarse;

  private FormattedCharSequence sequence;
  private String sequenceGlyph;
  private Style sequenceStyle;

  public boolean visible() {
    return (this.argb >>> 24) != 0;
  }

  /** The glyph as drawable text, kept from frame to frame while glyph and style stay the same. */
  public FormattedCharSequence sequence(Style style) {
    if (this.sequence == null || this.sequenceGlyph != this.glyph || this.sequenceStyle != style) {
      this.sequence = FormattedCharSequence.forward(this.glyph, style);
      this.sequenceGlyph = this.glyph;
      this.sequenceStyle = style;
    }

    return this.sequence;
  }

  void backHome() {
    this.glyph = this.homeGlyph;
    this.width = this.homeWidth;
    this.x = this.homeX;
    this.y = this.homeY;
    this.z = this.homeZ;
    this.scale = this.homeScale;
    this.tiltX = 0.0F;
    this.tiltY = 0.0F;
    this.tiltZ = this.homeTiltZ;
    this.alpha = 1.0F;
    this.brightness = 1.0F;
    this.shards = 0.0F;
  }
}
