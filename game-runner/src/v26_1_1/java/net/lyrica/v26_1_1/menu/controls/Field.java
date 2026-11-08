package net.lyrica.v26_1_1.menu.controls;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Text to type and a button that takes it; Enter is the button too. The label stands in the empty field. The action
 * answers whether it took the text: if it did not, the line flashes. A {@link #live} field has no button instead: its
 * label stands beside it, and what is typed takes effect with every key.
 */
public final class Field extends Control {

  private static final int MAX_LENGTH = 128;
  private static final long WRONG_MS = 700L;
  private static final int BUTTON_PADDING = 3;
  private static final int LIVE_MAX_LENGTH = 64;
  private static final int LABEL_GAP = 3;
  private static final int COLOUR_CODE = 0xA7;

  private final String button;
  private final Predicate<String> action;
  private final boolean keepText;
  private final TextLine line;
  private final Supplier<String> get;
  private final Consumer<String> set;
  private final Supplier<String> plain;
  private final Supplier<String> whenEmpty;

  private int buttonWidth;
  private boolean focused;
  private long wrongUntil;

  public Field(String key, String buttonKey, Predicate<String> action, boolean keepText) {
    this(key, buttonKey, action, keepText, MAX_LENGTH);
  }

  /** @param maxLength how many characters it takes, for text longer than a name, like a look's share code */
  public Field(String key, String buttonKey, Predicate<String> action, boolean keepText, int maxLength) {
    super(words(key), words(key + ".desc"));
    this.button = words(buttonKey);
    this.action = action;
    this.keepText = keepText;
    this.line = new TextLine(maxLength, point -> true);
    this.get = null;
    this.set = null;
    this.plain = null;
    this.whenEmpty = null;
  }

  private Field(String key, Supplier<String> get, Consumer<String> set, Supplier<String> plain,
      Supplier<String> whenEmpty) {
    super(words(key), words(key + ".desc"));
    this.button = null;
    this.action = null;
    this.keepText = true;
    this.line = new TextLine(LIVE_MAX_LENGTH, point -> point != COLOUR_CODE);
    this.get = get;
    this.set = set;
    this.plain = plain;
    this.whenEmpty = whenEmpty;
  }

  /**
   * A field that shows a setting and changes it as it is typed in.
   *
   * @param plain     what its reset mark goes back to
   * @param whenEmpty the faint words that stand in it while it is empty
   */
  public static Field live(String key, Supplier<String> get, Consumer<String> set, Supplier<String> plain,
      Supplier<String> whenEmpty) {
    return new Field(key, get, set, plain, whenEmpty);
  }

  /** Puts words in it, as they stand until somebody types over them. */
  public Field text(String words) {
    this.line.set(words);
    return this;
  }

  @Override
  public int heightFor(Font font, int width) {
    this.buttonWidth =this.button == null ? 0 : font.width(this.button) + BUTTON_PADDING * 2;
    return Ink.LINE;
  }

  @Override
  protected void draw(GuiGraphicsExtractor g, Font font, int mx, int my, boolean hot, boolean live) {
    if (this.get != null) {
      this.drawLive(g, font, hot, live);
      return;
    }

    int left = this.x + Ink.GUTTER;
    int inputWidth = this.inputWidth();
    boolean wrong = System.currentTimeMillis() < this.wrongUntil;
    int underColor = wrong ? Ink.WRONG : this.focused ? Ink.ACCENT : hot ? Ink.HAIR_BRIGHT : Ink.HAIR;
    Ink.hair(g, left, this.y + Ink.LINE - 2, inputWidth, underColor);
    if (this.line.text().isEmpty() && !this.focused) {
      g.text(font, Ink.fit(font, this.label, inputWidth), left, this.y + 2, Ink.FAINT, false);
    } else {
      this.line.draw(g, font, left, this.y + 2, inputWidth, live ? Ink.TEXT : Ink.FAINT, this.focused);
    }

    int buttonLeft = this.buttonLeft();
    boolean over = hot && mx >= buttonLeft;
    if (over) {
      g.fill(buttonLeft, this.y, this.x + this.w, this.y + Ink.LINE - 1, Ink.ACCENT);
    } else {
      Ink.frame(g, buttonLeft, this.y, this.buttonWidth, Ink.LINE - 1, live ? Ink.HAIR_BRIGHT : Ink.HAIR);
    }

    g.text(font, this.button, buttonLeft + BUTTON_PADDING, this.y + 2,
        over ? Ink.BLACK : live ? Ink.TEXT : Ink.FAINT, false);
  }

  private void drawLive(GuiGraphicsExtractor g, Font font, boolean hot, boolean live) {
    // What stands in it is whatever is in use, until somebody types in it
    if (!this.focused && !this.get.get().equals(this.line.text())) {
      this.line.set(this.get.get());
    }

    this.drawLabel(g, font, this.liveLeft() - this.x - Ink.GUTTER - LABEL_GAP, labelColor(hot || this.focused, live));
    int left = this.liveLeft();
    int width = this.x + this.w - left;
    Ink.hair(g, left, this.y + Ink.LINE - 2, width, this.focused ? Ink.ACCENT : hot ? Ink.HAIR_BRIGHT : Ink.HAIR);
    if (this.line.text().isEmpty() && !this.focused) {
      g.text(font, Ink.fit(font, this.whenEmpty.get(), width), left, this.y + 2, Ink.FAINT, false);
    } else {
      this.line.draw(g, font, left, this.y + 2, width, live ? Ink.TEXT : Ink.FAINT, this.focused);
    }
  }

  private int liveLeft() {
    return this.x + Ink.GUTTER + (this.w - Ink.GUTTER) * 2 / 5;
  }

  @Override
  protected boolean isChanged() {
    return this.get != null && !this.plain.get().equals(this.get.get());
  }

  @Override
  protected void reset() {
    if (this.get != null) {
      this.focused = false;
      this.set.accept(this.plain.get());
    }
  }

  @Override
  protected boolean pressed(double mx, double my, int button) {
    if (this.get != null) {
      this.focused = true;
      this.line.clickAt(Minecraft.getInstance().font, mx - this.liveLeft());
      return false;
    }

    if (mx >= this.buttonLeft()) {
      this.submit();
      return false;
    }

    this.focused = true;
    this.line.clickAt(Minecraft.getInstance().font, mx - this.x - Ink.GUTTER);
    return false;
  }

  @Override
  public boolean wantsKeys() {
    return this.focused;
  }

  @Override
  public void blur() {
    this.focused = false;
  }

  @Override
  public boolean key(KeyEvent event) {
    int key = event.key();
    if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
      if (this.get != null) {
        this.focused = false;
      } else {
        this.submit();
      }

      return true;
    }

    String before = this.line.text();
    boolean used = this.line.key(event);
    this.applyIfChanged(before);
    return used;
  }

  @Override
  public boolean chars(CharacterEvent event) {
    String before = this.line.text();
    boolean used = this.line.chars(event);
    this.applyIfChanged(before);
    return used;
  }

  private void applyIfChanged(String before) {
    if (this.set != null && !before.equals(this.line.text())) {
      this.set.accept(this.line.text());
    }
  }

  private void submit() {
    if (this.action.test(this.line.text().strip())) {
      Ink.click(1.35F);
      if (!this.keepText) {
        this.line.set("");
      }
    } else {
      Ink.click(0.7F);
      this.wrongUntil = System.currentTimeMillis() + WRONG_MS;
    }
  }

  private int inputWidth() {
    return this.w - Ink.GUTTER - this.buttonWidth - 3;
  }

  private int buttonLeft() {
    return this.x + this.w - this.buttonWidth;
  }
}
