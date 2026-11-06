package net.lyrica.core.staff;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.labymod.api.Laby;
import net.labymod.api.client.entity.player.tag.tags.AbstractNameTag;
import net.labymod.api.client.gfx.pipeline.renderer.text.FontFlags;
import net.labymod.api.client.gfx.pipeline.renderer.text.FontRenderer;
import net.labymod.api.client.gui.textures.TexturesCommon;
import net.labymod.api.client.render.matrix.Stack;
import net.labymod.api.client.render.state.entity.EntitySnapshot;
import net.labymod.api.laby3d.pipeline.RenderStates;
import net.labymod.api.laby3d.pipeline.material.LevelMaterial;
import net.labymod.api.laby3d.pipeline.material.Material;
import net.labymod.api.laby3d.render.queue.SubmissionCollector;
import net.lyrica.api.LyricsBridge;
import net.lyrica.core.LyricaAddon;
import net.lyrica.core.share.SongShare;

/**
 * A team member's title: one small line with the role's words in its colour and the player's own title beside them, as
 * bare words or on a little card, as the Staff page sets it. It stands under the name, unless the role is kept on the song
 * card, and then it stands over the name for as long as there is no song card to carry it: one instance of this for each
 * of those places. Who is on the team goes by the account id, and the bridge answers only with proof of that account; see
 * the design document.
 */
public class BadgeTag extends AbstractNameTag {

  private static final String ADDON_ID = "lyrica";

  private static final float SIZE = 0.78F;
  private static final float CAP_HEIGHT = 7.0F;
  private static final float PADDING_X = 5.0F;
  private static final float PADDING_Y = 3.0F;
  private static final float BARE_PADDING_Y = 1.0F;
  private static final float BESIDE = 4.0F;
  private static final float GAP = 1.5F;
  private static final float MOST_WIDTH = 120.0F;

  private static final float SCALE = 0.55F;
  private static final float MOST_SCALE = 0.9F;
  private static final float HOLD_PIXELS = 1.3F;
  private static final float GONE_BELOW = 0.42F;
  private static final float BACK_ABOVE = 0.47F;

  private static final float REVEAL_S = 0.2F;
  private static final float LONGEST_STEP_S = 0.1F;
  private static final float SMALLEST_RADIUS_PIXELS = 3.0F;
  private static final float WORDS_ALONE_BELOW = 0.8F;
  private static final float RIM_LIFT = 0.2F;
  private static final int TITLE_RGB = 0xF4F5F7;
  private static final int TITLE_ON_LIGHT_RGB = 0x14161A;
  private static final int LIGHT_PANEL = 150;
  private static final float LIGHT_PANEL_SOLID = 0.6F;
  private static final int TEXT_LIGHT = 0xF000F0;
  private static final float FAINTEST_TEXT = 0.03F;
  private static final int ORDER_PANEL = 1;
  private static final int ORDER_TEXT = 2;
  private static final long WORDS_EVERY_MS = 1_000L;
  private static final long FORGET_AFTER_MS = 5_000L;

  private final LyricaAddon addon;
  private final SongShare share;
  private final boolean overName;
  private final Map<UUID, State> badges = new HashMap<>();

  private Material flatMaterial;
  private State badge;

  /** @param overName this is the one over the name, for a role kept on the song card while there is no such card */
  public BadgeTag(LyricaAddon addon, SongShare share, boolean overName) {
    this.addon = addon;
    this.share = share;
    this.overName = overName;
  }

  @Override
  public void begin(EntitySnapshot snapshot) {
    super.begin(snapshot);
    this.badge = null;
    if (!snapshot.has(StaffSnapshot.KEY)) {
      return;
    }

    StaffSnapshot staff = snapshot.get(StaffSnapshot.KEY);
    LyricsBridge bridge = this.addon.bridge();
    if (staff == null || staff.uuid() == null || bridge == null) {
      return;
    }

    UUID player = staff.uuid();
    LyricsBridge.StaffRole role = bridge.roleOf(player);
    if (role == null && !this.badges.containsKey(player)) {
      return;
    }

    if (Laby.references().renderEnvironmentContext().isScreenContext()) {
      return;
    }

    if (!Laby.labyAPI().addonService().isEnabled(ADDON_ID) || !this.addon.configuration().enabled().get()
        || !bridge.staffBadges()) {
      this.badges.clear();
      return;
    }

    if (role != null && !this.belongsHere(player, bridge)) {
      role = null;
    }

    long now = System.currentTimeMillis();
    State state = this.badges.get(player);
    if (role == null) {
      if (state == null) {
        return;
      }

      state.step(now, false);
      if (state.reveal > 0.0F && state.label != null) {
        this.badge = state;
      } else {
        this.badges.remove(player);
      }

      return;
    }

    if (state == null) {
      state = new State();
      this.badges.put(player, state);
    }

    if (this.badges.size() > 64) {
      this.tidy(now);
    }

    LyricsBridge.CardLook look = bridge.cardLookOf(player);
    state.follow(now, role, look == null ? LyricsBridge.CardLook.BADGE : look, bridge);
    state.step(now, !snapshot.isDiscrete() && !state.tooFar);
    if (state.reveal > 0.0F || !snapshot.isDiscrete()) {
      this.badge = state;
    }
  }

  /** Which of the two places it takes right now. */
  private boolean belongsHere(UUID player, LyricsBridge bridge) {
    boolean keptOnCard = bridge.roleOnCard(player);
    return this.overName ? keptOnCard && this.share.cardSong(player, bridge) == null : !keptOnCard;
  }

  private void tidy(long now) {
    Iterator<State> states = this.badges.values().iterator();
    while (states.hasNext()) {
      if (now - states.next().lastSeenMs > FORGET_AFTER_MS) {
        states.remove();
      }
    }
  }

  @Override
  public boolean isVisible() {
    return this.badge != null && this.badge.label != null;
  }

  @Override
  public float getWidth() {
    return this.badge == null ? 0.0F : this.badge.width;
  }

  @Override
  public float getHeight() {
    return this.badge == null ? 0.0F : (this.badge.height + GAP) * this.badge.reveal;
  }

  @Override
  public float getScale() {
    return this.badge == null ? 1.0F : this.badge.scale;
  }

  @Override
  public void render(Stack stack, SubmissionCollector collector, EntitySnapshot snapshot) {
    State state = this.badge;
    if (state == null || state.label == null) {
      return;
    }

    CardGrid grid = CardGrid.of(stack.getProvider().getPose());
    state.seenAt(grid.perUnit / state.scale);
    if (state.reveal <= 0.0F) {
      return;
    }

    float alpha = state.reveal;
    float opacity = state.look.panelOpacity();
    float first = this.overName ? 0.0F : GAP;
    if (opacity > 0.0F) {
      float left = grid.x(0.0F);
      float top = grid.y(first);
      float right = grid.x(state.width);
      float bottom = grid.y(first + state.height);
      float pixel = grid.pixel;
      float wanted = state.look.corners() <= 0.0F ? 0.0F
          : Math.max(SMALLEST_RADIUS_PIXELS, Math.round(state.look.corners() * grid.perUnit)) * pixel;
      float radius = Math.min(wanted, (bottom - top) * 0.5F);
      int body = state.look.panelRgb();
      int rim = state.rimRgb;
      float panelAlpha = opacity * alpha;
      boolean fine = state.look.corners() > LyricsBridge.CardLook.PLAIN.corners();
      collector.order(ORDER_PANEL).submitCustomGeometry(stack, this.flatMaterial(), (pose, out) ->
          CardShapes.panel(out, pose, left, top, right, bottom, radius, pixel, 0.0F, body, rim, panelAlpha, fine));
    }

    if (alpha < FAINTEST_TEXT) {
      return;
    }

    float y = first + state.paddingY;
    float z = grid.depthStep * 2.0F;
    int flags = state.shadow ? FontFlags.DISPLAY_MODE_POLYGON_OFFSET | FontFlags.SHADOW
        : FontFlags.DISPLAY_MODE_POLYGON_OFFSET;
    float x = (state.width - state.textWidth) * 0.5F;
    state.label.submit(stack, collector, grid, x, y, state.labelRgb, alpha, z, TEXT_LIGHT, flags, ORDER_TEXT, state.shadow);
    if (state.title != null) {
      state.title.submit(stack, collector, grid, x + state.label.width + BESIDE, y, state.titleRgb, alpha, z, TEXT_LIGHT,
          flags, ORDER_TEXT, state.shadow);
    }
  }

  private Material flatMaterial() {
    if (this.flatMaterial == null) {
      this.flatMaterial = LevelMaterial.builder(RenderStates.SIMPLE_LEVEL_GEOMETRY)
          .setTexture(0, TexturesCommon.WHITE)
          .build();
    }

    return this.flatMaterial;
  }

  /** One player's badge from frame to frame. Text is built and measured only when it changes. */
  private static final class State {

    LyricsBridge.CardLook look = LyricsBridge.CardLook.BADGE;
    Words label;
    Words title;
    float textWidth;
    float width;
    float height;
    float paddingY;
    float scale = SCALE;
    float reveal;
    int labelRgb;
    int titleRgb = TITLE_RGB;
    int rimRgb;
    boolean shadow = true;
    boolean tooFar;
    long lastSeenMs;

    private String keyLabel;
    private String keyTitle;
    private int keyColor;
    private LyricsBridge.CardLook keyLook;
    private long nextWordsMs;
    private long lastStepMs;
    private float size = 1.0F;
    private boolean waiting;

    void follow(long nowMs, LyricsBridge.StaffRole role, LyricsBridge.CardLook wanted, LyricsBridge bridge) {
      boolean same = !this.waiting && nowMs < this.nextWordsMs && Objects.equals(role.label(), this.keyLabel)
          && Objects.equals(role.title(), this.keyTitle) && role.colorHex() == this.keyColor && wanted == this.keyLook;
      if (same) {
        return;
      }

      this.nextWordsMs = nowMs + WORDS_EVERY_MS;
      this.keyLabel = role.label();
      this.keyTitle = role.title();
      this.keyColor = role.colorHex();
      this.keyLook = wanted;
      this.look = wanted;
      this.size = wanted.size();

      float opacity = wanted.panelOpacity();
      boolean light = CardState.brightness(wanted.panelRgb()) >= LIGHT_PANEL && opacity >= LIGHT_PANEL_SOLID;
      this.shadow = !light && opacity < WORDS_ALONE_BELOW;
      this.labelRgb = light ? CardState.readableOnLight(role.colorHex()) : CardState.readable(role.colorHex());
      this.titleRgb = light ? TITLE_ON_LIGHT_RGB : TITLE_RGB;
      this.rimRgb = wanted.rim() ? CardState.blend(wanted.panelRgb(), light ? 0 : 0xFFFFFF, RIM_LIFT) : wanted.panelRgb();

      FontRenderer font = Laby.references().textRendererProvider().getRenderer().getCurrent();
      String labelWords = LyricsBridge.StaffRole.shown(role.label());
      String titleWords = LyricsBridge.StaffRole.shown(role.title());
      this.label = Words.of(labelWords == null ? "" : labelWords, true, MOST_WIDTH, SIZE, font, bridge);
      this.title = titleWords == null || titleWords.isEmpty() ? null
          : Words.of(titleWords, false, MOST_WIDTH, SIZE, font, bridge);
      this.waiting = this.label.waiting || this.title != null && this.title.waiting;
      this.textWidth = this.label.width + (this.title == null ? 0.0F : BESIDE + this.title.width);
      this.paddingY = opacity > 0.0F ? PADDING_Y : BARE_PADDING_Y;
      this.width = this.textWidth + PADDING_X * 2.0F;
      this.height = CAP_HEIGHT * SIZE + this.paddingY * 2.0F;
    }

    void step(long nowMs, boolean wanted) {
      float seconds = this.lastStepMs == 0L ? 0.0F
          : Math.max(0.0F, Math.min(LONGEST_STEP_S, (nowMs - this.lastStepMs) / 1000.0F));
      this.lastStepMs = nowMs;
      this.lastSeenMs = nowMs;
      float change = seconds / REVEAL_S;
      this.reveal = wanted ? Math.min(1.0F, this.reveal + change) : Math.max(0.0F, this.reveal - change);
    }

    /** How many screen pixels a text pixel gets here decides its size and whether it shows at all. */
    void seenAt(float basePixels) {
      this.tooFar = basePixels < (this.tooFar ? BACK_ABOVE : GONE_BELOW);
      this.scale = Math.max(SCALE * this.size, Math.min(MOST_SCALE * Math.max(1.0F, this.size), HOLD_PIXELS / basePixels));
    }
  }
}
