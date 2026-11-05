package net.splyrics.v1_21_11.menu;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.splyrics.v1_21_11.look.Fonts;
import net.splyrics.v1_21_11.look.Look;
import net.splyrics.v1_21_11.look.Palette;
import net.splyrics.v1_21_11.look.Presets;
import net.splyrics.v1_21_11.menu.controls.Ink;
import net.splyrics.v1_21_11.menu.controls.Scroller;
import net.splyrics.v1_21_11.song.Playback;
import net.splyrics.v1_21_11.stage.ComposedLine;
import net.splyrics.v1_21_11.stage.FlatDrawer;
import net.splyrics.v1_21_11.stage.LineComposer;

/** The row of ready-made looks: each tile says its own name in its own font, colours and motion, on and on. */
final class PresetTiles {

  private static final int MARGIN = 8;
  private static final int GAP = 2;
  private static final int LEAST_TILE = 84;
  private static final int MOST_TILE = 136;
  private static final int BAR_ROOM = 4;
  private static final long SUNG_MS = 3_400L;
  private static final long REST_MS = 500L;
  private static final long STAGGER_MS = 170L;
  private static final float MOST_SCALE = 1.6F;
  private static final int WASH = 0x0FF6F1E3;
  private static final int WASH_OVER = 0x1FF6F1E3;
  // Two layers at most: every tile moves on every frame, and all of them together must stay cheap.
  private static final float TILE_DEPTH = 0.67F;

  private static final class Tile {

    private final Presets.Preset preset;
    private final Look look;
    private final Style style;
    private final String name;
    private final LineComposer composer = new LineComposer();
    private long startMs;

    private Tile(Presets.Preset preset, long startMs) {
      this.preset = preset;
      this.look = preset.look();
      this.look.depth = Math.min(this.look.depth, TILE_DEPTH);
      this.look.outline = false;
      this.style = Fonts.style(this.look.font, this.look.bold);
      this.name = preset.name();
      this.startMs = startMs;
    }
  }

  private final List<Tile> tiles = new ArrayList<>();
  private final Scroller scroller = new Scroller();

  private int left;
  private int top;
  private int width;
  private int height;
  private int tileWidth = LEAST_TILE;

  PresetTiles() {
    long now = System.currentTimeMillis();
    List<Presets.Preset> all = Presets.all();
    for (int i = 0; i < all.size(); i++) {
      this.tiles.add(new Tile(all.get(i), now + i * STAGGER_MS));
    }
  }

  void layout(int left, int top, int width, int height) {
    this.left = left;
    this.top = top;
    this.width = width;
    this.height = height;
    this.tileWidth = Math.max(LEAST_TILE, Math.min(MOST_TILE, (height - BAR_ROOM) * 5 / 2));
    int content = MARGIN * 2 + this.tiles.size() * (this.tileWidth + GAP) - GAP;
    this.scroller.range(content, width);
  }

  void render(GuiGraphics g, Font font, int mx, int my, float frameMs, String wornId) {
    this.scroller.update(frameMs);
    int offset = this.scroller.offset();
    int boxHeight = this.height - BAR_ROOM;
    long now = System.currentTimeMillis();
    int[] album = Palette.albumColors(Playback.get().current());
    int over = this.tileAt(mx, my);
    for (int i = 0; i < this.tiles.size(); i++) {
      int x = this.left + MARGIN + i * (this.tileWidth + GAP) - offset;
      if (x + this.tileWidth < this.left || x > this.left + this.width) {
        continue;
      }

      Tile tile = this.tiles.get(i);
      boolean worn = tile.preset.id().equals(wornId);
      int shownLeft = Math.max(x, this.left);
      int shownRight = Math.min(x + this.tileWidth, this.left + this.width);
      g.enableScissor(shownLeft, this.top, shownRight, this.top + boxHeight);
      g.fill(x, this.top, x + this.tileWidth, this.top + boxHeight, i == over ? WASH_OVER : WASH);
      this.drawName(g, font, tile, x, boxHeight, now, i, album);
      if (worn) {
        g.fill(x, this.top + boxHeight - 2, x + this.tileWidth, this.top + boxHeight, Ink.ACCENT);
      }

      g.disableScissor();
    }

    this.scroller.drawBar(g, this.left + MARGIN, this.top + this.height - 2, this.barWidth());
  }

  private void drawName(GuiGraphics g, Font font, Tile tile, int x, int boxHeight, long now, int seed, int[] album) {
    long elapsed = now - tile.startMs;
    if (elapsed < 0L) {
      return;
    }

    ComposedLine line = tile.composer.compose(tile.look, font, tile.style, tile.name, elapsed, SUNG_MS, null, now, seed,
        album);
    if (line.finished) {
      tile.startMs = now + REST_MS;
      return;
    }

    float room = this.tileWidth - 10.0F;
    float scale = Math.min(room / Math.max(1.0F, line.width), (boxHeight - 8.0F) / Math.max(1.0F, line.height));
    scale = Math.min(MOST_SCALE, scale);
    float lineTop = this.top + (boxHeight - line.height * scale) * 0.5F;
    FlatDrawer.draw(g, font, tile.style, line, tile.look, x + this.tileWidth * 0.5F, lineTop, scale);
  }

  /** The preset under the pointer, or null. */
  Presets.Preset presetAt(double mx, double my) {
    int index = this.tileAt(mx, my);
    return index < 0 ? null : this.tiles.get(index).preset;
  }

  boolean contains(double mx, double my) {
    return Ink.inside(mx, my, this.left, this.top, this.width, this.height);
  }

  /** @return true when the press took hold of the scroll line */
  boolean pressBar(double mx, double my) {
    if (this.contains(mx, my) && my >= this.top + this.height - BAR_ROOM && this.scroller.max() > 0) {
      this.scroller.pressBar(mx, this.left + MARGIN, this.barWidth());
      return true;
    }

    return false;
  }

  void drag(double mx) {
    this.scroller.dragBar(mx, this.left + MARGIN, this.barWidth());
  }

  void release() {
    this.scroller.releaseBar();
  }

  void wheel(double amount) {
    this.scroller.wheel(amount);
  }

  private int tileAt(double mx, double my) {
    if (!this.contains(mx, my) || my >= this.top + this.height - BAR_ROOM) {
      return -1;
    }

    double along = mx - this.left - MARGIN + this.scroller.offset();
    if (along < 0.0) {
      return -1;
    }

    int stride = this.tileWidth + GAP;
    int index = (int) (along / stride);
    boolean onTile = along - index * stride < this.tileWidth;
    return onTile && index < this.tiles.size() ? index : -1;
  }

  private int barWidth() {
    return this.width - MARGIN * 2;
  }
}
