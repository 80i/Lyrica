package net.lyrica.v26_1_2.menu.controls;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.lyrica.v26_1_2.look.Fonts;
import net.lyrica.v26_1_2.look.Palette;

/** The desk's colours, measures and the few drawing strokes every control shares. */
public final class Ink {

  public static final int DOCK = 0xE60B0A09;
  public static final int HAIR = 0x2EF6F1E3;
  public static final int HAIR_BRIGHT = 0x73F6F1E3;
  public static final int TEXT = 0xFFECE7DA;
  public static final int DIM = 0xFF96928A;
  public static final int FAINT = 0xFF5F5C55;
  public static final int BLACK = 0xFF0B0A09;
  public static final int ACCENT = 0xFF000000 | Palette.ACCENT;
  public static final int WRONG = 0xFFFF3B3B;

  /** The height of one control line. */
  public static final int LINE = 13;
  /** The strip left of every control where its reset button sits. */
  public static final int GUTTER = 10;
  /** How wide and tall the reset button's arrow is. */
  public static final int UNDO_SIZE = 7;
  /** Where text sits inside a line. */
  public static final int TEXT_DROP = 3;

  public static final Style FACE = Fonts.style("BEBAS", false);

  private static final String ELLIPSIS = "…";
  private static final float CLICK_VOLUME = 0.28F;
  private static final int DASH = 3;
  private static final String[] UNDO = {
      "#.###..",
      "##...#.",
      "###...#",
      "......#",
      "......#",
      "#....#.",
      ".####.."
  };

  private Ink() {
  }

  public static void hair(GuiGraphicsExtractor g, int x, int y, int width, int argb) {
    g.fill(x, y, x + width, y + 1, argb);
  }

  public static void hairDown(GuiGraphicsExtractor g, int x, int y, int height, int argb) {
    g.fill(x, y, x + 1, y + height, argb);
  }

  public static void frame(GuiGraphicsExtractor g, int x, int y, int width, int height, int argb) {
    hair(g, x, y, width, argb);
    hair(g, x, y + height - 1, width, argb);
    hairDown(g, x, y + 1, height - 2, argb);
    hairDown(g, x + width - 1, y + 1, height - 2, argb);
  }

  public static void dashed(GuiGraphicsExtractor g, int x, int y, int width, int height, int argb) {
    for (int i = 0; i < width; i += DASH * 2) {
      int run = Math.min(DASH, width - i);
      hair(g, x + i, y, run, argb);
      hair(g, x + i, y + height - 1, run, argb);
    }

    for (int i = 0; i < height; i += DASH * 2) {
      int run = Math.min(DASH, height - i);
      hairDown(g, x, y + i, run, argb);
      hairDown(g, x + width - 1, y + i, run, argb);
    }
  }

  /** A small solid arrow in a 3 by 5 box, pointing left or right. */
  public static void arrow(GuiGraphicsExtractor g, int x, int y, boolean right, int argb) {
    for (int i = 0; i < 3; i++) {
      int column = right ? x + i : x + 2 - i;
      hairDown(g, column, y + i, 5 - i * 2, argb);
    }
  }

  /** An arrow turning back on itself, {@link #UNDO_SIZE} square: the sign for putting a value back. */
  public static void undo(GuiGraphicsExtractor g, int x, int y, int argb) {
    for (int row = 0; row < UNDO_SIZE; row++) {
      for (int column = 0; column < UNDO_SIZE; column++) {
        if (UNDO[row].charAt(column) == '#') {
          g.fill(x + column, y + row, x + column + 1, y + row + 1, argb);
        }
      }
    }
  }

  /** The text, cut with an ellipsis when it does not fit, so that nothing ever runs into its neighbour. */
  public static String fit(Font font, String text, int width) {
    if (width <= 0) {
      return "";
    }

    if (font.width(text) <= width) {
      return text;
    }

    return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ELLIPSIS))) + ELLIPSIS;
  }

  public static boolean inside(double mx, double my, int x, int y, int width, int height) {
    return mx >= x && mx < x + width && my >= y && my < y + height;
  }

  public static void click(float pitch) {
    Minecraft.getInstance().getSoundManager()
        .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, CLICK_VOLUME));
  }
}
