package net.lyrica.core.staff;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.entity.player.tag.tags.AbstractNameTag;
import net.labymod.api.client.gfx.pipeline.renderer.text.FontFlags;
import net.labymod.api.client.gfx.pipeline.renderer.text.FontRenderer;
import net.labymod.api.client.gui.textures.TexturesCommon;
import net.labymod.api.client.render.matrix.Stack;
import net.labymod.api.client.render.state.entity.EntitySnapshot;
import net.labymod.api.client.resources.ResourceLocation;
import net.labymod.api.laby3d.pipeline.RenderStates;
import net.labymod.api.laby3d.pipeline.material.LevelMaterial;
import net.labymod.api.laby3d.pipeline.material.Material;
import net.labymod.api.laby3d.render.queue.SubmissionCollector;
import net.labymod.laby3d.api.pipeline.RenderState;
import net.labymod.laby3d.api.resource.AssetId;
import net.lyrica.api.LyricsBridge;
import net.lyrica.core.LyricaAddon;
import net.lyrica.core.share.SongShare;

/**
 * The song over a player's name: a little player with the cover as its whole left end, the title in bold over the artist,
 * the time, and along the bottom that song's own wave, which fills up as the song goes on and lies down when it pauses.
 * It has nothing to do with being on the team; a team member's title is {@link BadgeTag}, under the name. Anybody can
 * have this card: one's own comes from what this PC plays, somebody else's from what they chose to share. Far away it
 * gives way to the title alone on one line, and then to nothing. The player picks whether cards stand over the name or
 * under it: one instance of this for each of those places, and the one not picked lets its card fade away.
 */
public class SongTag extends AbstractNameTag {

  private static final String ADDON_ID = "lyrical";

  private static final float GAP_BELOW = 3.0F;
  private static final float SMALLEST_RADIUS_PIXELS = 4.0F;
  // Clouds and water are drawn after the card. A panel that writes depth cuts them away, which shows as a clean hole in
  // the clouds once the panel is this faint; below it the panel leaves the depth buffer alone
  private static final float THIN_PANEL_BELOW = 0.35F;
  // The cover's right corners round off from here down, all the way at no panel
  private static final float COVER_ALONE_BELOW = 0.6F;
  private static final int MARK_SHADOW_RGB = 0x000000;
  private static final float MARK_SHADOW_ALPHA = 0.55F;
  private static final int TEXT_LIGHT = 0xF000F0;
  // Text so faint is what the font takes for not having an alpha at all, and draws solid
  private static final float FAINTEST_TEXT = 0.03F;
  // A paused song's cover steps back a little, so that the card reads as paused at a glance
  private static final float PAUSED_COVER = 0.45F;

  // The wave, in text pixels
  private static final float WAVE_PITCH = 2.25F;
  private static final float WAVE_TICK_SHARE = 0.55F;
  private static final float PAUSE_BAR = 1.25F;
  // A digit's advance runs on past its ink by about this much, in text pixels at size one. Taken off so that the time's
  // right edge and the wave's are one margin
  private static final float DIGIT_SIDE = 0.6F;

  private static final int ORDER_PANEL = 1;
  private static final int ORDER_IMAGE = 2;
  private static final int ORDER_MARKS = 3;
  private static final int ORDER_TEXT = 4;

  private static final long FORGET_AFTER_MS = 5_000L;
  private static final long TIDY_EVERY_MS = 2_000L;

  private final LyricaAddon addon;
  private final SongShare share;
  private final boolean underName;
  private final Map<UUID, CardState> cards = new HashMap<>();
  private final Map<ResourceLocation, Material> imageMaterials = new HashMap<>();

  private Material flatMaterial;
  private Material thinMaterial;
  private CardState card;
  private long nextTidyMs;

  /** @param underName this is the one under the name */
  public SongTag(LyricaAddon addon, SongShare share, boolean underName) {
    this.addon = addon;
    this.share = share;
    this.underName = underName;
  }

  @Override
  public void begin(EntitySnapshot snapshot) {
    super.begin(snapshot);
    this.card = null;
    if (!snapshot.has(StaffSnapshot.KEY)) {
      return;
    }

    StaffSnapshot staff = snapshot.get(StaffSnapshot.KEY);
    LyricsBridge bridge = this.addon.bridge();
    if (staff == null || staff.uuid() == null || bridge == null) {
      return;
    }

    UUID player = staff.uuid();
    // A menu's preview of the player, in the GUI pass: no card on it, and the cards in the world behind it stay as they are
    if (Laby.references().renderEnvironmentContext().isScreenContext()) {
      return;
    }

    if (!this.switchedOn()) {
      // Coming back on starts every card anew
      this.cards.clear();
      return;
    }

    LyricsBridge.LocalSong song = bridge.songCardUnderName() == this.underName ? this.share.cardSong(player, bridge) : null;
    long now = System.currentTimeMillis();
    CardState state = this.cards.get(player);
    if (state != null && now - state.lastSeenMs > FORGET_AFTER_MS) {
      // Not seen for a while, so whatever comes now starts anew
      this.cards.remove(player);
      state = null;
    }

    if (song == null) {
      this.foldAway(player, state, now);
      return;
    }

    if (state == null) {
      state = new CardState();
      this.cards.put(player, state);
    }

    FontRenderer font = Laby.references().textRendererProvider().getRenderer().getCurrent();
    // A team member can keep their role on this card rather than under the name
    LyricsBridge.StaffRole role = bridge.staffBadges() && bridge.roleOnCard(player) ? bridge.roleOf(player) : null;
    int accent = bridge.songCardAccent(song, this.share.thumbOf(player));
    state.step(now, accent, role, bridge.songCardLook(), song, snapshot.isDiscrete(), font, bridge);
    this.tidy(now);
    // Also when it has gone with distance, because only drawing finds out how far away it is. It then takes no room
    if (state.reveal > 0.0F || !snapshot.isDiscrete()) {
      this.card = state;
    }
  }

  /** The song has gone: the card hides as any card does and is then forgotten, so that it comes back with a fade too. */
  private void foldAway(UUID player, CardState state, long now) {
    if (state == null) {
      return;
    }

    state.fold(now);
    if (state.reveal > 0.0F && state.shown != null) {
      this.card = state;
    } else {
      this.cards.remove(player);
    }
  }

  /** Laby's Disable and the addon's own switch. */
  private boolean switchedOn() {
    return Laby.labyAPI().addonService().isEnabled(ADDON_ID) && this.addon.configuration().enabled().get();
  }

  private void tidy(long now) {
    if (now < this.nextTidyMs) {
      return;
    }

    this.nextTidyMs = now + TIDY_EVERY_MS;
    Iterator<CardState> states = this.cards.values().iterator();
    while (states.hasNext()) {
      if (now - states.next().lastSeenMs > FORGET_AFTER_MS) {
        states.remove();
      }
    }
  }

  @Override
  public boolean isVisible() {
    return this.card != null;
  }

  @Override
  public float getWidth() {
    return this.card == null ? 0.0F : this.card.width;
  }

  @Override
  public float getHeight() {
    return this.card == null ? 0.0F : (this.card.height + GAP_BELOW) * this.card.unfolded();
  }

  @Override
  public float getScale() {
    return this.card == null ? 1.0F : this.card.scale;
  }

  /**
   * The geometry runs later, when LabyMod works through what was submitted, by which time this tag is on another
   * player. So everything a piece of it needs goes in by value. Also, the collector forgets its order after each
   * submission: anything submitted without asking for an order again is drawn first, under the panel, and then the
   * panel fails the depth test at its pixels and the world shows through there.
   */
  @Override
  public void render(Stack stack, SubmissionCollector collector, EntitySnapshot snapshot) {
    CardState state = this.card;
    if (state == null || state.shown == null) {
      return;
    }

    if (!this.underName) {
      this.draw(stack, collector, state);
      return;
    }

    // Its gap belongs on the side of the name, which is above it here
    stack.push();
    stack.translate(0.0F, GAP_BELOW, 0.0F);
    this.draw(stack, collector, state);
    stack.pop();
  }

  private void draw(Stack stack, SubmissionCollector collector, CardState state) {
    CardGrid grid = CardGrid.of(stack.getProvider().getPose());
    state.basePixels = grid.perUnit / state.scale;
    if (state.panelAlpha() <= 0.0F) {
      return;
    }

    float depth = grid.depthStep;
    this.submitPanel(stack, collector, state, grid);

    float wordsAlpha = state.wordsAlpha();
    if (wordsAlpha <= 0.0F) {
      return;
    }

    CardState.Face face = state.shown;
    if (face.cover() != null) {
      float stepBack = 1.0F - (1.0F - PAUSED_COVER) * state.paused;
      this.submitCover(stack, collector, state, grid, wordsAlpha * state.contentAlpha, stepBack, depth);
    }

    if (face.wave()) {
      this.submitWave(stack, collector, state, grid, wordsAlpha * state.contentAlpha, depth * 2.0F);
    }

    this.submitText(stack, collector, state, grid, wordsAlpha, depth * 3.0F);
  }

  private void submitPanel(Stack stack, SubmissionCollector collector, CardState state, CardGrid grid) {
    float opacity = state.look.panelOpacity();
    if (opacity <= 0.0F) {
      return;
    }

    float left = grid.x(0.0F);
    float top = grid.y(0.0F);
    float right = grid.x(state.width);
    float pixel = grid.pixel;
    float bottom = Math.max(top + pixel * 2.0F, grid.y(state.height * state.unfolded()));
    float radius = Math.min(radius(grid, state.look.corners()), Math.min(bottom - top, right - left) * 0.5F);
    float alpha = opacity * state.panelAlpha();
    int bodyRgb = state.look.panelRgb();
    int rimRgb = state.rimRgb;
    boolean fine = state.look.corners() > LyricsBridge.CardLook.PLAIN.corners();
    Material material = opacity < THIN_PANEL_BELOW ? this.thinMaterial() : this.flatMaterial();
    collector.order(ORDER_PANEL).submitCustomGeometry(stack, material, (pose, out) ->
        CardShapes.panel(out, pose, left, top, right, bottom, radius, pixel, 0.0F, bodyRgb, rimRgb, alpha, fine));
  }

  private static float radius(CardGrid grid, float corners) {
    if (corners <= 0.0F) {
      return 0.0F;
    }

    float smallest = SMALLEST_RADIUS_PIXELS * Math.min(1.0F, corners / LyricsBridge.CardLook.PLAIN.corners());
    return Math.max(smallest, Math.round(corners * grid.perUnit)) * grid.pixel;
  }

  /** The card's whole left end, one pixel inside the rim, its left corners following the card's. */
  private void submitCover(Stack stack, SubmissionCollector collector, CardState state, CardGrid grid, float alpha,
      float brightness, float depth) {
    if (alpha <= 0.0F) {
      return;
    }

    float pixel = grid.pixel;
    float left = grid.x(0.0F) + pixel;
    float top = grid.y(0.0F) + pixel;
    float bottom = grid.y(state.height) - pixel;
    float side = bottom - top;
    float right = left + side;
    // The same centre as the panel's corners, so that the rim stays one even pixel all the way round
    float panelSide = side + pixel * 2.0F;
    float inner = Math.max(0.0F, Math.min(radius(grid, state.look.corners()), panelSide * 0.5F) - pixel);
    float rightInner = inner * Math.max(0.0F, 1.0F - state.look.panelOpacity() / COVER_ALONE_BELOW);
    boolean fine = state.look.corners() > LyricsBridge.CardLook.PLAIN.corners();
    ResourceLocation image = state.shown.cover().forPixels(side * grid.perUnit);
    int tint = Math.round(brightness * 255.0F);
    int rgb = tint << 16 | tint << 8 | tint;
    collector.order(ORDER_IMAGE).submitCustomGeometry(stack, this.imageMaterial(image), (pose, out) ->
        CardShapes.image(out, pose, left, top, right, bottom, inner, rightInner, pixel, depth, rgb, alpha, fine));
  }

  /**
   * The song's wave along the bottom of the text column: played ticks in the accent colour, the rest in dark grey, the
   * tick at the playhead between the two so that it moves smoothly. Whole pixels, so its edges are hard at any distance.
   */
  private void submitWave(Stack stack, SubmissionCollector collector, CardState state, CardGrid grid, float alpha,
      float z) {
    if (alpha <= 0.0F) {
      return;
    }

    float pixel = grid.pixel;
    float left = grid.x(state.textLeft);
    float right = grid.x(state.width - CardState.PADDING_X);
    float floor = grid.y(state.height - CardState.PADDING_Y);
    int across = Math.round((right - left) * grid.perUnit);
    int pitch = Math.max(3, Math.round(WAVE_PITCH * grid.perUnit));
    int tick = Math.max(1, (int) (pitch * WAVE_TICK_SHARE));
    int ticks = Math.min(CardState.WAVE_COLUMNS, Math.max(2, (across + pitch - tick) / pitch));
    float tallest = CardState.WAVE_HEIGHT * grid.perUnit;
    float[] wave = state.wave;
    float playhead = state.shown.hasProgress() ? state.progress * ticks : 0.0F;
    int played = state.accentRgb;
    int empty = state.waveEmptyRgb;
    boolean shadow = state.wordsShadow;
    collector.order(ORDER_MARKS).submitCustomGeometry(stack, this.flatMaterial(), (pose, out) -> {
      for (int i = 0; shadow && i < ticks; i++) {
        float x = left + Math.round(i * (float) (across - tick) / (ticks - 1)) * pixel + pixel;
        int high = Math.max(1, Math.round(wave[i * CardState.WAVE_COLUMNS / ticks] * tallest));
        CardShapes.rectangle(out, pose, x, floor - high * pixel + pixel, x + tick * pixel, floor + pixel, z,
            MARK_SHADOW_RGB, alpha * MARK_SHADOW_ALPHA);
      }

      for (int i = 0; i < ticks; i++) {
        int at = Math.round(i * (float) (across - tick) / (ticks - 1));
        float x = left + at * pixel;
        int high = Math.max(1, Math.round(wave[i * CardState.WAVE_COLUMNS / ticks] * tallest));
        int rgb = CardState.blend(empty, played, Math.max(0.0F, Math.min(1.0F, playhead - i)));
        CardShapes.rectangle(out, pose, x, floor - high * pixel, x + tick * pixel, floor, z, rgb, alpha);
      }
    });
  }

  private void submitText(Stack stack, SubmissionCollector collector, CardState state, CardGrid grid, float wordsAlpha,
      float z) {
    CardState.Face face = state.shown;
    float contentAlpha = state.contentAlpha * wordsAlpha;
    float top = face.detail() == CardState.Detail.LINE ? CardState.LINE_PADDING_Y : CardState.PADDING_Y;
    int flags = state.wordsShadow ? FontFlags.DISPLAY_MODE_POLYGON_OFFSET | FontFlags.SHADOW
        : FontFlags.DISPLAY_MODE_POLYGON_OFFSET;
    int soft = state.softRgb;
    boolean shadow = state.wordsShadow;

    float capTime = CardState.CAP_HEIGHT * CardState.TIME_SIZE;
    float timeTop = face.label() != null ? top : top + (CardState.CAP_HEIGHT - capTime) * 0.5F;
    float y = top;
    if (face.label() != null) {
      this.words(stack, collector, grid, face.label(), state.textLeft, y, face.labelColor(), contentAlpha, z, flags,
          shadow);
      y += capTime + CardState.LABEL_GAP;
    }

    this.words(stack, collector, grid, face.first(), state.textLeft, y, state.titleRgb, contentAlpha, z, flags, shadow);
    y += CardState.CAP_HEIGHT + CardState.ARTIST_GAP;

    float end = state.width - CardState.PADDING_X;
    if (face.paused() && face.word() != null) {
      // Says so in words where the time was, in the card's colour, with the two bars before it
      float wordLeft = end - face.wordWidth();
      this.submitLine(stack, collector, grid, face.word(), wordLeft, timeTop, CardState.TIME_SIZE, state.accentRgb,
          contentAlpha, z, flags);
      this.submitPauseMark(stack, collector, grid, state, wordLeft, timeTop, capTime, state.accentRgb, contentAlpha, z);
    } else if (face.showTime() && state.time != null) {
      float timeLeft = end - (state.timeWidth - DIGIT_SIDE) * CardState.TIME_SIZE;
      this.submitLine(stack, collector, grid, state.time, timeLeft, timeTop, CardState.TIME_SIZE, soft, contentAlpha, z,
          flags);
    }

    if (face.second() != null) {
      this.words(stack, collector, grid, face.second(), state.textLeft, y, soft, contentAlpha, z, flags, shadow);
    }
  }

  private void words(Stack stack, SubmissionCollector collector, CardGrid grid, Words words, float x, float y, int rgb,
      float alpha, float z, int flags, boolean shadow) {
    if (alpha >= FAINTEST_TEXT) {
      words.submit(stack, collector, grid, x, y, rgb, alpha, z, TEXT_LIGHT, flags, ORDER_TEXT, shadow);
    }
  }

  /** Each line starts on a whole pixel, so that the same text looks the same from frame to frame. */
  private void submitLine(Stack stack, SubmissionCollector collector, CardGrid grid, Component text, float x, float y,
      float size, int rgb, float alpha, float z, int flags) {
    if (alpha < FAINTEST_TEXT) {
      return;
    }

    stack.push();
    stack.translate(grid.x(x), grid.y(y), z);
    stack.scale(size, size, 1.0F);
    collector.order(ORDER_TEXT).submitComponent(stack, text, 0.0F, 0.0F, Math.round(alpha * 255.0F) << 24 | rgb,
        TEXT_LIGHT, 0, 1.0F, flags);
    stack.pop();
  }

  /** Two small bars just before the word. */
  private void submitPauseMark(Stack stack, SubmissionCollector collector, CardGrid grid, CardState state,
      float wordLeft, float top, float high, int rgb, float alpha, float z) {
    if (alpha <= 0.0F) {
      return;
    }

    float pixel = grid.pixel;
    float bar = grid.size(PAUSE_BAR);
    float right = grid.x(wordLeft - PAUSE_BAR * 1.5F);
    float y0 = grid.y(top);
    float y1 = grid.y(top + high);
    boolean shadow = state.wordsShadow;
    collector.order(ORDER_TEXT).submitCustomGeometry(stack, this.flatMaterial(), (pose, out) -> {
      if (shadow) {
        float shadowAlpha = alpha * MARK_SHADOW_ALPHA;
        CardShapes.rectangle(out, pose, right - bar + pixel, y0 + pixel, right + pixel, y1 + pixel, z, MARK_SHADOW_RGB,
            shadowAlpha);
        CardShapes.rectangle(out, pose, right - bar * 3.0F + pixel, y0 + pixel, right - bar * 2.0F + pixel, y1 + pixel,
            z, MARK_SHADOW_RGB, shadowAlpha);
      }

      CardShapes.rectangle(out, pose, right - bar, y0, right, y1, z, rgb, alpha);
      CardShapes.rectangle(out, pose, right - bar * 3.0F, y0, right - bar * 2.0F, y1, z, rgb, alpha);
    });
  }

  private Material flatMaterial() {
    if (this.flatMaterial == null) {
      this.flatMaterial = LevelMaterial.builder(RenderStates.SIMPLE_LEVEL_GEOMETRY)
          .setTexture(0, TexturesCommon.WHITE)
          .build();
    }

    return this.flatMaterial;
  }

  /** The same as the flat material, except that it leaves the depth buffer alone; see {@link #THIN_PANEL_BELOW}. */
  private Material thinMaterial() {
    if (this.thinMaterial == null) {
      RenderState thin = RenderState.builder(RenderStates.SIMPLE_LEVEL_GEOMETRY.toSnippet())
          .setId(AssetId.of(ADDON_ID, "card_thin_panel"))
          .setWriteDepth(false)
          .build();
      this.thinMaterial = LevelMaterial.builder(thin).setTexture(0, TexturesCommon.WHITE).build();
    }

    return this.thinMaterial;
  }

  private Material imageMaterial(ResourceLocation image) {
    Material material = this.imageMaterials.get(image);
    if (material == null) {
      material = LevelMaterial.builder(RenderStates.SIMPLE_LEVEL_GEOMETRY).setTexture(0, image).build();
      this.imageMaterials.put(image, material);
    }

    return material;
  }
}
