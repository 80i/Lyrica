package net.lyrica.v1_21_10.menu.controls;

import net.minecraft.client.gui.GuiGraphics;

/** Horizontal scrolling for a strip: wheel and a draggable hairline, eased so that the strip glides. */
public final class Scroller {

  private static final float WHEEL_STEP = 48.0F;
  private static final float EASE_MS = 55.0F;
  private static final long WHEEL_SETTLE_MS = 350L;
  private static final int LEAST_THUMB = 16;

  private float target;
  private float now;
  private int content;
  private int view;
  private long lastWheelMs;
  private boolean dragging;
  private double grabOffset;

  public void range(int contentWidth, int viewWidth) {
    this.content = contentWidth;
    this.view = viewWidth;
    this.target = this.clamp(this.target);
  }

  public int max() {
    return Math.max(0, this.content - this.view);
  }

  public void wheel(double amount) {
    if (this.max() > 0) {
      this.target = this.clamp(this.target - (float) amount * WHEEL_STEP);
      this.lastWheelMs = System.currentTimeMillis();
    }
  }

  /** True just after the wheel moved the strip: the pointer is passing over things, not aiming at them. */
  public boolean movedLately() {
    return System.currentTimeMillis() - this.lastWheelMs < WHEEL_SETTLE_MS;
  }

  public void update(float frameMs) {
    float keep = (float) Math.exp(-frameMs / EASE_MS);
    this.now = this.target + (this.now - this.target) * keep;
    if (Math.abs(this.now - this.target) < 0.5F) {
      this.now = this.target;
    }
  }

  /** Whole pixels, because text drawn between pixels goes blurry. */
  public int offset() {
    return Math.round(this.now);
  }

  /** Scrolls just enough to show a span given in content coordinates. */
  public void show(int from, int to) {
    if (from < this.target) {
      this.target = this.clamp(from);
    } else if (to > this.target + this.view) {
      this.target = this.clamp(to - this.view);
    }
  }

  public void drawBar(GuiGraphics g, int x, int y, int width) {
    if (this.max() <= 0) {
      return;
    }

    Ink.hair(g, x, y, width, Ink.HAIR);
    int thumb = this.thumbWidth(width);
    int left = x + Math.round((width - thumb) * this.now / this.max());
    g.fill(left, y - 1, left + thumb, y + 1, this.dragging ? Ink.ACCENT : Ink.DIM);
  }

  public void pressBar(double mx, int x, int width) {
    int thumb = this.thumbWidth(width);
    double left = x + (width - thumb) * (double) this.target / Math.max(1, this.max());
    boolean onThumb = mx >= left && mx < left + thumb;
    this.grabOffset = onThumb ? mx - left : thumb * 0.5;
    this.dragging = true;
    this.dragBar(mx, x, width);
  }

  public void dragBar(double mx, int x, int width) {
    if (this.dragging) {
      int room = Math.max(1, width - this.thumbWidth(width));
      this.target = this.clamp((float) ((mx - this.grabOffset - x) / room * this.max()));
    }
  }

  public boolean draggingBar() {
    return this.dragging;
  }

  public void releaseBar() {
    this.dragging = false;
  }

  private int thumbWidth(int width) {
    int thumb = (int) (width * (long) this.view / Math.max(1, this.content));
    return Math.max(Math.min(LEAST_THUMB, width), Math.min(width, thumb));
  }

  private float clamp(float value) {
    return Math.max(0.0F, Math.min(this.max(), value));
  }
}
