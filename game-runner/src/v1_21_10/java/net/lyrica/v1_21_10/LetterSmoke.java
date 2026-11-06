package net.lyrica.v1_21_10;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.labymod.api.Laby;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.addon.LoadedAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.lyrica.v1_21_10.look.Look;
import net.lyrica.v1_21_10.look.LookStore;
import net.lyrica.v1_21_10.look.Presets;
import net.lyrica.v1_21_10.song.NowPlaying;
import net.lyrica.v1_21_10.song.Playback;
import net.lyrica.v1_21_10.stage.WorldStage;
import net.lyrica.v1_21_10.words.DemoLines;
import net.lyrica.v1_21_10.words.LrcReader;
import net.lyrica.v1_21_10.words.LyricsLibrary;
import net.lyrica.v1_21_10.words.ReaderChecks;
import net.lyrica.v1_21_10.words.TimedLyrics;
import net.lyrica.v1_21_10.words.TtmlReader;

/**
 * Development only: active in the dev environment when LYRICA_LETTERS.txt sits in the game folder. Shoots the 3D
 * letters from every side on a stage built for it, and measures the frame rate with words on and off in the same run.
 * The marker's lines name the scenes to run; an empty marker runs all of them. It never saves the look, and it ends the
 * process itself, as stopping a game in the middle of a load can hang for good.
 */
public final class LetterSmoke {

  private static final String MARKER = "LYRICA_LETTERS.txt";
  private static final String TAG = "[Lyrica letters] ";
  private static final String WORLD = "letterworld";

  private static final long TITLE_SHOWING_MS = 2_500L;
  private static final long WORLD_SETTLE_MS = 1_500L;
  private static final long HALT_AFTER_MS = 500_000L;
  private static final long CHUNKS_MS = 6_000L;
  private static final long MOVE_MS = 700L;
  private static final long STAND_MS = 900L;
  private static final long MEASURE_MS = 5_000L;
  private static final long LAST_WRITES_MS = 2_000L;

  // One long platform of grass with its top at y = 101, the sites lying along it in z.
  private static final int LOW_X = 2960;
  private static final int HIGH_X = 3039;
  private static final int LOW_Z = 2960;
  private static final int HIGH_Z = 3199;
  private static final int FLOOR = 100;
  private static final int AIR_TOP = 125;
  private static final int AIR_SLICE = 1;
  private static final double FEET = 101.0;
  private static final double EYE_HEIGHT = 1.62;

  private static final double MIDDLE_X = 3000.5;
  private static final double OPEN_Z = 2970.5;
  private static final double SLOPE_Z = 3020.5;
  private static final double STEP_Z = 3060.5;
  private static final double WALL_Z = 3100.5;
  private static final double ROOM_Z = 3165.5;
  // Where a line stands from a spot, at the default distance and no wander.
  private static final double AHEAD = 12.0;

  private static final String WORDS = "Gypsy rhythm keeps playing through the quiet foggy night";
  private static final String SHORT_WORDS = "Gently swaying by the jetty";
  private static final String ADVANCE_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,'!?";

  // A record that LRCLIB's own format page uses as its example, so one that should keep its word timings.
  private static final String LRCLIB_TITLE = "Still Alive";
  private static final String LRCLIB_ARTIST = "Aperture Science Psychoacoustics Laboratories";
  private static final String LRCLIB_ALBUM = "Portal";
  private static final long LRCLIB_DURATION_MS = 176_457L;

  private static final String SUNG_TITLE = "Lantern Test";
  private static final String SUNG_ARTIST = "Lyrica Smoke";
  private static final long SUNG_DURATION_MS = 60_000L;
  private static final String SUNG_TTML = """
      <?xml version="1.0" encoding="UTF-8"?>
      <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="en">
      <head><metadata>
      <ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/>
      </metadata></head>
      <body dur="60"><div begin="1.000" end="10.000">
      <p begin="1.000" end="5.000"><span begin="1.000" end="1.500">Lan</span><span begin="1.500" end="2.100">tern</span> <span begin="2.300" end="3.100">light</span> <span begin="3.200" end="3.700">up</span><span begin="3.700" end="3.900">on</span> <span begin="3.900" end="4.200">the</span> <span begin="4.200" end="5.000">water</span><span ttm:role="x-translation" xml:lang="de">Laternenlicht</span></p>
      <p begin="6.000" end="9.500"><span begin="6.000" end="6.500">Holding</span> <span begin="6.500" end="9.500">on</span><span ttm:role="x-bg"><span begin="7.000" end="7.600">(still</span> <span begin="7.600" end="9.000">here)</span></span></p>
      </div><div begin="21.000" end="40.000">
      <p begin="21.000" end="25.000" ttm:agent="v1"><span begin="21.000" end="22.000">Call</span> <span begin="22.000" end="23.000">across</span> <span begin="23.000" end="25.000">the</span> <span begin="25.000" end="25.800">bay</span></p>
      <p begin="24.000" end="27.000" ttm:agent="v2"><span begin="24.000" end="25.000">Answer</span> <span begin="25.000" end="26.000">back</span> <span begin="26.000" end="27.000">again</span></p>
      <p begin="28.000" end="30.400"><span begin="28.000" end="28.400">&#x6708;</span><span begin="28.400" end="28.800">&#x306E;</span><span begin="28.800" end="29.200">&#x5149;</span><span begin="29.200" end="29.600">&#x304C;</span><span begin="29.600" end="30.000">&#x7167;</span><span begin="30.000" end="30.400">&#x3089;&#x3059;</span></p>
      <p begin="33.000" end="36.000"><span begin="33.000" end="33.500">&#x64A;&#x62D;&#x631;&#x642;</span> <span begin="33.500" end="34.200">&#x642;&#x644;&#x628;&#x64A;</span> <span begin="34.200" end="34.700">&#x641;&#x64A;</span> <span begin="34.700" end="35.200">&#x647;&#x630;&#x647;</span> <span begin="35.200" end="36.000">&#x627;&#x644;&#x644;&#x64A;&#x644;&#x629;</span></p>
      </div></body></tt>
      """;
  private static final String SUNG_LRC = """
      [ar:Lyrica Smoke]
      [ti:Lantern Test]
      [00:01.00]<00:01.00>Lantern <00:01.60>light <00:02.20>upon <00:03.00>the <00:03.40>water<00:04.60>
      [00:06.00]<00:06.00>Holding <00:06.50>on
      """;

  private static boolean checked;
  private static LetterSmoke run;

  private enum Phase { TITLE, JOINING, STEPS, DONE }

  private record Step(long settleMs, Runnable action, String shot, String measure) {
  }

  private record Spot(double x, double y, double z, float yaw, float pitch) {

    /** From here, looking at a point. */
    static Spot looking(double x, double y, double z, double atX, double atY, double atZ) {
      double dx = atX - x;
      double dz = atZ - z;
      float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
      float pitch = (float) -Math.toDegrees(Math.atan2(atY - (y + EYE_HEIGHT), Math.sqrt(dx * dx + dz * dz)));
      return new Spot(x, y, z, yaw, pitch);
    }
  }

  private final Minecraft client;
  private final long bornMs = System.currentTimeMillis();
  private final List<Step> steps = new ArrayList<>();
  private final List<String> scenes = new ArrayList<>();

  private Phase phase = Phase.TITLE;
  private long waitingSinceMs;
  private int index;
  private boolean stepBegun;
  private long stepBeganMs;
  private long framesInStep;
  private long drawNanosInStep;
  private long vertsInStep;
  private long worstDrawInStep;

  private LetterSmoke(Minecraft client) {
    this.client = client;
  }

  /** At the end of a frame, so a screenshot gets all of it. */
  public static void frame() {
    if (!checked) {
      checked = true;
      Minecraft client = Minecraft.getInstance();
      Path marker = client.gameDirectory.toPath().resolve(MARKER);
      if (Boolean.getBoolean("net.labymod.addon-dev-environment") && Files.exists(marker)) {
        LookStore.holdSaves();
        client.options.pauseOnLostFocus = false;
        run = new LetterSmoke(client);
        try {
          for (String line : Files.readAllLines(marker)) {
            if (!line.isBlank()) {
              run.scenes.add(line.strip());
            }
          }
        } catch (IOException failure) {
          Env.LOG.warn(TAG + "could not read the marker", failure);
        }

        startWatchdog();
        Env.LOG.info(TAG + "active, scenes " + run.scenes);
      }
    }

    if (run != null) {
      try {
        run.advance();
      } catch (RuntimeException failure) {
        Env.LOG.error(TAG + "failed", failure);
        end(2);
      }
    }
  }

  private static void startWatchdog() {
    Thread watchdog = new Thread(() -> {
      try {
        Thread.sleep(HALT_AFTER_MS);
      } catch (InterruptedException stopped) {
        return;
      }

      Env.LOG.warn(TAG + "watchdog ended the game");
      Runtime.getRuntime().halt(3);
    }, "Lyrica letters watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  private static void end(int code) {
    Env.LOG.info(TAG + "ending with " + code);
    Runtime.getRuntime().halt(code);
  }

  private void advance() {
    long now = System.currentTimeMillis();
    switch (this.phase) {
      case TITLE -> this.waitForTitle(now);
      case JOINING -> this.waitForWorld(now);
      case STEPS -> this.runSteps(now);
      default -> {
      }
    }
  }

  private void waitForTitle(long now) {
    if (this.client.level != null) {
      this.phase = Phase.JOINING;
      this.waitingSinceMs = 0L;
      return;
    }

    if (this.client.screen == null || this.client.getOverlay() != null) {
      this.waitingSinceMs = 0L;
      return;
    }

    if (this.waitingSinceMs == 0L) {
      this.waitingSinceMs = now;
    }

    if (now - this.waitingSinceMs >= TITLE_SHOWING_MS) {
      // Before opening it, as opening a world can run frames from inside this one
      this.phase = Phase.JOINING;
      this.waitingSinceMs = 0L;
      if (this.client.getLevelSource().levelExists(WORLD)) {
        Env.LOG.info(TAG + "opening " + WORLD);
        this.client.createWorldOpenFlows().openWorld(WORLD, () -> end(4));
      } else {
        Env.LOG.info(TAG + "making " + WORLD);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
            new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
        this.client.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
            registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                .createWorldDimensions(),
            this.client.screen);
      }
    }
  }

  private void waitForWorld(long now) {
    boolean ready = this.client.level != null && this.client.player != null && this.client.screen == null
        && this.client.getSingleplayerServer() != null;
    if (!ready) {
      this.waitingSinceMs = 0L;
      return;
    }

    if (this.waitingSinceMs == 0L) {
      this.waitingSinceMs = now;
    }

    if (now - this.waitingSinceMs >= WORLD_SETTLE_MS) {
      this.client.options.hideGui = true;
      this.buildSteps();
      this.phase = Phase.STEPS;
    }
  }

  private void runSteps(long now) {
    if (this.client.level == null || this.client.player == null) {
      Env.LOG.warn(TAG + "lost the world");
      end(5);
      return;
    }

    // Stays in the air wherever it is put, so that the view can be anywhere.
    this.client.player.getAbilities().flying = true;

    if (this.index >= this.steps.size()) {
      Env.LOG.info(TAG + "done");
      this.phase = Phase.DONE;
      end(0);
      return;
    }

    Step step = this.steps.get(this.index);
    if (!this.stepBegun) {
      this.stepBegun = true;
      this.stepBeganMs = now;
      this.framesInStep = 0L;
      this.drawNanosInStep = 0L;
      this.vertsInStep = 0L;
      this.worstDrawInStep = 0L;
      step.action.run();
      return;
    }

    this.framesInStep++;
    this.drawNanosInStep += WorldStage.lastDrawNanos();
    this.vertsInStep += WorldStage.lastVertices();
    this.worstDrawInStep = Math.max(this.worstDrawInStep, WorldStage.lastDrawNanos());

    long gone = now - this.stepBeganMs;
    if (gone >= step.settleMs) {
      if (step.measure != null) {
        Env.LOG.info(String.format(Locale.ROOT,
            TAG + "FPS %s: %.1f fps, lyrics %.3f ms a frame, worst %.2f ms, %d vertices, over %d frames",
            step.measure, this.framesInStep * 1000.0 / gone, this.drawNanosInStep / 1.0e6 / this.framesInStep,
            this.worstDrawInStep / 1.0e6, this.vertsInStep / this.framesInStep, this.framesInStep));
      }

      if (step.shot != null) {
        this.shoot(step.shot);
      }

      this.index++;
      this.stepBegun = false;
    }
  }

  private void shoot(String name) {
    String file = name + ".png";
    Path written = this.client.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR).resolve(file);
    Screenshot.grab(this.client.gameDirectory, file, this.client.getMainRenderTarget(), 1, message -> {
      if (Files.exists(written)) {
        Env.LOG.info(TAG + "saved " + name);
      } else {
        Env.LOG.warn(TAG + "not saved " + name + ": " + message.getString());
      }
    });
  }

  // The script

  private boolean wants(String scene) {
    return this.scenes.isEmpty() || this.scenes.contains(scene);
  }

  private void buildSteps() {
    this.buildStage();
    if (this.wants("sung")) {
      this.sung();
    }

    if (this.wants("lrclib")) {
      this.wordsFromLrclib();
    }

    if (this.wants("views")) {
      this.views();
    }

    if (this.wants("light")) {
      this.light();
    }

    if (this.wants("motion")) {
      this.motion();
    }

    if (this.wants("ground")) {
      this.ground();
    }

    if (this.wants("depth")) {
      this.depthAndTurn();
    }

    if (this.wants("looks")) {
      this.looks();
    }

    if (this.wants("sweep")) {
      this.sweep();
    }

    if (this.wants("places")) {
      this.places();
    }

    if (this.wants("lod")) {
      this.lod();
    }

    if (this.wants("fps")) {
      this.fps();
    }

    if (this.wants("disable")) {
      this.disable();
    }

    if (this.wants("languages")) {
      this.languages();
    }

    if (this.wants("halo")) {
      this.halo();
    }

    this.step(LAST_WRITES_MS, () -> {
    }, null);
  }

  private void buildStage() {
    this.step(CHUNKS_MS, () -> this.server(
        String.format(Locale.ROOT, "forceload add %d %d %d %d", LOW_X, LOW_Z, HIGH_X, HIGH_Z),
        "gamemode creative @a",
        "effect clear @a",
        "time set noon",
        "weather clear",
        String.format(Locale.ROOT, "tp @a %.1f 130 %.1f 0 2", MIDDLE_X, OPEN_Z)), null);
    // The chunks may still have been loading the first time; filling is harmless when repeated
    this.step(2_500L, this::fillStage, null);
    this.step(2_500L, () -> {
      this.fillStage();
      this.server("kill @e[type=!player]");
      this.goTo(new Spot(MIDDLE_X, FEET, OPEN_Z, 0.0F, 2.0F));
    }, null);
  }

  private void fillStage() {
    List<String> commands = new ArrayList<>();
    commands.add(fill(LOW_X, FLOOR, LOW_Z, HIGH_X, FLOOR, HIGH_Z, "grass_block"));
    for (int y = FLOOR + 1; y <= AIR_TOP; y += AIR_SLICE) {
      commands.add(fill(LOW_X, y, LOW_Z, HIGH_X, y, HIGH_Z, "air"));
    }

    // A slope along the line: one block up for every two across, on one side only, under all of the line's depth.
    int slopeZ = (int) (SLOPE_Z + AHEAD);
    for (int i = 0; i < 8; i++) {
      int x = 3002 + i * 2;
      commands.add(fill(x, FLOOR + 1, slopeZ - 5, x + 1, FLOOR + 1 + i, slopeZ + 5, "stone"));
    }

    // A step of two blocks part-way along the line.
    int stepZ = (int) (STEP_Z + AHEAD);
    commands.add(fill(3005, FLOOR + 1, stepZ - 4, 3008, FLOOR + 2, stepZ + 4, "stone_bricks"));

    // A wall of six blocks standing across part of the line.
    int wallZ = (int) (WALL_Z + AHEAD);
    commands.add(fill(3006, FLOOR + 1, wallZ - 6, 3007, FLOOR + 6, wallZ + 6, "bricks"));

    // A sealed dark room.
    int roomZ = (int) (ROOM_Z + AHEAD);
    commands.add(fill(2980, FLOOR + 1, roomZ - 20, 3020, FLOOR + 10, roomZ + 10, "stone"));
    commands.add(fill(2981, FLOOR + 1, roomZ - 19, 3019, FLOOR + 9, roomZ + 9, "air"));
    this.server(commands.toArray(new String[0]));
  }

  private static String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
    return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x0, y0, z0, x1, y1, z1, block);
  }

  /** The everyday look with lower case, so descenders show. */
  private static void readable(Look look) {
    look.font = "MONTSERRAT";
    look.bold = true;
    look.upper = false;
    look.size = 1.2F;
    look.letterSpacing = 0.2F;
    look.color = 0xF4EFE2;
    look.depth = 1.6F;
    look.sideShade = 0.42F;
    look.bend = 0.2F;
    look.enter = "NONE";
    look.exit = "NONE";
  }

  private void views() {
    this.stand(OPEN_Z, LetterSmoke::readable, WORDS);
    double lineZ = OPEN_Z + AHEAD;
    double middleY = FEET + 2.2;
    this.shotFrom("front_close", new Spot(MIDDLE_X, FEET + 1.0, lineZ - 5.0, 0.0F, 3.0F));
    this.shotFrom("left_45", Spot.looking(MIDDLE_X - 7.0, FEET + 0.5, lineZ - 7.0, MIDDLE_X - 2.0, middleY, lineZ));
    this.shotFrom("right_45", Spot.looking(MIDDLE_X + 7.0, FEET + 0.5, lineZ - 7.0, MIDDLE_X + 2.0, middleY, lineZ));
    this.shotFrom("above", Spot.looking(MIDDLE_X, FEET + 12.0, lineZ - 7.0, MIDDLE_X, middleY, lineZ));
    this.shotFrom("far_30", Spot.looking(MIDDLE_X, FEET + 2.0, lineZ - 30.0, MIDDLE_X, middleY, lineZ));
    // Going round by the side first, the way a player would.
    this.shotFrom(null, Spot.looking(MIDDLE_X + 16.0, FEET + 0.5, lineZ, MIDDLE_X, middleY, lineZ));
    this.shotFrom("behind", Spot.looking(MIDDLE_X + 4.0, FEET + 0.5, lineZ + 12.0, MIDDLE_X, middleY, lineZ));

    this.stand(OPEN_Z, look -> {
    }, "Standing In The World Tonight");
    this.shotFrom("default_look", new Spot(MIDDLE_X, FEET, OPEN_Z, 0.0F, -2.0F));
    this.shotFrom("default_close", new Spot(MIDDLE_X, FEET + 1.0, lineZ - 5.0, 0.0F, 0.0F));
    this.shotFrom("default_side", Spot.looking(MIDDLE_X + 8.0, FEET + 0.5, lineZ - 6.0, MIDDLE_X, middleY, lineZ));
  }

  // Word by word

  /**
   * A made-up song read from TTML and put straight on the library. Most shots hold the song still at a known moment, so
   * that they show exactly what that moment looks like. A last run plays on the clock.
   */
  private void sung() {
    this.step(MOVE_MS, this::checkReaders, null);
    this.step(1_000L, () -> {
      this.play(1_000L);
      LyricsLibrary.get().force(TtmlReader.parse(SUNG_TTML, SUNG_DURATION_MS));
    }, null);
    this.step(MOVE_MS, () -> this.expectWords("the TTML is word timed", true), null);

    // Line one, DIM: the sung part lights up word by word, and inside a word syllable by syllable.
    this.sungPlace(look -> look.karaokeStyle = "DIM");
    this.step(1_000L, () -> this.seek(1_020L), "sung_dim_1_start");
    this.step(MOVE_MS, () -> this.seek(1_400L), "sung_dim_2_lan");
    this.step(MOVE_MS, () -> this.seek(2_050L), "sung_dim_3_lantern");
    this.step(MOVE_MS, () -> this.seek(2_800L), "sung_dim_4_light");
    this.step(MOVE_MS, () -> this.seek(3_900L), "sung_dim_5_the");
    this.step(MOVE_MS, () -> this.seek(4_990L), "sung_dim_6_all");

    for (String style : new String[] {"FILL", "SWEEP", "LIFT"}) {
      this.sungPlace(look -> look.karaokeStyle = style);
      this.step(1_000L, () -> this.seek(2_800L), "sung_" + style.toLowerCase(Locale.ROOT) + "_light");
    }

    // Typed with the singing.
    this.sungPlace(look -> {
      look.karaokeStyle = "DIM";
      look.enter = "TYPE";
    });
    this.step(1_000L, () -> this.seek(2_050L), "sung_type");

    // Line two: a held word that swells, and what is sung behind it with its own timing.
    this.sungPlace(look -> look.karaokeStyle = "LIFT");
    this.step(1_000L, () -> this.seek(6_200L), "sung_held_start");
    this.step(MOVE_MS, () -> this.seek(8_400L), "sung_held_long");

    // The break: it counts from the last word sung to the next word sung.
    this.sungPlace(look -> {
      look.karaokeStyle = "DIM";
      look.breakDots = true;
    });
    this.step(1_000L, () -> this.seek(13_000L), "sung_break_early");
    this.step(MOVE_MS, () -> this.seek(19_000L), "sung_break_late");
    this.step(MOVE_MS, () -> this.seek(20_900L), "sung_break_out");

    // Two singers over each other, each on their own side.
    this.sungPlace(look -> {
      look.karaokeStyle = "DIM";
      look.spread = 1.0F;
      look.color2 = 0x7FD6FF;
    });
    this.step(1_000L, () -> this.seek(21_400L), null);
    this.step(1_000L, () -> this.seek(24_300L), "sung_duet");
    this.step(MOVE_MS, () -> this.seek(26_200L), "sung_duet_second");

    // Characters without spaces between them and a right-to-left line, both by their own timing.
    this.sungPlace(look -> look.karaokeStyle = "DIM");
    this.step(1_500L, () -> this.seek(28_800L), "sung_japanese");
    this.sungPlace(look -> look.karaokeStyle = "DIM");
    this.step(1_500L, () -> this.seek(33_800L), "sung_arabic");

    // The flat lyrics with the same file.
    this.sungPlace(look -> {
      look.karaokeStyle = "DIM";
      look.place = "SCREEN";
    });
    this.step(1_000L, () -> {
      this.client.options.hideGui = false;
      this.seek(8_400L);
    }, "sung_screen_behind");
    this.step(MOVE_MS, () -> this.seek(21_400L), null);
    this.step(MOVE_MS, () -> this.seek(24_300L), "sung_screen_duet");
    this.step(MOVE_MS, () -> this.client.options.hideGui = true, null);

    // Enhanced LRC.
    this.sungPlace(look -> look.karaokeStyle = "DIM");
    this.step(1_500L, () -> {
      this.seek(1_000L);
      LyricsLibrary.get().force(LrcReader.parse(SUNG_LRC, SUNG_DURATION_MS));
    }, null);
    this.step(MOVE_MS, () -> this.expectWords("enhanced LRC is word timed", true), null);
    this.step(MOVE_MS, () -> this.seek(1_700L), "sung_lrc");

    // On the clock.
    this.step(MOVE_MS, () -> LyricsLibrary.get().force(TtmlReader.parse(SUNG_TTML, SUNG_DURATION_MS)), null);
    this.sungPlace(look -> look.karaokeStyle = "LIFT");
    this.step(1_400L, () -> this.play(800L), "sung_live_a");
    this.step(1_400L, () -> {
    }, "sung_live_b");
    this.step(MEASURE_MS, () -> this.play(0L), null, "word timed karaoke on the clock");

    // Off again.
    this.step(1_000L, () -> {
      Playback.get().force(null);
      LyricsLibrary.get().onSong(null);
      WorldStage.reset();
    }, null);
  }

  /**
   * A real song from the real service, the way any song comes: nothing here is told about word timings. It needs the
   * network, and with none it says so and goes on. No lyric text is kept here, only the name of a record that has them.
   */
  private void wordsFromLrclib() {
    this.sungPlace(look -> look.karaokeStyle = "DIM");
    this.step(5_000L, () -> this.lrclibSong(600L), null);
    this.step(MOVE_MS, () -> {
      TimedLyrics lyrics = LyricsLibrary.get().current();
      LyricsLibrary.State state = LyricsLibrary.get().state();
      if (state == LyricsLibrary.State.OFFLINE) {
        Env.LOG.warn(TAG + "the service could not be reached, so LRCLIB's word timings were not tried");
      } else if (lyrics == null || !lyrics.wordTimed()) {
        Env.LOG.error(TAG + "no word timings from LRCLIB, state " + state);
        end(9);
      } else {
        Env.LOG.info(TAG + "word timings from LRCLIB: " + lyrics.size() + " lines");
      }
    }, null);
    this.step(1_500L, () -> this.lrclibSong(1_050L), "lrclib_words_a");
    this.step(MOVE_MS, () -> this.lrclibSong(2_600L), "lrclib_words_b");
    // A record kept before word timings existed, and long enough ago: it plays as it was, and then gains them.
    this.step(1_000L, this::ageKeptRecord, null);
    this.step(1_500L, () -> this.seekOtherSong(), null);
    this.step(4_000L, () -> this.lrclibSong(1_050L), null);
    this.step(MOVE_MS, () -> {
      this.expectWords("an old kept record plays as it was", false);
      this.expectKeptWords("and has gained word timings on disk", true);
    }, null);
    this.step(1_500L, () -> this.seekOtherSong(), null);
    this.step(1_500L, () -> this.lrclibSong(1_050L), null);
    this.step(MOVE_MS, () -> this.expectWords("the next time it sings word by word", true), null);

    this.step(1_000L, () -> {
      Playback.get().force(null);
      LyricsLibrary.get().onSong(null);
      WorldStage.reset();
    }, null);
  }

  private void seekOtherSong() {
    NowPlaying other = new NowPlaying("Another Song", SUNG_ARTIST, "", SUNG_DURATION_MS, 0L, System.currentTimeMillis(),
        false, "Lyrica", null);
    Playback.get().force(other);
    LyricsLibrary.get().onSong(other);
  }

  /** The cache's file for the record in question, or null. */
  private static Path keptRecord() throws IOException {
    try (DirectoryStream<Path> kept = Files.newDirectoryStream(Env.dir().resolve("cache"), "*.json")) {
      for (Path file : kept) {
        JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (json.has("title") && LRCLIB_TITLE.equals(json.get("title").getAsString())) {
          return file;
        }
      }
    }

    return null;
  }

  private void ageKeptRecord() {
    try {
      Path file = keptRecord();
      if (file == null) {
        Env.LOG.warn(TAG + "no kept record to age, so looking for word timings later was not tried");
        return;
      }

      JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
      json.remove("lyricsfile");
      Files.writeString(file, json.toString());
      Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 40L * 86_400_000L));
    } catch (IOException failure) {
      Env.LOG.error(TAG + "could not age the kept record", failure);
      end(10);
    }
  }

  private void expectKeptWords(String what, boolean wanted) {
    try {
      Path file = keptRecord();
      boolean found = file != null && JsonParser.parseString(Files.readString(file)).getAsJsonObject().has("lyricsfile");
      this.report(what, wanted, found);
    } catch (IOException failure) {
      Env.LOG.error(TAG + what, failure);
      end(10);
    }
  }

  private void expectWords(String what, boolean wanted) {
    TimedLyrics lyrics = LyricsLibrary.get().current();
    this.report(what, wanted, lyrics != null && lyrics.wordTimed());
  }

  private void report(String what, boolean wanted, boolean found) {
    if (found != wanted) {
      Env.LOG.error(TAG + what + ": wanted " + wanted + ", found " + found);
      end(10);
    }

    Env.LOG.info(TAG + what + ": " + found);
  }

  private void lrclibSong(long positionMs) {
    NowPlaying song = new NowPlaying(LRCLIB_TITLE, LRCLIB_ARTIST, LRCLIB_ALBUM, LRCLIB_DURATION_MS, positionMs,
        System.currentTimeMillis(), false, "Lyrica", null);
    Playback.get().force(song);
    LyricsLibrary.get().onSong(song);
  }

  private void checkReaders() {
    List<String> failures = ReaderChecks.failures();
    for (String failure : failures) {
      Env.LOG.error(TAG + "reader check failed: " + failure);
    }

    Env.LOG.info(TAG + "reader checks: " + failures.size() + " failed");
    if (!failures.isEmpty()) {
      end(6);
    }
  }

  /** Clears the stage and moves, so that the next line finds its spot from here. */
  private void sungPlace(Consumer<Look> settings) {
    this.step(MOVE_MS, () -> {
      WorldStage.reset();
      Look look = this.plain();
      readable(look);
      look.karaoke = true;
      look.color2 = 0xFFB01F;
      settings.accept(look);
      this.goTo(new Spot(MIDDLE_X, FEET, OPEN_Z, 0.0F, -2.0F));
    }, null);
  }

  /** Holds the song still at this moment. */
  private void seek(long positionMs) {
    this.song(positionMs, false);
  }

  private void play(long fromMs) {
    this.song(fromMs, true);
  }

  private void song(long positionMs, boolean playing) {
    NowPlaying song = new NowPlaying(SUNG_TITLE, SUNG_ARTIST, "", SUNG_DURATION_MS, positionMs,
        System.currentTimeMillis(), playing, "Lyrica", null);
    Playback.get().force(song);
    LyricsLibrary.get().onSong(song);
  }

  /** One line in each script, with letters of the player's usual kind. Their letters come from the fonts on the PC. */
  private void languages() {
    String[][] lines = {
        {"arabic", "يحرق قلبي في هذه الليلة"},
        {"arabic_vowels", "يَا لَيْلُ الصَّبُّ مَتَى غَدُهُ"},
        {"arabic_mixed", "أحبك Baby كل يوم (دائما)"},
        {"hebrew", "הלילה הזה הוא שלנו"},
        {"russian", "Я помню чудное мгновенье"},
        {"greek", "Σ' αγαπώ για πάντα"},
        {"japanese", "君の名前を呼んでいる"},
        {"chinese", "月亮代表我的心"},
        {"korean", "너를 사랑해 오늘 밤"},
        {"thai", "ฉันรักเธอตลอดไป"},
        {"hindi", "तुम ही हो मेरी जान"},
    };
    for (String[] line : lines) {
      this.stand(OPEN_Z, LetterSmoke::readable, line[1]);
      this.step(600L, () -> {
      }, "lang_" + line[0]);
    }

    this.stand(OPEN_Z, look -> {
      readable(look);
      look.font = "POPPINS";
      look.upper = true;
      look.enter = "NONE";
      look.exit = "SHATTER";
    }, "يحرق قلبي");
    this.step(600L, () -> {
    }, "lang_arabic_poppins");
    this.step(MOVE_MS, () -> this.goTo(Spot.looking(MIDDLE_X + 6.0, FEET + 0.5, OPEN_Z + 5.0, MIDDLE_X, FEET + 2.0,
        OPEN_Z + AHEAD)), "lang_arabic_side");
  }

  /** The halo at night, where it shows most, standing and while the letters break. */
  private void halo() {
    this.step(MOVE_MS, () -> this.server("time set midnight"), null);
    Consumer<Look> glowing = look -> {
      readable(look);
      look.font = "COMIC_RELIEF";
      look.colorMode = "GRADIENT";
      look.color = 0xF48FB1;
      look.color2 = 0x4FC3F7;
      look.selfLit = true;
      look.sparkles = true;
      look.enter = "NONE";
      look.exit = "SHATTER";
    };
    for (String glow : new String[] {"SOFT", "BRIGHT"}) {
      this.stand(OPEN_Z, look -> {
        glowing.accept(look);
        look.glow = glow;
      }, SHORT_WORDS);
      this.step(500L, () -> {
      }, "halo_" + glow.toLowerCase(Locale.ROOT));
      this.step(MOVE_MS, () -> this.goTo(Spot.looking(MIDDLE_X + 7.0, FEET + 0.5, OPEN_Z + 6.0, MIDDLE_X, FEET + 2.0,
          OPEN_Z + AHEAD)), "halo_" + glow.toLowerCase(Locale.ROOT) + "_side");
    }

    this.placeFirst(OPEN_Z, look -> {
      glowing.accept(look);
      look.glow = "BRIGHT";
    });
    this.step(250L, () -> DemoLines.forceRun(1_200L, SHORT_WORDS), null);
    this.step(950L, () -> {
    }, null);
    this.step(90L, () -> {
    }, "shatter_start");
    this.step(170L, () -> {
    }, "shatter_early");
    this.step(230L, () -> {
    }, "shatter_mid");
    this.step(300L, () -> {
    }, "shatter_late");
    this.step(MOVE_MS, () -> {
      DemoLines.force(null);
      this.server("time set noon");
    }, null);
  }

  private void light() {
    this.step(MOVE_MS, () -> this.server("time set midnight"), null);
    this.stand(OPEN_Z, LetterSmoke::readable, WORDS);
    this.step(1_500L, () -> {
    }, "night_open");
    this.step(MOVE_MS, () -> {
      DemoLines.force(null);
      this.server("time set noon");
    }, null);
    this.stand(ROOM_Z, LetterSmoke::readable, WORDS);
    this.step(1_500L, () -> {
    }, "dark_room");
    this.step(MOVE_MS, () -> LookStore.get().glow = "BRIGHT", null);
    this.step(MOVE_MS, () -> {
    }, "dark_room_glow");
    this.step(MOVE_MS, () -> {
      LookStore.get().glow = "OFF";
      LookStore.get().selfLit = true;
    }, null);
    this.step(MOVE_MS, () -> {
    }, "dark_room_selflit");
  }

  private void motion() {
    // Mid-fade: the first word is about half way when the shot is taken.
    this.placeFirst(OPEN_Z, look -> {
      readable(look);
      look.enter = "FADE";
      look.exit = "FADE";
    });
    this.step(240L, () -> DemoLines.forceRun(1_400L, SHORT_WORDS), "fade_enter");
    this.step(1_230L, () -> {
    }, "fade_exit");
    this.step(600L, () -> {
    }, null);

    double lineZ = OPEN_Z + AHEAD;
    // The same fade from the side, where the walls would show through if they could.
    this.step(MOVE_MS, () -> {
      DemoLines.force(null);
      this.goTo(new Spot(MIDDLE_X, FEET, OPEN_Z, 0.0F, 2.0F));
    }, null);
    this.step(250L, () -> DemoLines.forceRun(6_000L, SHORT_WORDS), null);
    this.step(1_000L, () -> {
    }, null);
    this.step(MOVE_MS, () -> this.goTo(Spot.looking(MIDDLE_X + 8.0, FEET + 0.5, lineZ - 6.0, MIDDLE_X, FEET + 1.5, lineZ)),
        null);
    this.step(400L, () -> DemoLines.forceRun(6_000L, SHORT_WORDS), "fade_side");

    for (String style : new String[] {"DIM", "FILL", "SWEEP", "LIFT"}) {
      this.placeFirst(OPEN_Z, look -> {
        readable(look);
        look.karaoke = true;
        look.karaokeStyle = style;
        look.color2 = 0xFFB01F;
      });
      this.step(2_700L, () -> DemoLines.forceRun(6_000L, WORDS), "karaoke_" + style.toLowerCase(Locale.ROOT));
    }

    // The hinge goes down to the ground: caught half way over and low from the side.
    this.placeFirst(OPEN_Z, look -> {
      readable(look);
      look.enter = "RISE_UP";
      look.exit = "TIP_OVER";
    });
    this.step(450L, () -> DemoLines.forceRun(1_200L, SHORT_WORDS), "rise_mid");
    this.step(1_300L, () -> {
    }, "tip_mid");

    this.placeFirst(OPEN_Z, look -> {
      readable(look);
      look.exit = "SINK";
    });
    this.step(1_700L, () -> DemoLines.forceRun(1_200L, SHORT_WORDS), "sink_mid");

    this.placeFirst(OPEN_Z, look -> {
      readable(look);
      look.enter = "FLIP";
    });
    this.step(500L, () -> DemoLines.forceRun(6_000L, SHORT_WORDS), "flip_mid");
  }

  private void ground() {
    double low = FEET + 0.15;
    this.stand(OPEN_Z, LetterSmoke::readable, WORDS);
    double lineZ = OPEN_Z + AHEAD;
    this.shotFrom("ground_flat", Spot.looking(MIDDLE_X + 9.0, low, lineZ - 5.0, MIDDLE_X, FEET + 0.7, lineZ));

    this.stand(SLOPE_Z, LetterSmoke::readable, WORDS);
    lineZ = SLOPE_Z + AHEAD;
    this.shotFrom("ground_slope_front", new Spot(MIDDLE_X, FEET, SLOPE_Z, 0.0F, -2.0F));
    this.shotFrom("ground_slope_side", Spot.looking(MIDDLE_X - 15.0, low, lineZ - 8.0, MIDDLE_X - 1.0, FEET + 2.5, lineZ));

    this.stand(STEP_Z, LetterSmoke::readable, WORDS);
    lineZ = STEP_Z + AHEAD;
    this.shotFrom("ground_step_front", new Spot(MIDDLE_X, FEET, STEP_Z, 0.0F, -2.0F));
    this.shotFrom("ground_step_side", Spot.looking(MIDDLE_X + 14.0, low + 1.5, lineZ - 7.0, MIDDLE_X + 4.0, FEET + 2.3, lineZ));

    this.stand(WALL_Z, LetterSmoke::readable, WORDS);
    lineZ = WALL_Z + AHEAD;
    this.shotFrom("ground_wall_front", new Spot(MIDDLE_X, FEET, WALL_Z, 0.0F, -2.0F));
    this.shotFrom("ground_wall_side", Spot.looking(MIDDLE_X - 12.0, low, lineZ - 9.0, MIDDLE_X + 4.0, FEET + 2.3, lineZ));
  }

  private void depthAndTurn() {
    double lineZ = OPEN_Z + AHEAD;
    this.stand(OPEN_Z, LetterSmoke::readable, WORDS);
    this.step(MOVE_MS, () -> this.server(fill(2999, FLOOR + 1, (int) lineZ - 3, 3001, FLOOR + 7, (int) lineZ - 3, "stone")),
        null);
    this.shotFrom("depth_block", new Spot(MIDDLE_X, FEET, OPEN_Z, 0.0F, -2.0F));
    this.step(MOVE_MS, () -> this.server(fill(2999, FLOOR + 1, (int) lineZ - 3, 3001, FLOOR + 7, (int) lineZ - 3, "air")),
        null);

    this.shotFrom("turn_a", new Spot(MIDDLE_X, FEET, OPEN_Z, 0.0F, -2.0F));
    this.shotFrom("turn_b", new Spot(MIDDLE_X, FEET, OPEN_Z, 22.0F, -2.0F));
    this.shotFrom("turn_c", new Spot(MIDDLE_X, FEET, OPEN_Z, -22.0F, 8.0F));
  }

  private void looks() {
    String[] presets = {"neon_tube", "chrome", "poster_stack", "disco"};
    for (String preset : presets) {
      this.stand(OPEN_Z, look -> {
        Presets.byId(preset).apply(look);
        look.enter = "NONE";
        look.exit = "NONE";
      }, SHORT_WORDS);
      this.step(600L, () -> {
      }, "look_" + preset);
    }

    String[] fonts = {"PLAYFAIR", "PRESS_START", "COMIC_RELIEF", "DEFAULT"};
    for (String font : fonts) {
      this.stand(OPEN_Z, look -> {
        readable(look);
        look.font = font;
      }, "Gently swaying 你好 by the jetty");
      this.step(600L, () -> {
      }, "font_" + font.toLowerCase(Locale.ROOT));
    }

    this.stand(OPEN_Z, look -> {
      readable(look);
      look.outline = true;
      look.outlineColor = 0x101018;
      look.color = 0xFFD76A;
    }, SHORT_WORDS);
    this.step(600L, () -> {
    }, "outline");
  }

  private void fps() {
    double lineZ = OPEN_Z + AHEAD;
    // A warm-up, so that the first numbers do not carry the JIT's start.
    this.stand(OPEN_Z, LetterSmoke::readable, WORDS);
    this.step(4_000L, () -> {
    }, null);
    for (int round = 0; round < 2; round++) {
      // Off for real: without a forced line, whatever song plays on this PC would come up instead.
      this.step(1_500L, () -> {
        DemoLines.force(null);
        LookStore.get().showLyrics = false;
      }, null);
      this.step(MEASURE_MS, () -> {
      }, null, "words off, 12 blocks");
      this.step(1_500L, () -> {
        LookStore.get().showLyrics = true;
        line(WORDS);
      }, null);
      this.step(MEASURE_MS, () -> {
      }, null, "words on, 12 blocks, 3 rows");
    }

    for (int round = 0; round < 2; round++) {
      // Off for real: without a forced line, whatever song plays on this PC would come up instead.
      this.step(1_500L, () -> {
        DemoLines.force(null);
        LookStore.get().showLyrics = false;
      }, null);
      this.step(MEASURE_MS, () -> {
      }, null, "words off, 12 blocks");
      this.step(1_500L, () -> {
        LookStore.get().showLyrics = true;
        line(WORDS);
      }, null);
      this.step(MEASURE_MS, () -> {
      }, null, "words on, 12 blocks, 3 rows");
    }

    Spot near = new Spot(MIDDLE_X, FEET + 1.0, lineZ - 5.0, 0.0F, 3.0F);
    this.step(1_000L, () -> this.goTo(near), null);
    this.step(MEASURE_MS, () -> {
    }, null, "words on, 5 blocks, fine meshes");

    Spot side = Spot.looking(MIDDLE_X + 8.0, FEET + 0.5, lineZ - 7.0, MIDDLE_X, FEET + 2.2, lineZ);
    this.step(1_000L, () -> this.goTo(side), null);
    this.step(MEASURE_MS, () -> {
    }, null, "words on, from the side, walls showing");

    for (int round = 0; round < 2; round++) {
      this.measureOff();

      // The usual look of a fresh install.
      this.stand(OPEN_Z, look -> {
      }, WORDS);
      this.step(1_000L, () -> {
      }, null);
      this.step(MEASURE_MS, () -> {
      }, null, "words on, default look");

      this.measureOff();

      // The busiest ordinary thing: a line leaving while the next one arrives, faded over and over.
      this.placeFirst(OPEN_Z, look -> {
        readable(look);
        look.enter = "FADE";
        look.exit = "FADE";
      });
      this.step(MEASURE_MS, () -> DemoLines.forceRun(1_600L, WORDS, SHORT_WORDS, WORDS, SHORT_WORDS), null,
          "two lines crossing, fading");
    }

    this.measureOff();
    this.step(MOVE_MS, () -> LookStore.get().showLyrics = true, null);
  }

  private void measureOff() {
    this.step(1_000L, () -> {
      DemoLines.force(null);
      LookStore.get().showLyrics = false;
    }, null);
    this.step(MEASURE_MS, () -> {
    }, null, "words off, 12 blocks");
  }

  /** Every animation caught part-way, low from the side, so that anything going into the ground shows. */
  private void sweep() {
    double lineZ = OPEN_Z + AHEAD;
    Spot view = Spot.looking(MIDDLE_X + 9.0, FEET + 0.1, lineZ - 9.0, MIDDLE_X, FEET + 1.2, lineZ);
    String[] enters = {"DROP", "RISE_UP", "FLY_IN", "FLIP", "UNFOLD", "TYPE", "SCATTER_IN"};
    for (String name : enters) {
      this.placeFirst(OPEN_Z, look -> {
        readable(look);
        look.enter = name;
      });
      this.step(60L, () -> DemoLines.forceRun(6_000L, SHORT_WORDS), null);
      this.step(280L, () -> this.goTo(view), "enter_" + name.toLowerCase(Locale.ROOT));
    }

    String[] exits = {"SINK", "TIP_OVER", "BLOW_AWAY", "FLY_OUT", "SHATTER", "ERASE"};
    for (String name : exits) {
      this.placeFirst(OPEN_Z, look -> {
        readable(look);
        look.exit = name;
      });
      this.step(60L, () -> DemoLines.forceRun(500L, SHORT_WORDS), null);
      this.step(780L, () -> this.goTo(view), "exit_" + name.toLowerCase(Locale.ROOT));
    }

    String[] idles = {"BREATHE", "BOB", "SWAY", "WAVE", "RIPPLE", "JITTER", "FLICKER", "GLITCH", "ORBIT_LETTERS"};
    for (String name : idles) {
      this.placeFirst(OPEN_Z, look -> {
        readable(look);
        look.idle = name;
        look.idleAmount = 2.0F;
        look.idleSpeed = 2.0F;
      });
      this.step(STAND_MS, () -> DemoLines.forceRun(8_000L, SHORT_WORDS), null);
      this.step(MOVE_MS, () -> this.goTo(view), "idle_" + name.toLowerCase(Locale.ROOT));
    }

    String[] arrangements = {"WORDS", "STAIRS", "ARC", "STACK"};
    for (String name : arrangements) {
      this.stand(OPEN_Z, look -> {
        readable(look);
        look.arrange = name;
      }, WORDS);
      this.step(MOVE_MS, () -> {
      }, "arrange_" + name.toLowerCase(Locale.ROOT));
    }
  }

  /** The other places a line can be, and the settings that only show in some of them. */
  private void places() {
    double lineZ = OPEN_Z + AHEAD;
    this.stand(OPEN_Z, look -> {
      readable(look);
      look.place = "HOVER";
    }, SHORT_WORDS);
    this.step(500L, () -> {
    }, "place_hover");

    this.stand(OPEN_Z, look -> {
      readable(look);
      look.place = "FLOOR";
    }, SHORT_WORDS);
    this.step(MOVE_MS, () -> this.goTo(new Spot(MIDDLE_X, FEET + 5.0, OPEN_Z, 0.0F, 35.0F)), "place_floor");

    this.stand(OPEN_Z, look -> {
      readable(look);
      look.place = "ORBIT";
    }, SHORT_WORDS);
    this.step(500L, () -> {
    }, "place_orbit");

    this.stand(OPEN_Z, look -> {
      readable(look);
      look.place = "SKY";
    }, SHORT_WORDS);
    this.step(MOVE_MS, () -> this.goTo(new Spot(MIDDLE_X, FEET, OPEN_Z, 0.0F, -20.0F)), "place_sky");

    // Looking straight at the far wall of the dark room's stone, from outside it.
    this.placeFirst(ROOM_Z - 30.0, look -> {
      readable(look);
      look.place = "WALL";
    });
    this.step(MOVE_MS, () -> {
      this.goTo(new Spot(MIDDLE_X, FEET + 2.0, ROOM_Z - 30.0, 0.0F, 0.0F));
    }, null);
    this.step(STAND_MS, () -> line(SHORT_WORDS), "place_wall");

    // A line through a pillar and through walls, faint where it is hidden.
    this.stand(OPEN_Z, look -> {
      readable(look);
      look.throughWalls = true;
    }, WORDS);
    this.step(MOVE_MS, () -> this.server(fill(2999, FLOOR + 1, (int) lineZ - 3, 3001, FLOOR + 7, (int) lineZ - 3, "stone")),
        "through_walls");
    this.step(MOVE_MS, () -> this.server(fill(2999, FLOOR + 1, (int) lineZ - 3, 3001, FLOOR + 7, (int) lineZ - 3, "air")),
        null);

    // Rows around the line, which all have to end up above the ground too.
    this.stand(OPEN_Z, look -> {
      readable(look);
      look.nextLine = true;
      look.earlierLines = 1;
    }, null);
    this.step(MOVE_MS, () -> DemoLines.forceRun(8_000L, "Gently swaying by the jetty", "Going my way, quietly", "Yet again"), null);
    this.step(1_000L, () -> {
    }, null);
    this.step(9_000L, () -> {
    }, "rows_around");

    for (String font : new String[] {"BEBAS", "MONTSERRAT", "POPPINS", "RIGHTEOUS", "PLAYFAIR", "LOBSTER", "PACIFICO",
        "PRESS_START", "COMIC_RELIEF", "EAGLE_LAKE", "COMFORTAA", "JETBRAINS_MONO"}) {
      this.step(300L, () -> {
        Look look = LookStore.get();
        look.font = font;
        look.bold = false;
        Env.LOG.info(TAG + WorldStage.advanceReport(look, ADVANCE_CHARACTERS));
      }, null);
      this.step(300L, () -> {
        Look look = LookStore.get();
        look.bold = true;
        Env.LOG.info(TAG + WorldStage.advanceReport(look, ADVANCE_CHARACTERS));
      }, null);
    }
  }

  /** The same view in both detail levels, around where a letter would change from one to the other. */
  private void lod() {
    this.stand(OPEN_Z, LetterSmoke::readable, WORDS);
    this.step(MOVE_MS, () -> WorldStage.forceDetail(0), "lod_fine");
    this.step(MOVE_MS, () -> WorldStage.forceDetail(1), "lod_coarse");
    this.step(MOVE_MS, () -> WorldStage.forceDetail(-1), null);
  }

  private void disable() {
    this.stand(OPEN_Z, LetterSmoke::readable, WORDS);
    this.step(MOVE_MS, () -> {
    }, "disable_before");
    this.step(1_500L, () -> setAddonEnabled(false), "disable_off");
    this.step(1_500L, () -> setAddonEnabled(true), null);
    this.step(1_000L, () -> line(WORDS), "disable_back_on");
  }

  private static void setAddonEnabled(boolean enabled) {
    LoadedAddon addon = Laby.labyAPI().addonService().getAddon("lyrical").orElse(null);
    if (addon != null && addon.getInstance() instanceof LabyAddon<?> labyAddon) {
      labyAddon.configuration().enabled().set(enabled);
      Env.LOG.info(TAG + "addon enabled set to " + enabled + ", service says "
          + Laby.labyAPI().addonService().isEnabled("lyrical"));
    } else {
      Env.LOG.warn(TAG + "could not find the addon to switch");
    }
  }

  // What the steps do

  /** Moves to the spot, and from there has a line newly find its place in the given look. */
  private void stand(double z, Consumer<Look> settings, String text) {
    this.placeFirst(z, settings);
    if (text != null) {
      this.step(STAND_MS, () -> line(text), null);
    }
  }

  /** Clears the stage, sets the look and moves, so that the next line finds its spot from here. */
  private void placeFirst(double z, Consumer<Look> settings) {
    this.step(MOVE_MS, () -> {
      DemoLines.force(null);
      settings.accept(this.plain());
      this.goTo(new Spot(MIDDLE_X, FEET, z, 0.0F, -2.0F));
    }, null);
  }

  private void shotFrom(String name, Spot spot) {
    this.step(MOVE_MS, () -> this.goTo(spot), name);
  }

  private void step(long settleMs, Runnable action, String shot) {
    this.step(settleMs, action, shot, null);
  }

  private void step(long settleMs, Runnable action, String shot, String measure) {
    this.steps.add(new Step(settleMs, action, shot, measure));
  }

  private Look plain() {
    Look look = LookStore.get();
    Presets.byId(Presets.DEFAULT_ID).apply(look);
    look.showLyrics = true;
    look.outline = false;
    look.idle = "NONE";
    look.hideWhenPaused = false;
    look.earlierLines = 0;
    look.nextLine = false;
    look.breakDots = false;
    look.place = "STAND";
    look.distance = 12.0F;
    look.heightAdd = 0.0F;
    look.spread = 0.0F;
    look.facing = "FIXED";
    look.carryWhenFast = false;
    look.trailLength = 3;
    look.glow = "OFF";
    look.sparkles = false;
    look.karaoke = false;
    look.throughWalls = false;
    look.selfLit = false;
    look.offsetMs = 0L;
    look.cardOn = false;
    return look;
  }

  private static void line(String text) {
    DemoLines.force(null);
    DemoLines.force(text);
  }

  private void goTo(Spot spot) {
    this.server(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", spot.x, spot.y, spot.z, spot.yaw, spot.pitch));
  }

  private void server(String... commands) {
    IntegratedServer server = this.client.getSingleplayerServer();
    if (server == null) {
      return;
    }

    server.execute(() -> {
      CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
      for (String command : commands) {
        server.getCommands().performPrefixedCommand(source, command);
      }
    });
  }
}
