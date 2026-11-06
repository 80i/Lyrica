package net.lyrica.v26_1_1.stage;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import net.lyrica.v26_1_1.look.Fonts;
import net.lyrica.v26_1_1.look.Look;
import net.lyrica.v26_1_1.look.LookStore;
import net.lyrica.v26_1_1.look.Palette;
import net.lyrica.v26_1_1.song.NowPlaying;
import net.lyrica.v26_1_1.song.Playback;
import net.lyrica.v26_1_1.words.DemoLines;
import net.lyrica.v26_1_1.words.LyricsLibrary;
import net.lyrica.v26_1_1.words.TimedLine;
import net.lyrica.v26_1_1.words.TimedLyrics;

/**
 * The lyrics as objects in the world. Every line gets its own spot once and keeps it: nothing here follows the player
 * unless the player asked for that. The leaving line finishes its exit where it stands while the next one enters at
 * its own spot, each with its own composer and its own clock.
 */
public final class WorldStage {

  private static final WorldStage STAGE = new WorldStage();

  private static final float LONGEST_STEP_SECONDS = 0.1F;
  private static final double JUMP_BLOCKS = 64.0;
  private static final float MASTER_FADE_SECONDS = 0.35F;
  private static final long SEEK_BACK_MS = 500L;
  private static final int MOST_LINES = 6;
  private static final int MOST_LINES_TO_READY = 2_000;
  private static final int MOST_SUNG_TOGETHER = 4;

  private static final float ROW_SIZE = 0.58F;
  private static final float ROW_GAP = 1.5F;
  private static final float NEXT_ALPHA = 0.5F;
  private static final float EARLIER_ALPHA = 0.45F;
  private static final float BACKGROUND_ALPHA = 0.85F;
  private static final float DUET_WRAP = 0.62F;
  private static final float ROW_SHIFT_RATE = 6.0F;

  private static final int LIGHT_POLL_FRAMES = 12;
  private static final int LEAST_BLOCK_LIGHT = 4;
  // Letters that glow carry some of their own light into the dark.
  private static final int SOFT_GLOW_BLOCK_LIGHT = 9;
  private static final int BRIGHT_GLOW_BLOCK_LIGHT = 15;

  private static final float FLIP_MARGIN = -0.05F;
  private static final float TURN_START = (float) Math.toRadians(40.0);
  private static final float TURN_END = (float) Math.toRadians(1.5);
  private static final float TURN_RATE = 2.2F;
  private static final float BEND_RETURN_SECONDS = 0.45F;

  private static final float CARRY_ABOVE_SPEED = 8.0F;
  private static final float CARRY_RATE = 3.0F;
  private static final float SPEED_SMOOTHING = 5.0F;
  private static final float RESETTLE_RATE = 4.0F;
  private static final double SETTLED_BLOCKS = 0.002;
  private static final float CARRY_GROUND_REACH = 24.0F;
  private static final float CARRY_GROUND_FROM = 8.0F;
  private static final float CARRIED_CLEARANCE = 0.03F;
  private static final int CARRY_GROUND_POLL_FRAMES = 8;

  private static final long BREAK_LEAST_MS = 8_000L;
  private static final long BREAK_AFTER_LINE_MS = 900L;
  private static final long BREAK_FADE_MS = 500L;
  private static final long ONE_MORE_DOT_MS = 8_000L;
  private static final int FEWEST_DOTS = 3;
  private static final int MOST_DOTS = 5;
  private static final float DOT_ROW_HEIGHT = 9.0F;
  private static final float DOT_ROW_WIDTH_PER_DOT = 10.0F;

  private final List<LineSlot> live = new ArrayList<>();
  private final List<LineSlot> pool = new ArrayList<>();
  private final Placement placement = new Placement();
  private final Look rowLook = new Look();
  private final Look backgroundLook = new Look();
  private final Look duetLook = new Look();
  private final BlockPos.MutableBlockPos lightAt = new BlockPos.MutableBlockPos();

  private int[] drawOrder = new int[MOST_LINES];
  private double[] drawDistance = new double[MOST_LINES];

  private TimedLyrics askedLyrics;
  private VectorFace askedFace;
  private long lastDrawNanos;

  private StageBuffers buffers;
  private SlotDrawer drawer;
  private LineSlot breakSlot;
  private TimedLine breakBefore;

  private ClientLevel lastLevel;
  private long lastNanos;
  private boolean hasLastCamera;
  private double lastCameraX;
  private double lastCameraY;
  private double lastCameraZ;
  private float speedX;
  private float speedY;
  private float speedZ;
  private float carry;
  private int frameCount;

  private NowPlaying keySong;
  private String key;
  private String shownKey;
  private TimedLyrics shownLyrics;
  private float master;

  private boolean hasPlaceKey;
  private String placedPlace;
  private float placedDistance;
  private float placedHeightAdd;

  private WorldStage() {
  }

  /** Draws the lines. The pose starts at the camera in world axes, and the camera's rotation is already applied. */
  public static void render(PoseStack pose, Camera camera) {
    long began = System.nanoTime();
    STAGE.frame(pose, camera);
    STAGE.lastDrawNanos = System.nanoTime() - began;
  }

  /** How long the last frame's lyrics took, buffers and draw calls included. */
  public static long lastDrawNanos() {
    return STAGE.lastDrawNanos;
  }

  /** For the development test: makes every letter use this detail level, -1 for the normal choice. */
  public static void forceDetail(int detail) {
    SlotDrawer.forcedDetail = detail;
  }

  /** For the development test: how far the outlines' advances lie from the font's own over these characters. */
  public static String advanceReport(Look look, String characters) {
    VectorFace face = VectorFace.of(look);
    if (face == null) {
      return look.font + " has no outlines";
    }

    Font font = Minecraft.getInstance().font;
    Style style = Fonts.style(look.font, look.bold);
    float worst = 0.0F;
    float sum = 0.0F;
    int count = 0;
    String worstAt = "";
    for (int i = 0; i < characters.length(); i++) {
      String one = characters.substring(i, i + 1);
      float mesh = face.advanceOf(one);
      if (mesh < 0.0F) {
        continue;
      }

      float game = font.getSplitter().stringWidth(FormattedCharSequence.forward(one, style));
      float off = Math.abs(mesh - game);
      sum += off;
      count++;
      if (off > worst) {
        worst = off;
        worstAt = one;
      }
    }

    return look.font + (look.bold ? " bold" : "") + ": mean off " + sum / Math.max(1, count) + ", worst " + worst
        + " at " + worstAt;
  }

  /** How many letter vertices the last frame wrote. */
  public static int lastVertices() {
    return STAGE.drawer == null ? 0 : STAGE.drawer.vertices();
  }

  /** Takes everything off the stage. The next line finds a new spot. */
  public static void reset() {
    STAGE.clear();
    STAGE.master = 0.0F;
    STAGE.shownKey = null;
    STAGE.shownLyrics = null;
    STAGE.keySong = null;
    STAGE.key = null;
    STAGE.askedFace = null;
    STAGE.askedLyrics = null;
    STAGE.forgetWorld();
  }

  /** The game has no world any more. Without this the stage would hold on to the old one and all it refers to. */
  public static void leftWorld() {
    if (STAGE.lastLevel != null || STAGE.hasLastCamera) {
      STAGE.clear();
      STAGE.forgetWorld();
    }
  }

  private void forgetWorld() {
    this.lastLevel = null;
    this.hasLastCamera = false;
  }

  private void clear() {
    for (int i = 0; i < this.live.size(); i++) {
      LineSlot slot = this.live.get(i);
      slot.release();
      this.pool.add(slot);
    }

    this.live.clear();
    this.breakBefore = null;
    this.placement.forget();
  }

  private void frame(PoseStack pose, Camera camera) {
    Minecraft client = Minecraft.getInstance();
    ClientLevel level = client.level;
    float step = this.stepClock();
    if (this.drawer != null) {
      this.drawer.noVertices();
    }

    this.trackCamera(camera, level, step);
    this.frameCount++;

    Look look = LookStore.get();
    NowPlaying song = Playback.get().current();
    TimedLyrics lyrics = LyricsLibrary.get().current();
    boolean demo = DemoLines.active(song, lyrics);
    if (demo) {
      song = DemoLines.song();
      lyrics = DemoLines.lyrics();
    }

    if (song != this.keySong) {
      this.keySong = song;
      this.key = song == null ? null : song.key();
    }

    if (!Objects.equals(this.key, this.shownKey) || lyrics != this.shownLyrics) {
      this.clear();
      this.shownKey = this.key;
      this.shownLyrics = lyrics;
    }

    if (song == null || lyrics == null || lyrics.isEmpty() || "SCREEN".equals(look.place)) {
      this.clear();
      return;
    }

    boolean wanted = demo || (look.showLyrics && (song.playing() || !look.hideWhenPaused));
    this.master = approach(this.master, wanted ? 1.0F : 0.0F, step / MASTER_FADE_SECONDS);
    if (!wanted && this.master <= 0.0F) {
      this.clear();
      return;
    }

    this.checkPlaceChange(look);

    VectorFace face = VectorFace.of(look);
    this.askForGlyphs(face, lyrics);

    long position = song.positionNow(look.offsetFor(this.key));
    int index = lyrics.indexAt(position);
    if (wanted) {
      this.spawnWhatIsSung(lyrics, position, index);
    }

    if (this.buffers == null) {
      this.buffers = new StageBuffers();
      this.drawer = new SlotDrawer(this.buffers);
    }

    Font font = client.font;
    Style style = Fonts.style(look.font, look.bold);
    long now = System.currentTimeMillis();
    // Always the real song: the album cache holds one, and the menu's tiles ask about the real one in the same frame.
    int[] colors = look.colorMode.startsWith("ALBUM") ? Palette.albumColors(Playback.get().current()) : null;
    this.prepareRowLook(look);
    this.drawer.begin(pose, camera, level, look, font, style, face, now, client.options.getEffectiveRenderDistance(),
        client.getWindow().getWidth(), client.getWindow().getHeight(), client.options.fov().get());

    boolean trail = "TRAIL".equals(look.place);
    this.ageLines(look, trail, now, step);

    Vec3 eye = camera.position();
    this.updateCarry(look, step);
    if (this.carry > 0.0F) {
      for (int i = 0; i < this.live.size(); i++) {
        this.moveCarried(this.live.get(i), look, camera, level, step);
      }
    }

    for (int i = 0; i < this.live.size(); i++) {
      LineSlot slot = this.live.get(i);
      long elapsed = elapsedOf(slot, position, trail, now);
      if (position < slot.line.startMs() - SEEK_BACK_MS) {
        this.drop(i--);
        continue;
      }

      // Its letters are still being built on the side, which never takes long. Better late than a stalled frame.
      if (face != null && slot.readyFace != face) {
        if (!face.ready(slot.line.text())) {
          slot.composed = null;
          continue;
        }

        slot.readyFace = face;
      }

      slot.composed = slot.composer.compose(slot.line.voice() == 0 ? look : this.duetLook, font, style, slot.line.text(),
          elapsed, slot.line.windowMs(), slot.parts, now, slot.seed, colors);
      if (slot.composed.finished) {
        this.drop(i--);
        continue;
      }

      this.composeRows(slot, look, font, style, lyrics, elapsed, now, colors);
      slot.widthPx = this.widestOf(slot);
      slot.bottomPx = bottomOf(slot);
      if (!slot.placed) {
        float height = slot.composed.height + slot.belowPx + slot.abovePx;
        this.placement.place(slot, look, camera, level, height, this.live);
        slot.belowEase = slot.belowPx;
        slot.bottomEase = slot.bottomPx;
      } else {
        float ease = 1.0F - (float) Math.exp(-step * ROW_SHIFT_RATE);
        slot.belowEase += (slot.belowPx - slot.belowEase) * ease;
        slot.bottomEase += (slot.bottomPx - slot.bottomEase) * ease;
      }

      this.turnToViewer(slot, look, level, eye, step);
      if (slot.waitingForChunk) {
        this.lookForLoadedGround(slot, look, camera, level, eye);
      }

      this.updateLight(slot, look, level);
    }

    this.drawFarToNear(eye);
    this.drawBreak(look, camera, level, lyrics, position, index, wanted, eye, step);
    this.buffers.drawAll();
  }

  /**
   * Text is neither sorted by the game nor kept out of the depth buffer, so a see-through line written first would cut
   * letter-shaped holes in whatever lies behind it. {@code live} keeps its order, because the ages count along it.
   */
  private void drawFarToNear(Vec3 eye) {
    int count = this.live.size();
    if (this.drawOrder.length < count) {
      this.drawOrder = new int[count];
      this.drawDistance = new double[count];
    }

    for (int i = 0; i < count; i++) {
      LineSlot slot = this.live.get(i);
      double dx = slot.anchorX - eye.x;
      double dy = slot.anchorY - eye.y;
      double dz = slot.anchorZ - eye.z;
      double distance = dx * dx + dy * dy + dz * dz;
      int at = i;
      while (at > 0 && this.drawDistance[at - 1] < distance) {
        this.drawDistance[at] = this.drawDistance[at - 1];
        this.drawOrder[at] = this.drawOrder[at - 1];
        at--;
      }

      this.drawDistance[at] = distance;
      this.drawOrder[at] = i;
    }

    int order = 0;
    for (int i = 0; i < count; i++) {
      LineSlot slot = this.live.get(this.drawOrder[i]);
      if (slot.composed != null && slot.placed) {
        this.drawer.drawLine(slot, this.master, order++);
      }
    }
  }

  /** When the song or the font changes, what it will need gets built on the side before its first line comes up. */
  private void askForGlyphs(VectorFace face, TimedLyrics lyrics) {
    if (face == null || (face == this.askedFace && lyrics == this.askedLyrics)) {
      return;
    }

    this.askedFace = face;
    this.askedLyrics = lyrics;
    StringBuilder all = new StringBuilder();
    // A long loop of the same few lines gains nothing from going through all of them.
    int lines = Math.min(lyrics.size(), MOST_LINES_TO_READY);
    for (int i = 0; i < lines; i++) {
      TimedLine line = lyrics.get(i);
      all.append(line.text());
      if (line.background() != null) {
        all.append(line.background().text());
      }
    }

    face.ask(all.toString());
  }

  /** Where the anchor of a line on foot sits, in the main row's space. */
  private static float bottomOf(LineSlot slot) {
    float bottom = slot.composed.restBottom;
    for (int i = 0; i < LineSlot.EXTRA_ROWS; i++) {
      LineSlot.Extra extra = slot.extras[i];
      if (extra != null && extra.composed != null) {
        bottom = Math.max(bottom, extra.y + extra.composed.restBottom);
      }
    }

    return bottom;
  }

  private float stepClock() {
    long nanos = System.nanoTime();
    float step = this.lastNanos == 0L ? 0.0F : Math.min(LONGEST_STEP_SECONDS, (nanos - this.lastNanos) * 1.0e-9F);
    this.lastNanos = nanos;
    return step;
  }

  /** A jump means a teleport or a respawn: whatever stood there no longer has anything to do with where the player is. */
  private void trackCamera(Camera camera, ClientLevel level, float step) {
    Vec3 eye = camera.position();
    boolean jumped = false;
    if (level != this.lastLevel) {
      this.lastLevel = level;
      jumped = this.hasLastCamera;
    } else if (this.hasLastCamera) {
      double dx = eye.x - this.lastCameraX;
      double dy = eye.y - this.lastCameraY;
      double dz = eye.z - this.lastCameraZ;
      jumped = dx * dx + dy * dy + dz * dz > JUMP_BLOCKS * JUMP_BLOCKS;
      if (!jumped && step > 0.0F) {
        float smoothing = 1.0F - (float) Math.exp(-step * SPEED_SMOOTHING);
        this.speedX += ((float) dx / step - this.speedX) * smoothing;
        this.speedY += ((float) dy / step - this.speedY) * smoothing;
        this.speedZ += ((float) dz / step - this.speedZ) * smoothing;
      }
    }

    if (jumped) {
      this.clear();
      this.speedX = 0.0F;
      this.speedY = 0.0F;
      this.speedZ = 0.0F;
      this.carry = 0.0F;
    }

    this.lastCameraX = eye.x;
    this.lastCameraY = eye.y;
    this.lastCameraZ = eye.z;
    this.hasLastCamera = true;
  }

  private void checkPlaceChange(Look look) {
    boolean changed = !this.hasPlaceKey
        || !look.place.equals(this.placedPlace)
        || look.distance != this.placedDistance
        || look.heightAdd != this.placedHeightAdd;
    if (!changed) {
      return;
    }

    // The menu shows the real line as its preview, so a moved fader has to move the line that is standing right now.
    if (this.hasPlaceKey) {
      this.placement.forget();
      for (int i = 0; i < this.live.size(); i++) {
        this.live.get(i).placed = false;
      }

      if (this.breakSlot != null) {
        this.breakSlot.placed = false;
      }
    }

    this.hasPlaceKey = true;
    this.placedPlace = look.place;
    this.placedDistance = look.distance;
    this.placedHeightAdd = look.heightAdd;
  }

  private boolean isLive(TimedLine line) {
    for (int i = 0; i < this.live.size(); i++) {
      if (this.live.get(i).line == line) {
        return true;
      }
    }

    return false;
  }

  /**
   * The newest line, and any before it that are still being sung, as in a duet: those stand where they are, and after a
   * seek or a pause they come back. Ordinary lines end where the next begins, so this finds no more than one of them.
   */
  private void spawnWhatIsSung(TimedLyrics lyrics, long position, int index) {
    for (int i = Math.max(0, index - MOST_SUNG_TOGETHER + 1); i <= index; i++) {
      TimedLine line = lyrics.get(i);
      if (position < line.endMs() && !line.text().isBlank() && !this.isLive(line)) {
        this.spawn(line, i);
      }
    }
  }

  private void spawn(TimedLine line, int index) {
    if (this.live.size() >= MOST_LINES && !"TRAIL".equals(LookStore.get().place)) {
      this.drop(0);
    }

    LineSlot slot = this.pool.isEmpty() ? new LineSlot() : this.pool.remove(this.pool.size() - 1);
    slot.begin(line, index);
    this.live.add(slot);
  }

  private void drop(int index) {
    LineSlot slot = this.live.remove(index);
    slot.release();
    this.pool.add(slot);
  }

  private void ageLines(Look look, boolean trail, long now, float step) {
    int age = 0;
    for (int i = this.live.size() - 1; i >= 0; i--) {
      LineSlot slot = this.live.get(i);
      if (!trail) {
        slot.age = 0.0F;
        continue;
      }

      if (!slot.evicted && age >= look.trailLength) {
        slot.evicted = true;
        slot.evictedAtMs = now;
      }

      if (!slot.evicted) {
        slot.age = approach(slot.age, age, step * 2.0F);
        age++;
      }
    }
  }

  private static long elapsedOf(LineSlot slot, long position, boolean trail, long now) {
    long window = slot.line.windowMs();
    if (slot.evicted) {
      return window + (now - slot.evictedAtMs);
    }

    long elapsed = position - slot.line.startMs();
    // In a trail the exit waits until the line's turn in the list is over, so it keeps standing with its idle running.
    return trail ? Math.min(elapsed, window - 1L) : elapsed;
  }

  // Rows

  private void prepareRowLook(Look look) {
    Look row = this.rowLook;
    row.copyLookFrom(look);
    row.size = look.size * ROW_SIZE;
    row.arrange = "LINE";
    row.karaoke = false;

    // What is sung behind the line has its own timing, and comes and goes quietly.
    Look behind = this.backgroundLook;
    behind.copyLookFrom(row);
    behind.karaoke = look.karaoke;
    behind.karaokeStyle = look.karaokeStyle;
    behind.enter = "FADE";
    behind.exit = "FADE";
    behind.idle = "NONE";

    // A singer of a duet has half of the view, so their lines wrap sooner.
    Look duet = this.duetLook;
    duet.copyLookFrom(look);
    duet.wrapWidth = Math.round(look.wrapWidth * DUET_WRAP);
  }

  private void composeRows(LineSlot slot, Look look, Font font, Style style, TimedLyrics lyrics, long elapsed, long now,
      int[] colors) {
    float mainHeight = slot.composed.height;
    float below = 0.0F;
    float above = 0.0F;
    boolean rows = slot.age < 0.5F && !slot.evicted;

    VectorFace face = VectorFace.of(look);
    below = this.backgroundRow(slot, rows, mainHeight, font, style, face, elapsed, now, colors);

    int next = slot.lineIndex + 1;
    String nextText = rows && look.nextLine && next < lyrics.size() ? lyrics.get(next).text() : null;
    below = this.belowRow(slot, LineSlot.NEXT, nextText, NEXT_ALPHA, mainHeight, below, font, style, face, elapsed, now,
        colors);

    for (int i = 0; i < LineSlot.EXTRA_ROWS - LineSlot.FIRST_EARLIER; i++) {
      int earlier = slot.lineIndex - 1 - i;
      String text = rows && i < look.earlierLines && earlier >= 0 ? lyrics.get(earlier).text() : null;
      LineSlot.Extra extra = slot.extras[LineSlot.FIRST_EARLIER + i];
      if (text == null || text.isBlank()) {
        if (extra != null) {
          extra.composed = null;
        }

        continue;
      }

      extra = slot.extra(LineSlot.FIRST_EARLIER + i);
      // A row waits for its letters like the line does, or laying it out would build them here on the render thread.
      if (!extra.ready(face, text)) {
        extra.composed = null;
        continue;
      }

      extra.composed = extra.composer.compose(this.rowLook, font, style, text, elapsed, slot.line.windowMs(), null, now,
          slot.seed + 1L + i, colors);
      above += extra.composed.height + ROW_GAP;
      extra.y = -above;
      extra.alpha = EARLIER_ALPHA;
    }

    slot.belowPx = below;
    slot.abovePx = above;
  }

  /**
   * Stands right under the line from the line's start, with its letters gone until they are sung, so that the line
   * does not have to make room in the middle of being sung.
   */
  private float backgroundRow(LineSlot slot, boolean rows, float mainHeight, Font font, Style style, VectorFace face,
      long elapsed, long now, int[] colors) {
    TimedLine behind = rows ? slot.line.background() : null;
    if (behind == null) {
      if (slot.extras[LineSlot.BACKGROUND] != null) {
        slot.extras[LineSlot.BACKGROUND].composed = null;
      }

      return 0.0F;
    }

    LineSlot.Extra extra = slot.extra(LineSlot.BACKGROUND);
    if (!extra.ready(face, behind.text())) {
      extra.composed = null;
      return 0.0F;
    }

    long since = elapsed - (behind.startMs() - slot.line.startMs());
    extra.composed = extra.composer.compose(this.backgroundLook, font, style, behind.text(), since, behind.windowMs(),
        behind.parts(), now, slot.seed + 5L, colors);
    extra.y = mainHeight + ROW_GAP;
    extra.alpha = BACKGROUND_ALPHA;
    return ROW_GAP + extra.composed.height;
  }

  private float belowRow(LineSlot slot, int row, String text, float alpha, float mainHeight, float below, Font font,
      Style style, VectorFace face, long elapsed, long now, int[] colors) {
    if (text == null || text.isBlank()) {
      if (slot.extras[row] != null) {
        slot.extras[row].composed = null;
      }

      return below;
    }

    LineSlot.Extra extra = slot.extra(row);
    if (!extra.ready(face, text)) {
      extra.composed = null;
      return below;
    }

    extra.composed = extra.composer.compose(this.rowLook, font, style, text, elapsed, slot.line.windowMs(), null, now,
        slot.seed + 11L + row, colors);
    extra.y = mainHeight + below + ROW_GAP;
    extra.alpha = alpha;
    return below + ROW_GAP + extra.composed.height;
  }

  private float widestOf(LineSlot slot) {
    float widest = slot.composed.width;
    for (int i = 0; i < LineSlot.EXTRA_ROWS; i++) {
      LineSlot.Extra extra = slot.extras[i];
      if (extra != null && extra.composed != null) {
        widest = Math.max(widest, extra.composed.width);
      }
    }

    return widest;
  }

  // Keeping a line in the world

  /**
   * A line never tips on the pitch axis. It flips when the viewer gets behind it, at the moment it is edge-on and the
   * flip cannot be seen, and with facing TURN it swings slowly round like a heavy sign once the viewer has wandered off.
   */
  private void turnToViewer(LineSlot slot, Look look, ClientLevel level, Vec3 eye, float step) {
    if (slot.settleY != 0.0) {
      double move = Math.abs(slot.settleY) < SETTLED_BLOCKS
          ? slot.settleY
          : slot.settleY * (1.0F - (float) Math.exp(-step * RESETTLE_RATE));
      slot.anchorY += move;
      slot.floorY += move;
      slot.settleY -= move;
    }

    if (slot.standing) {
      float toX = (float) (eye.x - slot.anchorX);
      float toZ = (float) (eye.z - slot.anchorZ);
      float distance = (float) Math.sqrt(toX * toX + toZ * toZ);
      if (distance > 0.01F) {
        float faceX = (float) Math.sin(slot.yaw);
        float faceZ = (float) Math.cos(slot.yaw);
        if ((toX * faceX + toZ * faceZ) / distance < FLIP_MARGIN) {
          slot.yaw += (float) Math.PI;
          // The curve belongs to the viewer's side, so it has to come back round on the new side rather than jump.
          slot.bendEase = 0.0F;
          slot.columnsValid = false;
        }

        if ("TURN".equals(look.facing)) {
          float wanted = (float) Math.atan2(toX, toZ);
          float left = wrapRadians(wanted - slot.yaw);
          if (!slot.turning && Math.abs(left) > TURN_START) {
            slot.turning = true;
          }

          if (slot.turning) {
            slot.yaw += left * (1.0F - (float) Math.exp(-step * TURN_RATE));
            slot.columnsValid = false;
            if (Math.abs(left) < TURN_END) {
              slot.turning = false;
            }
          }
        }
      }

      slot.bendEase = approach(slot.bendEase, 1.0F, step / BEND_RETURN_SECONDS);
      slot.orient.rotationY(slot.yaw);
      if (!slot.columnsValid && !slot.turning && slot.bendEase >= 1.0F && this.carry <= 0.0F) {
        Placement.findColumns(slot, look, level);
        // Having swung round it stands over other ground. A flip only changes sides, which the search covered already.
        if (slot.onFoot && "TURN".equals(look.facing)) {
          restEased(slot, look, level, eye.y);
        }
      }
    }
  }

  /** Stands a line on the ground it has now, getting there by easing, with its floor keeping pace, so it never pops. */
  private static void restEased(LineSlot slot, Look look, ClientLevel level, double eyeY) {
    double anchorWas = slot.anchorY;
    Placement.restOnHighestGround(slot, look, level, eyeY);
    slot.settleY = slot.anchorY - anchorWas;
    slot.anchorY = anchorWas;
    slot.floorY -= slot.settleY;
  }

  /**
   * A line placed before its chunk came, as right after a teleport, hangs at a guessed height that the ground may well
   * turn out to lie above. Once the chunk is there it goes onto that ground. Over a real void it never waited at all.
   */
  private void lookForLoadedGround(LineSlot slot, Look look, Camera camera, ClientLevel level, Vec3 eye) {
    if (this.carry > 0.0F || this.frameCount % CARRY_GROUND_POLL_FRAMES != 0 || !Placement.chunkLoaded(slot, level)) {
      return;
    }

    slot.waitingForChunk = false;
    double ground = Placement.groundBelow(level, slot.anchorX, eye.y, slot.anchorZ, Placement.GROUND_REACH,
        Placement.submerged(camera));
    if (!Double.isNaN(ground)) {
      slot.hasGround = true;
      slot.groundY = ground;
      slot.columnsValid = false;
      restEased(slot, look, level, eye.y);
    }
  }

  /** Only when the player switched this on: anything faster than a sprint brings the words along. */
  private void updateCarry(Look look, float step) {
    float speed = (float) Math.sqrt(this.speedX * this.speedX + this.speedY * this.speedY + this.speedZ * this.speedZ);
    boolean carrying = look.carryWhenFast && speed > CARRY_ABOVE_SPEED;
    this.carry = approach(this.carry, carrying ? 1.0F : 0.0F, step * CARRY_RATE);
  }

  private void moveCarried(LineSlot slot, Look look, Camera camera, ClientLevel level, float step) {
    if (!slot.placed || !slot.standing) {
      return;
    }

    float move = step * this.carry;
    slot.anchorX += this.speedX * move;
    slot.anchorZ += this.speedZ * move;
    // The steps found when the line was placed are somewhere behind by now.
    slot.columnsValid = false;
    if (!slot.onFoot) {
      slot.anchorY += this.speedY * move;
      return;
    }

    slot.settleY = 0.0;

    if (this.frameCount % CARRY_GROUND_POLL_FRAMES == 0) {
      double ground = Placement.groundBelow(level, slot.anchorX, slot.anchorY + CARRY_GROUND_FROM, slot.anchorZ,
          CARRY_GROUND_REACH, Placement.submerged(camera));
      slot.hasGround = !Double.isNaN(ground);
      slot.groundY = slot.hasGround ? ground : slot.groundY;
    }

    slot.hasFloor = slot.hasGround;
    slot.floorY = slot.groundY;
    if (slot.hasGround) {
      double wantedY = slot.groundY + CARRIED_CLEARANCE + Math.max(0.0F, look.heightAdd);
      slot.anchorY += (wantedY - slot.anchorY) * (1.0F - (float) Math.exp(-step * RESETTLE_RATE));
    }
  }

  private void updateLight(LineSlot slot, Look look, ClientLevel level) {
    if (look.selfLit) {
      slot.light = LightCoordsUtil.FULL_BRIGHT;
      slot.lightAge = 0;
      return;
    }

    if (--slot.lightAge > 0) {
      return;
    }

    slot.lightAge = LIGHT_POLL_FRAMES;
    float middle = slot.composed == null ? 0.0F : slot.composed.height * slot.scale * 0.5F;
    double y = slot.anchorY + (slot.onFoot ? middle : 0.0);
    double x = slot.anchorX;
    double z = slot.anchorZ;
    if (slot.standing) {
      // On the face's side: the anchor itself can sit right against a block, and inside one the light reads as zero.
      x += Math.sin(slot.yaw);
      z += Math.cos(slot.yaw);
    } else {
      y += 0.5;
    }

    this.lightAt.set(x, y, z);
    int block = level.getBrightness(LightLayer.BLOCK, this.lightAt);
    int sky = level.getBrightness(LightLayer.SKY, this.lightAt);
    this.lightAt.set(x, y + 1.0, z);
    block = Math.max(block, level.getBrightness(LightLayer.BLOCK, this.lightAt));
    sky = Math.max(sky, level.getBrightness(LightLayer.SKY, this.lightAt));
    int least = "BRIGHT".equals(look.glow) ? BRIGHT_GLOW_BLOCK_LIGHT
        : "SOFT".equals(look.glow) ? SOFT_GLOW_BLOCK_LIGHT : LEAST_BLOCK_LIGHT;
    slot.light = LightCoordsUtil.pack(Math.max(least, block), sky);
  }

  // Long instrumental breaks

  private void drawBreak(Look look, Camera camera, ClientLevel level, TimedLyrics lyrics, long position, int index,
      boolean wanted, Vec3 eye, float step) {
    if (!look.breakDots || !wanted) {
      this.breakBefore = null;
      return;
    }

    // With word timings a break really runs from the last word sung to the next word sung.
    long since = 0L;
    long gone = 0L;
    for (int i = Math.max(0, index - MOST_SUNG_TOGETHER + 1); i <= index; i++) {
      since = Math.max(since, lyrics.get(i).sungEndMs());
      gone = Math.max(gone, lyrics.get(i).endMs());
    }

    TimedLine next = lyrics.next(position);
    long nextWord = next == null ? 0L : next.firstSungMs();
    if (next == null || position < gone + BREAK_AFTER_LINE_MS || nextWord - since < BREAK_LEAST_MS) {
      this.breakBefore = null;
      return;
    }

    long gap = nextWord - since;
    if (this.breakSlot == null) {
      this.breakSlot = new LineSlot();
      this.breakSlot.composed = new ComposedLine();
    }

    LineSlot slot = this.breakSlot;
    int dots = Math.min(MOST_DOTS, FEWEST_DOTS + (int) ((gap - BREAK_LEAST_MS) / ONE_MORE_DOT_MS));
    if (next != this.breakBefore) {
      this.breakBefore = next;
      slot.placed = false;
      slot.seed = next.startMs() * 37L;
    }

    if (!slot.placed) {
      slot.widthPx = dots * DOT_ROW_WIDTH_PER_DOT;
      this.placement.place(slot, look, camera, level, DOT_ROW_HEIGHT, this.live);
    }

    this.turnToViewer(slot, look, level, eye, step);
    this.updateLight(slot, look, level);
    float in = (position - gone - BREAK_AFTER_LINE_MS) / (float) BREAK_FADE_MS;
    float out = (nextWord - position) / (float) BREAK_FADE_MS;
    float alpha = Math.min(1.0F, Math.min(in, out)) * this.master;
    this.drawer.drawDots(slot, dots, (position - since) / (float) gap, alpha);
  }

  private static float approach(float value, float target, float amount) {
    if (value < target) {
      return Math.min(target, value + amount);
    }

    return Math.max(target, value - amount);
  }

  private static float wrapRadians(float radians) {
    float full = (float) Math.PI * 2.0F;
    float wrapped = radians % full;
    if (wrapped > Math.PI) {
      wrapped -= full;
    } else if (wrapped < -Math.PI) {
      wrapped += full;
    }

    return wrapped;
  }
}
