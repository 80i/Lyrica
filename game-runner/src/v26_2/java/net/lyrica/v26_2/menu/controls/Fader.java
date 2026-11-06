package net.lyrica.v26_2.menu.controls;

import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A number on a hairline track with a square knob. The value stands at the right end of the line. */
public final class Fader extends Control {

  public interface Get {

    float get();
  }

  public interface Set {

    void set(float value);
  }

  public interface Format {

    String text(float value);
  }

  public static final Format TWO_PLACES = value -> String.format(Locale.ROOT, "%.2f", value);
  public static final Format ONE_PLACE = value -> String.format(Locale.ROOT, "%.1f", value);
  public static final Format WHOLE = value -> Integer.toString(Math.round(value));
  public static final Format PERCENT = value -> Math.round(value * 100.0F) + "%";
  public static final Format SIGNED = value -> {
    int whole = Math.round(value);
    return whole > 0 ? "+" + whole : Integer.toString(whole);
  };

  private static final int VALUE_WIDTH = 31;
  private static final int NUDGE = 7;
  private static final int KNOB_WIDTH = 4;
  private static final int KNOB_HEIGHT = 9;

  private final Get get;
  private final Set set;
  private final Get plain;
  private final float min;
  private final float max;
  private final float step;
  private final Format format;

  private boolean nudged;
  private boolean dragging;
  private float shownFor = Float.NaN;
  private String shown = "";
  private int labelWidth;
  private int trackLeft;
  private int trackRight;

  public Fader(String key, Get get, Set set, Get plain, float min, float max, float step, Format format) {
    super(words(key), words(key + ".desc"));
    this.get = get;
    this.set = set;
    this.plain = plain;
    this.min = min;
    this.max = max;
    this.step = step;
    this.format = format;
  }

  /** Adds a square at each end of the track that moves the value by one step. */
  public Fader nudged() {
    this.nudged = true;
    return this;
  }

  @Override
  public void place(int x, int y, int w, int h) {
    super.place(x, y, w, h);
    int inner = w - Ink.GUTTER;
    this.labelWidth = inner * 2 / 5;
    int ends = this.nudged ? NUDGE + 3 : 0;
    this.trackLeft = x + Ink.GUTTER + this.labelWidth + 3 + ends;
    this.trackRight = x + w - VALUE_WIDTH - 3 - ends;
  }

  @Override
  protected void draw(GuiGraphicsExtractor g, Font font, int mx, int my, boolean hot, boolean live) {
    float value = this.get.get();
    boolean busy = hot || this.dragging;
    this.drawLabel(g, font, this.labelWidth, labelColor(busy, live));
    int middle = this.y + Ink.LINE / 2;
    int length = this.trackRight - this.trackLeft;
    if (length > 0) {
      Ink.hair(g, this.trackLeft, middle, length + 1, live ? Ink.HAIR_BRIGHT : Ink.HAIR);
      float rest = this.min < 0.0F && this.max > 0.0F ? 0.0F : this.min;
      int origin = this.trackLeft + Math.round(this.fraction(rest) * length);
      Ink.hairDown(g, this.trackLeft, middle - 1, 3, Ink.HAIR_BRIGHT);
      Ink.hairDown(g, this.trackRight, middle - 1, 3, Ink.HAIR_BRIGHT);
      Ink.hairDown(g, origin, middle - 2, 5, Ink.HAIR_BRIGHT);

      int knob = this.trackLeft + Math.round(this.fraction(value) * length);
      if (live) {
        Ink.hair(g, Math.min(origin, knob), middle, Math.abs(knob - origin), Ink.TEXT);
      }

      int knobLeft = knob - KNOB_WIDTH / 2;
      int knobTop = this.y + (Ink.LINE - KNOB_HEIGHT) / 2;
      g.fill(knobLeft, knobTop, knobLeft + KNOB_WIDTH, knobTop + KNOB_HEIGHT,
          !live ? Ink.FAINT : busy ? Ink.ACCENT : Ink.TEXT);
    }

    if (this.nudged) {
      this.drawNudge(g, this.minusLeft(), false, mx, my, live);
      this.drawNudge(g, this.plusLeft(), true, mx, my, live);
    }

    if (value != this.shownFor) {
      this.shownFor = value;
      this.shown = this.format.text(value);
    }

    g.text(font, this.shown, this.x + this.w - font.width(this.shown) - 1, this.y + Ink.TEXT_DROP,
        !live ? Ink.FAINT : this.dragging ? Ink.TEXT : Ink.DIM, false);
  }

  private void drawNudge(GuiGraphicsExtractor g, int left, boolean plus, int mx, int my, boolean live) {
    int top = this.y + (Ink.LINE - NUDGE) / 2;
    boolean over = live && Ink.inside(mx, my, left, top, NUDGE, NUDGE);
    int color = !live ? Ink.FAINT : over ? Ink.ACCENT : Ink.DIM;
    Ink.frame(g, left, top, NUDGE, NUDGE, color);
    Ink.hair(g, left + 2, top + 3, 3, color);
    if (plus) {
      Ink.hairDown(g, left + 3, top + 2, 3, color);
    }
  }

  @Override
  protected boolean pressed(double mx, double my, int button) {
    if (this.nudged) {
      int top = this.y + (Ink.LINE - NUDGE) / 2;
      if (Ink.inside(mx, my, this.minusLeft(), top, NUDGE, NUDGE)) {
        this.store(this.get.get() - this.step);
        Ink.click(0.9F);
        return false;
      }

      if (Ink.inside(mx, my, this.plusLeft(), top, NUDGE, NUDGE)) {
        this.store(this.get.get() + this.step);
        Ink.click(1.1F);
        return false;
      }
    }

    if (mx < this.trackLeft - KNOB_WIDTH || mx > this.trackRight + KNOB_WIDTH) {
      return false;
    }

    this.dragging = true;
    this.drag(mx, my);
    return true;
  }

  @Override
  public void drag(double mx, double my) {
    int length = this.trackRight - this.trackLeft;
    if (this.dragging && length > 0) {
      double at = Math.max(0.0, Math.min(1.0, (mx - this.trackLeft) / length));
      this.store(this.min + (float) at * (this.max - this.min));
    }
  }

  @Override
  public void release(double mx, double my) {
    if (this.dragging) {
      this.dragging = false;
      Ink.click(1.2F);
    }
  }

  @Override
  protected boolean isChanged() {
    return Math.abs(this.get.get() - this.plain.get()) > this.step * 0.5F;
  }

  @Override
  protected void reset() {
    this.set.set(this.plain.get());
  }

  private int minusLeft() {
    return this.trackLeft - NUDGE - 3;
  }

  private int plusLeft() {
    return this.trackRight + 3;
  }

  private void store(float value) {
    float snapped = Math.round(value / this.step) * this.step;
    this.set.set(Math.max(this.min, Math.min(this.max, snapped)));
  }

  private float fraction(float value) {
    float at = (value - this.min) / (this.max - this.min);
    return Math.max(0.0F, Math.min(1.0F, at));
  }
}
