package net.lyrica.v26_1_1.menu.controls;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A text button. Either a whole line, or a row in a list: words at the left and one or two small buttons at the right.
 * A row's button with nothing to run just stands there as a word, like "In use".
 */
public final class Action extends Control {

  private static final int PADDING = 3;
  private static final int GAP = 2;
  private static final int DOT = 6;
  private static final int MARK = 3;
  private static final int INDENT = 9;
  private static final long FLASH_MS = 1_500L;

  private final boolean wholeLine;
  private final String firstText;
  private final Runnable first;

  private String secondText;
  private Runnable second;
  private boolean dotted;
  private boolean indented;
  private int dotColor;
  private String flashText;
  private long flashUntil;
  private boolean marked;
  private int firstWidth;
  private int secondWidth;

  /** A whole-line button with the words of {@code key} and the description of {@code key.desc}. */
  public Action(String key, Runnable run) {
    super(words(key), words(key + ".desc"));
    this.wholeLine = true;
    this.firstText = this.label;
    this.first = run;
  }

  private Action(String text, String description, String button, Runnable run) {
    super(text, description);
    this.wholeLine = false;
    this.firstText = button;
    this.first = run;
  }

  /** A list row; the texts are already translated or are the player's own. */
  public static Action row(String text, String description, String button, Runnable run) {
    return new Action(text, description, button, run);
  }

  public Action second(String button, Runnable run) {
    this.secondText = button;
    this.second = run;
    return this;
  }

  public Action dot(int rgb) {
    this.dotted = true;
    this.dotColor = 0xFF000000 | rgb;
    return this;
  }

  /** Sets the row in by as much as a dot takes, so that it reads as belonging to the row above with the dot. */
  public Action indent() {
    this.indented = true;
    return this;
  }

  /** A small square in the accent colour right after the words, for a row that has something special about it. */
  public Action mark(boolean on) {
    this.marked = on;
    return this;
  }

  /** Words the button shows for a moment after a press, to say that it worked. */
  public Action flash(String words) {
    this.flashText = words;
    return this;
  }

  @Override
  public int heightFor(Font font, int width) {
    this.firstWidth = font.width(this.firstText) + PADDING * 2;
    this.secondWidth = this.secondText == null ? 0 : font.width(this.secondText) + PADDING * 2;
    return Ink.LINE;
  }

  @Override
  protected void draw(GuiGraphicsExtractor g, Font font, int mx, int my, boolean hot, boolean live) {
    if (this.wholeLine) {
      this.drawWhole(g, font, hot, live);
      return;
    }

    int left = this.x + Ink.GUTTER;
    int firstLeft = this.firstLeft();
    int textLeft = this.indented ? left + INDENT : left;
    if (this.dotted) {
      g.fill(left, this.y + 3, left + DOT, this.y + 3 + DOT, this.dotColor);
      textLeft += DOT + 3;
    }

    String text = Ink.fit(font, this.label, firstLeft - textLeft - 3 - (this.marked ? MARK + GAP * 2 : 0));
    g.text(font, text, textLeft, this.y + Ink.TEXT_DROP, live ? Ink.TEXT : Ink.FAINT, false);
    if (this.marked) {
      int markLeft = textLeft + font.width(text) + GAP * 2;
      g.fill(markLeft, this.y + 4, markLeft + MARK, this.y + 4 + MARK, Ink.ACCENT);
    }
    this.drawButton(g, font, firstLeft, this.firstWidth, this.firstText, this.first, hot, mx, live);
    if (this.secondText != null) {
      this.drawButton(g, font, this.secondLeft(), this.secondWidth, this.secondText, this.second, hot, mx, live);
    }
  }

  private void drawWhole(GuiGraphicsExtractor g, Font font, boolean hot, boolean live) {
    int left = this.x + Ink.GUTTER;
    int width = this.w - Ink.GUTTER;
    String text = System.currentTimeMillis() < this.flashUntil ? this.flashText : this.label;
    if (hot) {
      g.fill(left, this.y, left + width, this.y + Ink.LINE - 1, Ink.ACCENT);
    } else {
      Ink.frame(g, left, this.y, width, Ink.LINE - 1, live ? Ink.HAIR_BRIGHT : Ink.HAIR);
    }

    g.text(font, Ink.fit(font, text, width - PADDING * 2), left + PADDING, this.y + 2,
        hot ? Ink.BLACK : live ? Ink.TEXT : Ink.FAINT, false);
  }

  private void drawButton(GuiGraphicsExtractor g, Font font, int left, int width, String text, Runnable run, boolean hot, int mx,
      boolean live) {
    boolean over = run != null && live && hot && mx >= left && mx < left + width;
    if (over) {
      g.fill(left, this.y + 1, left + width, this.y + Ink.LINE - 1, Ink.ACCENT);
    }

    int color = over ? Ink.BLACK : !live ? Ink.FAINT : run == null ? Ink.ACCENT : Ink.DIM;
    g.text(font, text, left + PADDING, this.y + Ink.TEXT_DROP, color, false);
  }

  @Override
  protected boolean pressed(double mx, double my, int button) {
    if (this.wholeLine) {
      if (mx >= this.x + Ink.GUTTER) {
        this.run(this.first);
      }

      return false;
    }

    int firstLeft = this.firstLeft();
    if (mx >= firstLeft && mx < firstLeft + this.firstWidth) {
      this.run(this.first);
    } else if (this.secondText != null && mx >= this.secondLeft()) {
      this.run(this.second);
    }

    return false;
  }

  private void run(Runnable run) {
    if (run != null) {
      run.run();
      Ink.click(1.2F);
      if (this.flashText != null) {
        this.flashUntil = System.currentTimeMillis() + FLASH_MS;
      }
    }
  }

  private int firstLeft() {
    int rightEnd = this.x + this.w;
    return this.secondText == null ? rightEnd - this.firstWidth : rightEnd - this.secondWidth - GAP - this.firstWidth;
  }

  private int secondLeft() {
    return this.x + this.w - this.secondWidth;
  }
}
