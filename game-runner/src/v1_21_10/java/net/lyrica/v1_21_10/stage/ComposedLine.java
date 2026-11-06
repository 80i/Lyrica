package net.lyrica.v1_21_10.stage;

import java.util.ArrayList;
import java.util.List;

/**
 * A line ready to draw. The {@link LineComposer} that made it keeps and changes this object, so it is valid until that
 * composer's next compose.
 *
 * <p>x = 0 is the middle of the line, y = 0 its top edge and y = {@link #height} its bottom edge, with y running down.
 * z = 0 is the middle of the line's depth, positive toward the viewer. Width and height are the line at rest in glyph
 * pixels, with the look's size already in them; animation moves letters outside this box.
 */
public final class ComposedLine {

  public final List<Letter> letters = new ArrayList<>();
  public float width;
  public float height;
  public int wordCount;
  /** The enter and the exit are both over: the line can be dropped. */
  public boolean finished;
  /** How much of the line is showing, 0 to 1, the mean alpha of its letters. For what fades with it, like a shadow. */
  public float presence;
  /** The lowest point of any letter at rest, descenders included, in the same space as {@link #height}. */
  public float restBottom;
  /** How far letters at rest lie toward the viewer and away from them, around z = 0. */
  public float restFront;
  public float restBack;
  /** The letters are real outlines, which can break into pieces, rather than pictures from the font's texture. */
  public boolean outlined;

  String[] glitchGlyphs = new String[0];
  float[] glitchWidths = new float[0];
  float glyphHeight = 9.0F;
}
