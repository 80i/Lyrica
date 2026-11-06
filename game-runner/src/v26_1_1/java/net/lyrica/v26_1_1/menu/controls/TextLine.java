package net.lyrica.v26_1_1.menu.controls;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.IntPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** A single line of editable text with a caret and a selection, as {@link Field} and {@link Swatches} need it. */
final class TextLine {

  private static final long BLINK_MS = 500L;
  private static final int SELECTION = 0x66FF5A36;

  private final int max;
  private final IntPredicate allowed;

  private String text = "";
  private int caret;
  private int anchor;
  private int view;

  TextLine(int max, IntPredicate allowed) {
    this.max = max;
    this.allowed = allowed;
  }

  String text() {
    return this.text;
  }

  void set(String value) {
    this.text = value;
    this.caret = value.length();
    this.anchor = this.caret;
    this.view = 0;
  }

  boolean key(KeyEvent event) {
    if (event.isSelectAll()) {
      this.anchor = 0;
      this.caret = this.text.length();
      return true;
    }

    if (event.isCopy()) {
      if (this.caret != this.anchor) {
        Minecraft.getInstance().keyboardHandler.setClipboard(this.selected());
      }

      return true;
    }

    if (event.isCut()) {
      if (this.caret != this.anchor) {
        Minecraft.getInstance().keyboardHandler.setClipboard(this.selected());
        this.removeSelection();
      }

      return true;
    }

    if (event.isPaste()) {
      this.insert(Minecraft.getInstance().keyboardHandler.getClipboard());
      return true;
    }

    boolean extend = event.hasShiftDown();
    switch (event.key()) {
      case InputConstants.KEY_LEFT -> this.moveTo(this.previous(), extend);
      case InputConstants.KEY_RIGHT -> this.moveTo(this.following(), extend);
      case InputConstants.KEY_HOME -> this.moveTo(0, extend);
      case InputConstants.KEY_END -> this.moveTo(this.text.length(), extend);
      case InputConstants.KEY_BACKSPACE -> {
        if (this.caret == this.anchor) {
          this.anchor = this.previous();
        }

        this.removeSelection();
      }
      case InputConstants.KEY_DELETE -> {
        if (this.caret == this.anchor) {
          this.anchor = this.following();
        }

        this.removeSelection();
      }
      default -> {
        return false;
      }
    }

    return true;
  }

  boolean chars(CharacterEvent event) {
    if (!event.isAllowedChatCharacter()) {
      return false;
    }

    this.insert(event.codepointAsString());
    return true;
  }

  void clickAt(Font font, double fromLeft) {
    String seen = this.text.substring(this.view);
    int at = this.view + font.plainSubstrByWidth(seen, (int) Math.max(0.0, fromLeft)).length();
    if (at > 0 && at < this.text.length() && Character.isLowSurrogate(this.text.charAt(at))) {
      at--;
    }

    this.moveTo(at, false);
  }

  void draw(GuiGraphicsExtractor g, Font font, int x, int y, int width, int color, boolean focused) {
    if (width <= 0) {
      return;
    }

    if (this.caret < this.view) {
      this.view = this.caret;
    }

    while (this.view < this.caret && font.width(this.text.substring(this.view, this.caret)) > width - 2) {
      this.view = this.text.offsetByCodePoints(this.view, 1);
    }

    String seen = font.plainSubstrByWidth(this.text.substring(this.view), width);
    if (focused && this.caret != this.anchor) {
      int from = Math.max(Math.min(this.caret, this.anchor), this.view);
      int to = Math.min(Math.max(this.caret, this.anchor), this.view + seen.length());
      if (from < to) {
        int left = x + font.width(this.text.substring(this.view, from));
        int right = x + font.width(this.text.substring(this.view, to));
        g.fill(left, y - 1, right, y + 9, SELECTION);
      }
    }

    g.text(font, seen, x, y, color, false);
    if (focused && System.currentTimeMillis() / BLINK_MS % 2L == 0L) {
      int left = x + font.width(this.text.substring(this.view, this.caret));
      Ink.hairDown(g, left, y - 1, 10, Ink.ACCENT);
    }
  }

  private int previous() {
    return this.caret > 0 ? this.text.offsetByCodePoints(this.caret, -1) : 0;
  }

  private int following() {
    return this.caret < this.text.length() ? this.text.offsetByCodePoints(this.caret, 1) : this.caret;
  }

  private String selected() {
    return this.text.substring(Math.min(this.caret, this.anchor), Math.max(this.caret, this.anchor));
  }

  private void moveTo(int at, boolean extend) {
    this.caret = at;
    if (!extend) {
      this.anchor = at;
    }
  }

  private void removeSelection() {
    int from = Math.min(this.caret, this.anchor);
    int to = Math.max(this.caret, this.anchor);
    this.text = this.text.substring(0, from) + this.text.substring(to);
    this.caret = from;
    this.anchor = from;
    if (this.view > from) {
      this.view = from;
    }
  }

  private void insert(String typed) {
    this.removeSelection();
    StringBuilder clean = new StringBuilder();
    int room = this.max - this.text.length();
    int i = 0;
    while (i < typed.length()) {
      int point = typed.codePointAt(i);
      i += Character.charCount(point);
      if (point >= 32 && point != 127 && this.allowed.test(point)
          && clean.length() + Character.charCount(point) <= room) {
        clean.appendCodePoint(point);
      }
    }

    this.text = this.text.substring(0, this.caret) + clean + this.text.substring(this.caret);
    this.caret += clean.length();
    this.anchor = this.caret;
  }
}
