package net.lyrica.v1_21_10.menu;

import net.labymod.api.util.I18n;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.lyrica.v1_21_10.look.Look;
import net.lyrica.v1_21_10.look.LookStore;

/** One page of the desk: its word in the tab row and the strips it shows. Groups are built on the render thread only. */
abstract class Page {

  /**
   * What a control's reset goes back to, and what counts as "changed": the plain look, or the ready-made look last put
   * on, so that putting on a look does not light up every reset mark on the desk.
   */
  protected static Look base = new Look();

  final String id;
  final String title;

  protected final Look look = LookStore.get();
  protected final Columns columns = new Columns();

  protected Font font;
  protected int left;
  protected int top;
  protected int width;
  protected int height;

  private boolean stale = true;

  Page(String id) {
    this.id = id;
    this.title = I18n.translate("lyrical.menu.page." + id);
  }

  /** Fills the columns. Controls that keep typed text must be kept by the page and added again. */
  protected abstract void build();

  /** Asks for a rebuild before the next frame: never rebuilt during input, while controls are being walked. */
  protected final void rebuild() {
    this.stale = true;
  }

  /** Puts back every value on this page, whether or not it has been on screen yet. It rebuilds before it next draws. */
  void resetAll() {
    if (this.stale) {
      this.columns.clear();
      this.build();
    }

    this.columns.resetAll();
    this.stale = true;
  }

  /** Checked each frame by pages whose content comes from elsewhere. */
  protected boolean outdated() {
    return false;
  }

  void tick() {
  }

  /** It is on screen now: the desk opened on it, or its tab was chosen. May be called twice in a row. */
  void shown() {
  }

  /** It is not on screen any more: another tab was chosen, or the desk closed. May be called twice in a row. */
  void hidden() {
  }

  void layout(Font font, int left, int top, int width, int height) {
    this.font = font;
    this.left = left;
    this.top = top;
    this.width = width;
    this.height = height;
    if (!this.stale) {
      this.layOutColumns();
    }
  }

  protected void layOutColumns() {
    this.columns.layout(this.font, this.left, this.top, this.width, this.height);
  }

  void render(GuiGraphics g, int mx, int my, float frameMs) {
    if (this.stale || this.outdated()) {
      this.stale = false;
      this.columns.clear();
      this.build();
      this.layOutColumns();
    }

    this.columns.render(g, mx, my, frameMs);
  }

  String hint(double mx, double my) {
    return this.columns.hint(mx, my);
  }

  boolean press(double mx, double my, int button) {
    return this.columns.press(mx, my, button);
  }

  void drag(double mx, double my) {
    this.columns.drag(mx, my);
  }

  void release(double mx, double my) {
    this.columns.release(mx, my);
  }

  boolean wheel(double mx, double my, double amount) {
    return this.columns.wheel(mx, my, amount);
  }

  final boolean key(KeyEvent event) {
    return this.columns.key(event);
  }

  final boolean chars(CharacterEvent event) {
    return this.columns.chars(event);
  }

  final boolean hasFocus() {
    return this.columns.hasFocus();
  }

  final void blur() {
    this.columns.blur();
  }
}
