package net.lyrica.v26_2.menu;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.labymod.api.util.I18n;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.lyrica.v26_2.DevSmoke;
import net.lyrica.v26_2.Env;
import net.lyrica.v26_2.hud.NowCard;
import net.lyrica.v26_2.look.Look;
import net.lyrica.v26_2.look.LookStore;
import net.lyrica.v26_2.look.StaffRoster;
import net.lyrica.v26_2.menu.controls.Ink;
import net.lyrica.v26_2.menu.controls.Scroller;
import net.lyrica.v26_2.words.DemoLines;

/**
 * The settings: a mixing desk along the bottom of the screen. The world stays bright above it, and the real line standing
 * in the world is the preview; the game goes on, and with nothing playing a demo line plays while the desk is out.
 */
public final class StudioScreen extends Screen {

  static final int TAB_HEIGHT = 17;

  private static final int HINT_HEIGHT = 12;
  private static final int MOST_DOCK = 190;
  private static final int LEAST_DOCK = 96;
  private static final int WORDMARK_LEFT = 8;
  private static final int TAB_PADDING = 6;
  private static final long RISE_MS = 180L;
  private static final long FALL_MS = 120L;
  private static final long KEY_GUARD_MS = 300L;
  private static final float LONGEST_FRAME_MS = 100.0F;
  private static final long RESET_ARMED_MS = 3_000L;
  private static final int RESET_ICON_GAP = 3;

  private static String lastPageId = "looks";

  private final List<Page> pages = new ArrayList<>();
  private final Look look = LookStore.get();
  private final Scroller tabScroller = new Scroller();
  private final FormattedCharSequence wordmark =
      FormattedCharSequence.forward(I18n.translate("lyrica.menu.wordmark"), Ink.FACE);
  private final String idleHint = I18n.translate("lyrica.menu.hint");
  private final String cardHint = I18n.translate("lyrica.menu.card_hint");
  private final String resetAllWords = I18n.translate("lyrica.menu.reset_all");
  private final String resetAllSure = I18n.translate("lyrica.menu.reset_all.sure");
  private final String resetAllHint = I18n.translate("lyrica.menu.reset_all.desc");

  /** Until when a click on Reset all counts as the second one that really does it. */
  private long resetArmedUntilMs;

  private Page page;
  private int[] tabX = new int[0];
  private int[] tabWidth = new int[0];
  private int tabsLeft;
  private int dockHeight;
  private int dockTop;

  private final long openedMs = System.currentTimeMillis();
  private long closingMs;
  private long lastFrameMs = this.openedMs;

  private boolean draggingCard;
  private double grabX;
  private double grabY;
  private boolean draggingPage;
  private boolean staffPageAdded;

  public StudioScreen() {
    super(Component.literal(I18n.translate("lyrica.menu.title")));
    this.pages.add(new LooksPage());
    this.pages.add(new TypePage());
    this.pages.add(new ColourPage());
    this.pages.add(new SolidPage());
    this.pages.add(new MotionPage());
    this.pages.add(new PlacePage());
    this.pages.add(new CardPage());
    this.pages.add(new HeadPage());
    // The override needs the development environment and the test's marker, so it never holds in a real game
    if (StaffRoster.mayOpenPanel() || DevSmoke.staffPageTest()) {
      this.pages.add(new StaffPage());
      this.staffPageAdded = true;
    }

    this.page = this.pageById(lastPageId);
    Ink.click(1.3F);
  }

  /** Opens the desk on a page by its id; an unknown or unavailable id gets the first page. */
  public static void openOn(String pageId) {
    lastPageId = pageId;
    Minecraft client = Minecraft.getInstance();
    if (client.gui.screen() instanceof StudioScreen open) {
      open.show(open.pageById(pageId));
    } else {
      client.gui.setScreen(new StudioScreen());
    }
  }

  private Page pageById(String id) {
    for (int i = 0; i < this.pages.size(); i++) {
      if (this.pages.get(i).id.equals(id)) {
        return this.pages.get(i);
      }
    }

    return this.pages.get(0);
  }

  @Override
  protected void init() {
    DemoLines.menuOpen(true);
    this.layout();
    this.page.shown();
  }

  @Override
  protected void repositionElements() {
    this.layout();
  }

  private void layout() {
    // Small GUI scales have pixels to spare, so the dock stops growing and leaves more of the world in view.
    int wanted = Math.min(MOST_DOCK, Math.round(this.height * 0.45F));
    this.dockHeight = Math.max(wanted, Math.min(LEAST_DOCK, this.height * 3 / 5));
    this.dockTop = this.height - this.dockHeight;

    this.tabsLeft = WORDMARK_LEFT + this.font.width(this.wordmark) + TAB_PADDING * 2;
    this.tabX = new int[this.pages.size()];
    this.tabWidth = new int[this.pages.size()];
    int at = 0;
    for (int i = 0; i < this.pages.size(); i++) {
      this.tabX[i] = at;
      this.tabWidth[i] = this.font.width(this.pages.get(i).title) + TAB_PADDING * 2;
      at += this.tabWidth[i];
    }

    this.tabScroller.range(at, Math.max(1, this.width - this.tabsLeft));
    this.layOutPage();
  }

  private void layOutPage() {
    int top = this.dockTop + TAB_HEIGHT + 3;
    int height = this.dockHeight - TAB_HEIGHT - HINT_HEIGHT - 4;
    this.page.layout(this.font, 0, top, this.width, height);
    int index = this.pages.indexOf(this.page);
    this.tabScroller.show(this.tabX[index], this.tabX[index] + this.tabWidth[index]);
  }

  private void show(Page next) {
    if (next == this.page) {
      return;
    }

    this.page.blur();
    this.page.hidden();
    this.page = next;
    lastPageId = next.id;
    this.layOutPage();
    next.shown();
    Ink.click(1.15F);
  }

  // Input

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
    if (this.closingMs != 0L) {
      return true;
    }

    double mx = event.x();
    double my = event.y();
    if (my < this.dockTop) {
      this.page.blur();
      if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.overCard(mx, my)) {
        NowCard.Bounds bounds = NowCard.bounds(this.look);
        this.draggingCard = true;
        this.grabX = mx - bounds.x();
        this.grabY = my - bounds.y();
      }

      return true;
    }

    if (my < this.dockTop + TAB_HEIGHT) {
      Page tab = this.tabAt(mx);
      if (tab != null) {
        this.show(tab);
      }

      return true;
    }

    if (this.overResetAll(mx, my)) {
      this.pressResetAll();
      return true;
    }

    this.draggingPage = this.page.press(mx, my, event.button());
    return true;
  }

  /** Two clicks, as it undoes everything at once and nothing brings that back. */
  private void pressResetAll() {
    if (this.resetArmed()) {
      this.resetEverything();
    } else {
      this.resetArmedUntilMs = System.currentTimeMillis() + RESET_ARMED_MS;
      Ink.click(1.0F);
    }
  }

  /** Puts back every value on every page but the staff one, which is the team's list and not this player's look. */
  public void resetEverything() {
    this.resetArmedUntilMs = 0L;
    this.page.blur();
    for (int i = 0; i < this.pages.size(); i++) {
      this.pages.get(i).resetAll();
    }

    Ink.click(0.8F);
  }

  private boolean resetArmed() {
    return System.currentTimeMillis() < this.resetArmedUntilMs;
  }

  private String resetAllWords() {
    return this.resetArmed() ? this.resetAllSure : this.resetAllWords;
  }

  private int resetAllWidth() {
    return Ink.UNDO_SIZE + RESET_ICON_GAP + this.font.width(this.resetAllWords());
  }

  private boolean overResetAll(double mx, double my) {
    int width = this.resetAllWidth();
    return Ink.inside(mx, my, this.width - WORDMARK_LEFT - width, this.height - HINT_HEIGHT, width, HINT_HEIGHT);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
    if (this.draggingCard) {
      NowCard.moveTo(this.look, event.x() - this.grabX, event.y() - this.grabY);
    } else if (this.draggingPage) {
      this.page.drag(event.x(), event.y());
    }

    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    if (this.draggingCard) {
      this.draggingCard = false;
      Ink.click(1.2F);
    }

    if (this.draggingPage) {
      this.draggingPage = false;
      this.page.release(event.x(), event.y());
    }

    return true;
  }

  @Override
  public boolean mouseScrolled(double mx, double my, double sideways, double upDown) {
    if (my < this.dockTop) {
      return false;
    }

    double amount = upDown != 0.0 ? upDown : -sideways;
    if (my < this.dockTop + TAB_HEIGHT) {
      this.tabScroller.wheel(amount);
      return true;
    }

    return this.page.wheel(mx, my, amount);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (this.closingMs != 0L) {
      return true;
    }

    if (event.isEscape()) {
      if (this.page.hasFocus()) {
        this.page.blur();
      } else {
        this.onClose();
      }

      return true;
    }

    if (this.page.hasFocus()) {
      return this.page.key(event);
    }

    // The press that opened the desk may still be repeating; it must not close it again.
    boolean settled = System.currentTimeMillis() - this.openedMs >= KEY_GUARD_MS;
    if (settled && Env.isMenuKey(event.key())) {
      this.onClose();
      return true;
    }

    return false;
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    return this.closingMs == 0L && this.page.hasFocus() && this.page.chars(event);
  }

  // Life

  @Override
  public void tick() {
    // The check runs in the background and can finish while the desk is open.
    if (!this.staffPageAdded && StaffRoster.mayOpenPanel()) {
      this.pages.add(new StaffPage());
      this.staffPageAdded = true;
      this.layout();
    }

    this.page.tick();
    if (this.closingMs != 0L && System.currentTimeMillis() - this.closingMs >= FALL_MS) {
      super.onClose();
    }
  }

  /** Lets the dock sink first; {@link #tick()} then really closes, away from the middle of a frame. */
  @Override
  public void onClose() {
    if (this.closingMs == 0L) {
      this.closingMs = System.currentTimeMillis();
      this.page.blur();
      Ink.click(0.9F);
    }
  }

  @Override
  public void removed() {
    this.page.hidden();
    DemoLines.menuOpen(false);
    LookStore.save();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  /** Nothing: no blur, no dimming, the world stays as it is. */
  @Override
  public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
  }

  // Drawing

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
    long now = System.currentTimeMillis();
    float frameMs = Math.min(LONGEST_FRAME_MS, now - this.lastFrameMs);
    this.lastFrameMs = now;

    this.drawCardFrame(g, mx, my);

    g.pose().pushMatrix();
    g.pose().translate(0.0F, (float) this.sunk(now));
    g.fill(0, this.dockTop, this.width, this.height, Ink.DOCK);
    Ink.hair(g, 0, this.dockTop, this.width, Ink.HAIR_BRIGHT);
    this.drawTabs(g, mx, my, frameMs);
    Ink.hair(g, 0, this.dockTop + TAB_HEIGHT, this.width, Ink.HAIR);
    this.page.render(g, mx, my, frameMs);
    this.drawHintLine(g, mx, my);
    g.pose().popMatrix();
  }

  /** How far below its place the dock is at this moment: the short rise on opening and sink on closing. */
  private int sunk(long now) {
    float out;
    if (this.closingMs != 0L) {
      out = 1.0F - Math.min(1.0F, (now - this.closingMs) / (float) FALL_MS);
    } else {
      out = Math.min(1.0F, (now - this.openedMs) / (float) RISE_MS);
    }

    float eased = 1.0F - (1.0F - out) * (1.0F - out);
    return Math.round((1.0F - eased) * this.dockHeight);
  }

  private void drawCardFrame(GuiGraphicsExtractor g, int mx, int my) {
    if (!this.cardShown()) {
      return;
    }

    NowCard.Bounds bounds = NowCard.bounds(this.look);
    boolean hot = this.draggingCard || this.overCard(mx, my);
    Ink.dashed(g, bounds.x() - 2, bounds.y() - 2, bounds.w() + 4, bounds.h() + 4, hot ? Ink.ACCENT : Ink.HAIR_BRIGHT);
  }

  private void drawTabs(GuiGraphicsExtractor g, int mx, int my, float frameMs) {
    g.text(this.font, this.wordmark, WORDMARK_LEFT, this.dockTop + 5, Ink.TEXT, false);

    this.tabScroller.update(frameMs);
    int offset = this.tabScroller.offset();
    Page over = my >= this.dockTop && my < this.dockTop + TAB_HEIGHT ? this.tabAt(mx) : null;
    g.enableScissor(this.tabsLeft, this.dockTop, this.width, this.dockTop + TAB_HEIGHT);
    for (int i = 0; i < this.pages.size(); i++) {
      Page tab = this.pages.get(i);
      int left = this.tabsLeft + this.tabX[i] - offset;
      boolean active = tab == this.page;
      int color = active || tab == over ? Ink.TEXT : Ink.DIM;
      g.text(this.font, tab.title, left + TAB_PADDING, this.dockTop + 5, color, false);
      if (active) {
        g.fill(left + TAB_PADDING - 1, this.dockTop + TAB_HEIGHT - 2, left + this.tabWidth[i] - TAB_PADDING + 1,
            this.dockTop + TAB_HEIGHT, Ink.ACCENT);
      } else if (tab == over) {
        Ink.hair(g, left + TAB_PADDING - 1, this.dockTop + TAB_HEIGHT - 1, this.tabWidth[i] - TAB_PADDING * 2 + 2,
            Ink.DIM);
      }
    }

    g.disableScissor();
  }

  private void drawHintLine(GuiGraphicsExtractor g, int mx, int my) {
    int top = this.height - HINT_HEIGHT;
    Ink.hair(g, 0, top, this.width, Ink.HAIR);
    boolean overReset = this.overResetAll(mx, my);
    String hint = overReset ? this.resetAllHint
        : my < this.dockTop && this.overCard(mx, my) ? this.cardHint : this.page.hint(mx, my);
    if (hint == null || hint.isEmpty()) {
      hint = this.idleHint;
    }

    int buttonWidth = this.resetAllWidth();
    int buttonLeft = this.width - WORDMARK_LEFT - buttonWidth;
    g.text(this.font, Ink.fit(this.font, hint, buttonLeft - WORDMARK_LEFT * 2), WORDMARK_LEFT, top + 2, Ink.DIM,
        false);

    int color = this.resetArmed() ? Ink.WRONG : overReset ? Ink.TEXT : Ink.DIM;
    Ink.undo(g, buttonLeft, top + (HINT_HEIGHT - Ink.UNDO_SIZE) / 2, color);
    g.text(this.font, this.resetAllWords(), buttonLeft + Ink.UNDO_SIZE + RESET_ICON_GAP, top + 2, color, false);
  }

  private Page tabAt(double mx) {
    double along = mx - this.tabsLeft + this.tabScroller.offset();
    if (mx < this.tabsLeft) {
      return null;
    }

    for (int i = 0; i < this.pages.size(); i++) {
      if (along >= this.tabX[i] && along < this.tabX[i] + this.tabWidth[i]) {
        return this.pages.get(i);
      }
    }

    return null;
  }

  private boolean cardShown() {
    return this.look.cardOn && !"NONE".equals(this.look.cardStyle);
  }

  private boolean overCard(double mx, double my) {
    if (!this.cardShown()) {
      return false;
    }

    NowCard.Bounds bounds = NowCard.bounds(this.look);
    return Ink.inside(mx, my, bounds.x(), bounds.y(), bounds.w(), bounds.h());
  }
}
