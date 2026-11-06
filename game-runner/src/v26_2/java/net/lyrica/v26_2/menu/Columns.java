package net.lyrica.v26_2.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.labymod.api.util.I18n;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;
import net.lyrica.v26_2.menu.controls.Control;
import net.lyrica.v26_2.menu.controls.Ink;
import net.lyrica.v26_2.menu.controls.Scroller;

/**
 * The channel strips of a page: groups of controls flowing into columns left to right. A group that is too tall for the
 * dock carries on in a next column, so nothing is ever cut off at any size; what does not fit the width scrolls.
 */
final class Columns {

  private static final int WIDE = 150;
  private static final int WIDEST = 200;
  private static final int NARROWEST = 100;
  private static final int MARGIN = 8;
  private static final int GAP = 13;
  private static final int TITLE = 13;
  private static final int LINE_GAP = 1;
  private static final int BAR_ROOM = 4;
  private static final int MOST_SPAN = 5;

  /** A group of controls under one title. */
  static final class Group {

    private final String title;
    private final List<Control> controls = new ArrayList<>();

    private Group(String title) {
      this.title = title.toUpperCase(Locale.ROOT);
    }

    Group add(Control control) {
      this.controls.add(control);
      return this;
    }
  }

  private record Head(String number, FormattedCharSequence title, int x, boolean carried) {

  }

  private final List<Group> groups = new ArrayList<>();
  private final List<Head> heads = new ArrayList<>();
  private final Scroller scroller = new Scroller();

  private Font font;
  private int left;
  private int top;
  private int viewWidth;
  private int viewHeight;
  private int columnWidth = WIDE;
  private Control captured;
  private Control focus;

  void clear() {
    this.groups.clear();
  }

  void resetAll() {
    for (int i = 0; i < this.groups.size(); i++) {
      List<Control> controls = this.groups.get(i).controls;
      for (int j = 0; j < controls.size(); j++) {
        controls.get(j).resetIfChanged();
      }
    }
  }

  Group group(String titleKey) {
    return this.groupOf(I18n.translate(titleKey));
  }

  /** A group under text that needs no translating, like a player's name. */
  Group groupOf(String title) {
    Group group = new Group(title);
    this.groups.add(group);
    return group;
  }

  void layout(Font font, int left, int top, int width, int height) {
    this.font = font;
    this.left = left;
    this.top = top;
    this.viewWidth = width;
    this.viewHeight = height;

    int room = Math.max(NARROWEST, width - MARGIN * 2);
    int columns = this.flow(Math.min(WIDE, room));
    int filled = columns * (Math.min(WIDE, room) + GAP) - GAP;
    if (columns > 0 && filled < room) {
      // Everything fits, so the strips may breathe rather than huddle at the left of a wide screen.
      this.flow(Math.min(WIDEST, (room - (columns - 1) * GAP) / columns));
    }
  }

  private int flow(int width) {
    this.columnWidth = width;
    this.heads.clear();
    int usable = this.viewHeight - BAR_ROOM;
    int column = 0;
    for (int g = 0; g < this.groups.size(); g++) {
      Group group = this.groups.get(g);
      String number = String.format(Locale.ROOT, "%02d", g + 1);
      FormattedCharSequence title = FormattedCharSequence.forward(group.title, Ink.FACE);
      this.heads.add(new Head(number, title, this.columnX(column), false));
      int y = TITLE;
      for (int i = 0; i < group.controls.size(); i++) {
        Control control = group.controls.get(i);
        // A long list of chips can be taller than a low dock; then it takes the width of more columns instead.
        int span = 1;
        int wide = width;
        int height = control.heightFor(this.font, wide);
        while (height > usable - TITLE && span < MOST_SPAN) {
          span++;
          wide = width * span + GAP * (span - 1);
          height = control.heightFor(this.font, wide);
        }

        if (y + height > usable && y > TITLE) {
          column++;
          this.heads.add(new Head(number, title, this.columnX(column), true));
          y = TITLE;
        }

        control.place(this.columnX(column), y, wide, height);
        y += height + LINE_GAP;
        column += span - 1;
      }

      column++;
    }

    int content = column == 0 ? 0 : this.columnX(column - 1) + width + MARGIN;
    this.scroller.range(content, this.viewWidth);
    return column;
  }

  private int columnX(int column) {
    return MARGIN + column * (this.columnWidth + GAP);
  }

  void render(GuiGraphicsExtractor g, int mx, int my, float frameMs) {
    this.scroller.update(frameMs);
    int offset = this.scroller.offset();
    boolean over = this.contains(mx, my) && !this.overBar(my);
    int localX = over ? mx - this.left + offset : -1;
    int localY = over ? my - this.top : -1;
    Control hovered = this.captured != null ? this.captured : over ? this.at(localX, localY) : null;
    if (this.captured != null) {
      localX = mx - this.left + offset;
      localY = my - this.top;
    }

    g.enableScissor(this.left, this.top, this.left + this.viewWidth, this.top + this.viewHeight);
    g.pose().pushMatrix();
    g.pose().translate((float) (this.left - offset), (float) this.top);
    for (int i = 0; i < this.heads.size(); i++) {
      this.drawHead(g, this.heads.get(i), offset);
    }

    for (int i = 0; i < this.groups.size(); i++) {
      List<Control> controls = this.groups.get(i).controls;
      for (int j = 0; j < controls.size(); j++) {
        Control control = controls.get(j);
        if (control.x + control.w >= offset && control.x <= offset + this.viewWidth) {
          control.render(g, this.font, localX, localY, control == hovered);
        }
      }
    }

    g.pose().popMatrix();
    g.disableScissor();
    this.scroller.drawBar(g, this.left + MARGIN, this.barY(), this.barWidth());
  }

  private void drawHead(GuiGraphicsExtractor g, Head head, int offset) {
    if (head.x + this.columnWidth < offset || head.x > offset + this.viewWidth) {
      return;
    }

    if (head.x > MARGIN) {
      Ink.hairDown(g, head.x - GAP / 2 - 1, 0, this.viewHeight - BAR_ROOM, Ink.HAIR);
    }

    g.text(this.font, head.number, head.x + Ink.GUTTER, 2, Ink.FAINT, false);
    int titleLeft = head.x + Ink.GUTTER + this.font.width(head.number) + 4;
    g.text(this.font, head.title, titleLeft, 2, head.carried ? Ink.FAINT : Ink.TEXT, false);
  }

  String hint(double mx, double my) {
    if (!this.contains(mx, my)) {
      return null;
    }

    double localX = mx - this.left + this.scroller.offset();
    Control control = this.at(localX, my - this.top);
    return control == null ? null : control.hint(localX, my - this.top);
  }

  boolean press(double mx, double my, int button) {
    if (!this.contains(mx, my)) {
      return false;
    }

    if (this.overBar(my) && this.scroller.max() > 0) {
      this.scroller.pressBar(mx, this.left + MARGIN, this.barWidth());
      this.setFocus(null);
      return true;
    }

    double localX = mx - this.left + this.scroller.offset();
    double localY = my - this.top;
    Control control = this.at(localX, localY);
    this.captured = control != null && control.press(localX, localY, button) ? control : null;
    this.setFocus(control != null && control.wantsKeys() ? control : null);
    return true;
  }

  void drag(double mx, double my) {
    if (this.scroller.draggingBar()) {
      this.scroller.dragBar(mx, this.left + MARGIN, this.barWidth());
    } else if (this.captured != null) {
      this.captured.drag(mx - this.left + this.scroller.offset(), my - this.top);
    }
  }

  void release(double mx, double my) {
    this.scroller.releaseBar();
    if (this.captured != null) {
      this.captured.release(mx - this.left + this.scroller.offset(), my - this.top);
      this.captured = null;
    }
  }

  boolean wheel(double mx, double my, double amount) {
    if (!this.contains(mx, my)) {
      return false;
    }

    if (!this.scroller.movedLately()) {
      Control control = this.at(mx - this.left + this.scroller.offset(), my - this.top);
      if (control != null && control.enabled() && control.wheel(amount)) {
        return true;
      }
    }

    this.scroller.wheel(amount);
    return true;
  }

  boolean key(KeyEvent event) {
    return this.focus != null && this.focus.key(event);
  }

  boolean chars(CharacterEvent event) {
    return this.focus != null && this.focus.chars(event);
  }

  boolean hasFocus() {
    // A control can give up the keys on its own, like a colour field on Enter.
    if (this.focus != null && !this.focus.wantsKeys()) {
      this.focus = null;
    }

    return this.focus != null;
  }

  void blur() {
    this.setFocus(null);
  }

  private void setFocus(Control next) {
    if (this.focus != null && this.focus != next) {
      this.focus.blur();
    }

    this.focus = next;
  }

  private Control at(double localX, double localY) {
    for (int i = 0; i < this.groups.size(); i++) {
      List<Control> controls = this.groups.get(i).controls;
      for (int j = 0; j < controls.size(); j++) {
        Control control = controls.get(j);
        if (Ink.inside(localX, localY, control.x, control.y, control.w, control.h)) {
          return control;
        }
      }
    }

    return null;
  }

  private boolean contains(double mx, double my) {
    return Ink.inside(mx, my, this.left, this.top, this.viewWidth, this.viewHeight);
  }

  private boolean overBar(double my) {
    return my >= this.top + this.viewHeight - BAR_ROOM;
  }

  private int barY() {
    return this.top + this.viewHeight - 2;
  }

  private int barWidth() {
    return this.viewWidth - MARGIN * 2;
  }
}
