package net.splyrics.v1_21_11;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sun.management.ThreadMXBean;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.Unpooled;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.labymod.api.Laby;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.addon.LoadedAddon;
import net.labymod.api.configuration.labymod.main.laby.ingame.OwnNameTagMode;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.splyrics.api.LyricsBridge;
import net.splyrics.api.LyricsBridge.CardLook;
import net.splyrics.v1_21_11.hud.AlbumTexture;
import net.splyrics.v1_21_11.look.AccountProof;
import net.splyrics.v1_21_11.look.AtomicFile;
import net.splyrics.v1_21_11.look.CardStyle;
import net.splyrics.v1_21_11.look.Look;
import net.splyrics.v1_21_11.look.LookStore;
import net.splyrics.v1_21_11.look.Palette;
import net.splyrics.v1_21_11.look.StaffRoster;
import net.splyrics.v1_21_11.look.Presets;
import net.splyrics.v1_21_11.menu.StudioScreen;
import net.splyrics.v1_21_11.mixins.PlayerInfoTestAccess;
import net.splyrics.v1_21_11.song.NowPlaying;
import net.splyrics.v1_21_11.words.DemoLines;
import org.lwjgl.glfw.GLFW;

/**
 * Development only, active in the development environment when SPLYRICS_SMOKE.txt sits in the game folder: walks
 * through the places, the light and the menu on a test stage far from anything, takes a screenshot of each, and ends
 * the game. It changes the one look in memory and never saves it. With the word "authorcard" in the marker it tests
 * the card over the name instead, with "accountproof" what another player's card asks for, with "cardstyle" the card's
 * styles and the Staff page that sets them, and with "rolelist" the wave's colour, hiding a role and the published list.
 */
public final class DevSmoke {

  private static final String MARKER = "SPLYRICS_SMOKE.txt";
  private static final String TAG = "[SpLyrics smoke] ";
  private static final String WORLD = "devworld";
  private static final String DEV_ENVIRONMENT = "net.labymod.addon-dev-environment";
  private static final String ADDON_ID = "splyrics";
  private static final String AUTHOR_CARD = "authorcard";
  private static final String ACCOUNT_PROOF = "accountproof";
  private static final String CARD_STYLE = "cardstyle";
  private static final String HEAD_CARD = "headcard";
  private static final String ROLE_LIST = "rolelist";

  private static final long SCREEN_SHOWING_MS = 2_500L;
  private static final long WORLD_SETTLE_MS = 1_500L;
  private static final long GIVE_UP_MS = 480_000L;
  private static final long CHUNKS_MS = 8_000L;
  private static final long MOVE_MS = 1_000L;
  private static final long LINE_MS = 1_800L;
  private static final long ENTRANCE_MS = 300L;
  private static final long LAST_WRITES_MS = 2_000L;

  // Stage: 80 by 80 blocks around (2000, 2000), its ground at y = 200
  private static final int LOW = 1960;
  private static final int HIGH = 2039;
  private static final int FLOOR = 199;
  private static final int TOP = 230;
  // A fill may change 32768 blocks at most, and 80 x 80 x 5 is just under
  private static final int AIR_SLICE = 5;
  private static final int WALL_Z = 2020;
  private static final int WALL_HIGH = 8;
  private static final double EYE = 201.62;

  // At the default distance and without wander a line stands straight ahead of FRONT
  private static final Spot FRONT = new Spot(2000.5, 2000.5, 0.0F, 2.0F);
  private static final double LINE_X = 2000.5;
  private static final double LINE_Y = 200.8;
  private static final double LINE_Z = 2012.5;
  private static final Spot FAR_FRONT = new Spot(2000.5, 1978.5, 0.0F, 2.0F);
  private static final double FAR_LINE_Z = 1990.5;

  private static final int CARD_WINDOW_WIDTH = 1920;
  private static final int CARD_WINDOW_HEIGHT = 1080;
  private static final long CARD_MS = 1_400L;
  private static final double CLOSE_CAMERA = 3.0;
  private static final double USUAL_CAMERA = 4.0;
  private static final int GONE_TEST_FOV = 110;
  private static final String TEST_ROLE_LABEL = "splyrics.staff.role.developer";
  private static final int TEST_ROLE_COLOR = 0xFF5A36;
  private static final String TEST_ROLE_TITLE = "splyrics.staff.title.author";
  private static final UUID AN_AUTHOR = UUID.fromString("479708ff-6807-4154-9a10-370070a3f0df");
  private static final String AN_AUTHORS_NAME = "6O2";
  private static final int FAKE_PLAYER_ENTITY = -1_000_001;
  private static final Spot PROOF_SPOT = new Spot(2000.5, 2000.5, 0.0F, -12.0F);
  private static final double PROOF_NEAR_Z = 2004.0;
  private static final double PROOF_FAR_Z = 2010.0;
  private static final long LAPSES_IN_MS = 400L;
  private static final double PROOF_BEHIND_Z = 1990.0;
  private static final int FOLD_SHOTS = 6;
  private static final int FADE_IN_SHOTS = 4;
  // Past StaffTag's 5 s, after which a card nobody has seen starts anew
  private static final long UNSEEN_MS = 5_500L;
  private static final long LIVES_FOR_MS = 3_600_000L;
  private static final int COST_WARM_UP = 200_000;
  private static final int COST_CHECKS = 1_000_000;
  private static final String TEST_TITLE = "Midnight Signal";
  private static final String TEST_ARTIST = "Test Pressing";
  private static final String LONG_TITLE = "A Very Long Song Title That Keeps On Going Well Past Any Sensible Width";
  private static final String LONG_ARTIST = "The Band With The Equally Long Name";

  private static final String[] PRESET_IDS = {"neon_tube", "chrome", "ember", "poster_stack", "disco", "stone"};
  private static final String[] MENU_PAGES = {"looks", "type", "colour", "solid", "motion", "place", "card"};

  private static boolean checked;
  private static DevSmoke run;

  private enum Phase { TITLE, JOINING, STEPS, DONE }

  private record Step(long settleMs, Runnable action, String shot) {
  }

  private record Spot(double x, double z, float yaw, float pitch) {

    static Spot facing(double x, double z, double atX, double atY, double atZ) {
      double dx = atX - x;
      double dz = atZ - z;
      float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
      float pitch = (float) -Math.toDegrees(Math.atan2(atY - EYE, Math.sqrt(dx * dx + dz * dz)));
      return new Spot(x, z, yaw, pitch);
    }
  }

  private final Minecraft client;
  private final long bornMs = System.currentTimeMillis();
  private final List<Step> steps = new ArrayList<>();

  private Phase phase = Phase.TITLE;
  private long waitingSinceMs;
  private int index;
  private boolean stepBegun;
  private long stepBeganMs;

  private boolean authorCard;
  private boolean accountProof;
  private boolean testRoleOn = true;
  private RemotePlayer fakePlayer;
  private PublicKey testKey;
  private long burstBeganMs;
  private boolean cardStyle;
  private boolean headCard;
  private boolean roleList;
  private HttpServer listServer;
  private volatile int listStatus = 200;
  private volatile String listBody = "";
  private boolean roleOnCard;
  private String testLabel = TEST_ROLE_LABEL;
  private CardLook testLook = CardLook.BADGE;
  private NowPlaying styleSong;
  private byte[] coverArt;
  private byte[] otherCoverArt;
  private NowPlaying fakeSong;
  private String testTitle = TEST_ROLE_TITLE;
  private boolean holdSneak;
  private OwnNameTagMode ownNameBefore;
  private int fovBefore;
  private int guiScaleBefore;

  private DevSmoke(Minecraft client) {
    this.client = client;
  }

  /** At the end of a frame, so a screenshot gets all of it. */
  public static void frame() {
    if (!checked) {
      checked = true;
      Minecraft client = Minecraft.getInstance();
      Path marker = client.gameDirectory.toPath().resolve(MARKER);
      if (Boolean.getBoolean(DEV_ENVIRONMENT) && Files.exists(marker)) {
        LookStore.holdSaves();
        // The test runs in a window nobody is looking at, and the pause screen would cover everything
        client.options.pauseOnLostFocus = false;
        DevSmoke test = new DevSmoke(client);
        String wanted = readMarker(marker);
        test.accountProof = ACCOUNT_PROOF.equals(wanted);
        test.testRoleOn = !test.accountProof;
        test.headCard = HEAD_CARD.equals(wanted);
        test.roleList = ROLE_LIST.equals(wanted);
        test.cardStyle = test.headCard || test.roleList || CARD_STYLE.equals(wanted);
        test.authorCard = test.accountProof || test.cardStyle || AUTHOR_CARD.equals(wanted);
        if (test.authorCard) {
          test.coverArt = makeCover(false);
          test.otherCoverArt = makeCover(true);
          // Big enough to judge the card; out of sight, so a test running beside a real game does not get in its way
          client.getWindow().setWindowed(CARD_WINDOW_WIDTH, CARD_WINDOW_HEIGHT);
          GLFW.glfwHideWindow(client.getWindow().handle());
        }

        run = test;
        Env.LOG.info(TAG + "active");
      }
    }

    if (run != null) {
      try {
        run.advance();
      } catch (RuntimeException failure) {
        Env.LOG.error(TAG + "failed", failure);
        run.quit("failed");
      }
    }
  }

  /** Whether this run plays its own made-up song instead of what plays on the PC. */
  public static boolean overridesSong() {
    return run != null && run.authorCard;
  }

  public static NowPlaying song() {
    return run == null ? null : run.fakeSong;
  }

  /**
   * Gives the test account a role in the card test, so the card can be seen without being on the team. Inert in a real
   * game: {@code run} needs the development environment as well as the marker, and this reads no file and asks nobody.
   */
  public static LyricsBridge.StaffRole testRole(UUID playerId) {
    DevSmoke test = run;
    if (test == null || !test.authorCard || !test.testRoleOn || !Boolean.getBoolean(DEV_ENVIRONMENT) || playerId == null
        || !playerId.equals(test.client.getUser().getProfileId())) {
      return null;
    }

    return new LyricsBridge.StaffRole("developer", test.testLabel, TEST_ROLE_COLOR, test.testTitle);
  }

  /** The look of the test account's card in the card test, and the plain look in any other case. */
  public static CardLook testLook(UUID playerId) {
    DevSmoke test = run;
    if (test == null || !test.authorCard || !Boolean.getBoolean(DEV_ENVIRONMENT) || playerId == null
        || !playerId.equals(test.client.getUser().getProfileId())) {
      return CardLook.PLAIN;
    }

    return test.testLook;
  }

  /** Whether the test account has its role on the song card, in the head card test only. */
  public static boolean testRoleOnCard(UUID playerId) {
    DevSmoke test = run;
    return test != null && (test.headCard || test.roleList) && test.roleOnCard && Boolean.getBoolean(DEV_ENVIRONMENT)
        && playerId != null
        && playerId.equals(test.client.getUser().getProfileId());
  }

  /**
   * Lets the card style test show and use the Staff page, because the test account is no team member. Inert in a real
   * game: {@code run} needs the development environment as well as the marker, and it asks for both again here.
   */
  public static boolean staffPageTest() {
    DevSmoke test = run;
    return test != null && test.cardStyle && Boolean.getBoolean(DEV_ENVIRONMENT);
  }

  private static String readMarker(Path marker) {
    try {
      return Files.readString(marker).strip();
    } catch (IOException e) {
      return "";
    }
  }

  private void advance() {
    long now = System.currentTimeMillis();
    if (this.authorCard) {
      this.client.options.keyShift.setDown(this.holdSneak);
    }

    if (this.phase != Phase.DONE && now - this.bornMs > GIVE_UP_MS) {
      this.quit("gave up in " + this.phase);
      return;
    }

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

    if (now - this.waitingSinceMs >= SCREEN_SHOWING_MS) {
      Env.LOG.info(TAG + "opening " + WORLD);
      this.phase = Phase.JOINING;
      this.waitingSinceMs = 0L;
      this.client.createWorldOpenFlows().openWorld(WORLD, () -> this.quit("world refused"));
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
      this.buildSteps();
      this.phase = Phase.STEPS;
    }
  }

  private void runSteps(long now) {
    if (this.client.level == null || this.client.player == null) {
      this.quit("lost the world");
      return;
    }

    if (this.index >= this.steps.size()) {
      Env.LOG.info(TAG + "done");
      this.phase = Phase.DONE;
      this.client.stop();
      return;
    }

    Step step = this.steps.get(this.index);
    if (!this.stepBegun) {
      this.stepBegun = true;
      this.stepBeganMs = now;
      step.action.run();
      return;
    }

    if (now - this.stepBeganMs >= step.settleMs) {
      if (step.shot != null) {
        this.shoot(step.shot);
      }

      this.index++;
      this.stepBegun = false;
    }
  }

  private void quit(String why) {
    Env.LOG.warn(TAG + "stopping: " + why);
    this.phase = Phase.DONE;
    this.client.stop();
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

  private void buildSteps() {
    if (this.accountProof) {
      this.buildProofSteps();
      return;
    }

    if (this.roleList) {
      this.buildListSteps();
      return;
    }

    if (this.headCard) {
      this.buildHeadSteps();
      return;
    }

    if (this.cardStyle) {
      this.buildStyleSteps();
      return;
    }

    if (this.authorCard) {
      this.buildCardSteps();
      return;
    }

    this.buildStage();
    this.standShots();
    this.placeShots();
    this.lightShots();
    this.presetShots();
    this.menuShots();
    this.step(LAST_WRITES_MS, () -> {
    }, null);
  }

  private void buildStage() {
    this.step(CHUNKS_MS, () -> this.server(
        String.format(Locale.ROOT, "forceload add %d %d %d %d", LOW, LOW, HIGH, HIGH),
        "gamemode creative @a",
        "effect clear @a",
        "time set noon",
        "weather clear"), null);
    // The chunks may still have been loading the first time; filling is harmless when repeated. Only then does anybody
    // go there, because the player once fell out of the world waiting for the ground.
    this.step(3_000L, this::fillStage, null);
    this.step(3_000L, () -> {
      this.fillStage();
      this.server("kill @e[type=!player]");
      this.goTo(FRONT);
    }, null);
  }

  private void fillStage() {
    List<String> commands = new ArrayList<>();
    commands.add(fill(FLOOR, FLOOR, LOW, HIGH, "grass_block"));
    for (int y = FLOOR + 1; y <= TOP; y += AIR_SLICE) {
      commands.add(fill(y, Math.min(TOP, y + AIR_SLICE - 1), LOW, HIGH, "air"));
    }

    commands.add(fill(FLOOR + 1, FLOOR + WALL_HIGH, WALL_Z, WALL_Z, "stone"));
    this.server(commands.toArray(new String[0]));
  }

  private static String fill(int fromY, int toY, int fromZ, int toZ, String block) {
    return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", LOW, fromY, fromZ, HIGH, toY, toZ, block);
  }

  private void standShots() {
    this.shot("stand_front", FRONT, look -> {
    }, "Standing In The World");

    // Seven to the left and a little to the reader's side: exactly along its plane a line shows nothing but its edge
    this.step(1_500L, () -> this.goTo(Spot.facing(LINE_X + 7.0, LINE_Z - 3.0, LINE_X, LINE_Y, LINE_Z)), "stand_side");

    this.step(MOVE_MS, () -> this.goTo(FAR_FRONT), null);
    this.step(1_200L, () -> line("Seen From Behind"), null);
    this.step(1_500L, () -> this.goTo(Spot.facing(LINE_X + 12.0, FAR_LINE_Z + 16.0, LINE_X, LINE_Y, FAR_LINE_Z)),
        "stand_behind");

    this.step(MOVE_MS, () -> {
      this.plain();
      this.goTo(FRONT);
      this.server("time set midnight");
    }, null);
    this.step(2_500L, () -> line("Dim At Night"), "stand_night");
    this.step(500L, () -> this.server("time set noon"), null);

    this.step(MOVE_MS, () -> {
      this.plain().enter = "RISE_UP";
      this.goTo(FRONT);
    }, null);
    this.step(ENTRANCE_MS, () -> DemoLines.forceRun(6_000L, "Caught Rising Up"), "enter_mid");
  }

  private void placeShots() {
    this.shot("hover", FRONT, look -> look.place = "HOVER", "Floating At Eye Height");
    this.shot("sky", new Spot(FRONT.x, FRONT.z, 0.0F, -20.0F), look -> look.place = "SKY", "Written On The Sky");
    this.shot("wall", new Spot(LINE_X, 2012.5, 0.0F, 0.0F), look -> look.place = "WALL", "Painted On The Wall");
    this.shot("floor", new Spot(FRONT.x, FRONT.z, 0.0F, 40.0F), look -> look.place = "FLOOR", "Road Marking Here");
    this.shot("orbit", FRONT, look -> look.place = "ORBIT", "Going Round You");

    // One run of lyrics, because new lyrics clear the stage and a trail has to keep its older lines
    this.step(MOVE_MS, () -> {
      this.plain().place = "TRAIL";
      this.goTo(FRONT);
    }, null);
    this.step(2_500L, () -> DemoLines.forceRun(3_000L, "First Line Stays", "Second Line Stays", "Third Line Stays"), null);
    this.step(3_000L, () -> this.goTo(new Spot(2006.5, 2006.5, 0.0F, 2.0F)), null);
    this.step(2_000L, () -> this.goTo(new Spot(1994.5, 2012.5, -10.0F, 2.0F)), null);
    this.step(1_200L, () -> this.goTo(Spot.facing(1985.5, 1990.5, 2000.5, LINE_Y, 2018.0)), "trail");

    this.shot("screen", FRONT, look -> look.place = "SCREEN", "Flat On The Screen");
  }

  private void lightShots() {
    this.shot("glow", FRONT, look -> look.glow = "BRIGHT", "Glowing Letters");
    this.shot("sparkles", FRONT, look -> look.sparkles = true, "Sparkling Letters");

    // A forced line lasts for hours, and its karaoke would barely have begun
    this.step(MOVE_MS, () -> {
      Look look = this.plain();
      look.karaoke = true;
      look.karaokeStyle = "SWEEP";
      this.goTo(FRONT);
    }, null);
    this.step(2_700L, () -> DemoLines.forceRun(6_000L, "Singing Along Now With Me"), "karaoke_sweep");
  }

  private void presetShots() {
    for (String id : PRESET_IDS) {
      this.shot("preset_" + id, FRONT, look -> Presets.byId(id).apply(look), "Every Look Its Own");
    }
  }

  private void menuShots() {
    this.shot(null, FRONT, look -> {
    }, "The Desk Below");
    for (String page : MENU_PAGES) {
      this.step(1_500L, () -> StudioScreen.openOn(page), "menu_" + page);
    }
  }

  /** Moves first and only then starts the line, because a line finds its spot from where the camera is. */
  private void shot(String name, Spot spot, Consumer<Look> settings, String text) {
    this.step(MOVE_MS, () -> {
      settings.accept(this.plain());
      this.goTo(spot);
    }, null);
    this.step(LINE_MS, () -> line(text), name);
  }

  private void step(long settleMs, Runnable action, String shot) {
    this.steps.add(new Step(settleMs, action, shot));
  }

  /** The song above the name, the title below it, the pause both ways, and the two pages that set them. */
  private void buildHeadSteps() {
    this.buildStage();
    this.step(1_000L, this::prepareCardTest, null);
    this.step(2_000L, () -> this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA), "hc_title_only");
    this.step(CARD_MS, () -> this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt), "hc_song");
    this.step(CARD_MS, () -> this.fakeSong = song(TEST_TITLE, TEST_ARTIST, false, 200_000L, this.coverArt), "hc_paused");
    this.step(CARD_MS, () -> LookStore.get().pausedCard = "HIDE", "hc_paused_hidden");
    this.step(CARD_MS, () -> {
      LookStore.get().pausedCard = "SHOW";
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      this.testLook = new CardLook(0.95F, CardLook.PLAIN.panelRgb(), true, 5.0F, 1.0F, false, false, false);
    }, "hc_title_card");
    this.step(CARD_MS, () -> {
      this.testLook = CardLook.BADGE;
      this.testTitle = null;
    }, "hc_label_only");
    this.step(CARD_MS, () -> {
      this.testTitle = TEST_ROLE_TITLE;
      this.fakeSong = song(LONG_TITLE, LONG_ARTIST, true, 200_000L, this.coverArt);
    }, "hc_long");
    this.step(CARD_MS, () -> {
      this.testLabel = "مطور";
      this.testTitle = "المؤلف";
      this.fakeSong = song("يحرق قلبي", "وفية", true, 200_000L, this.coverArt);
    }, "hc_arabic");
    this.step(CARD_MS, () -> {
      this.testLabel = TEST_ROLE_LABEL;
      this.testTitle = null;
    }, "hc_no_author");
    this.step(CARD_MS, () -> {
      this.testTitle = TEST_ROLE_TITLE;
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      this.roleOnCard = true;
    }, "hc_role_on_card");
    this.step(CARD_MS, () -> this.fakeSong = null, "hc_role_on_card_no_song");
    this.step(CARD_MS, () -> {
      this.roleOnCard = false;
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      LookStore.get().headPlace = "UNDER";
    }, "hc_under_plain");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, 16.0), "hc_under_far");
    this.step(CARD_MS, () -> {
      LookStore.get().headPlace = "OVER";
      this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA);
    }, null);
    this.step(CARD_MS, () -> {
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      LookStore.get().ownCard = false;
    }, "hc_own_card_off");
    this.step(CARD_MS, () -> {
      LookStore.get().ownCard = true;
      this.camera(CameraType.THIRD_PERSON_BACK, USUAL_CAMERA);
    }, "hc_usual_4");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, 16.0), "hc_far_16");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA), null);
    this.step(1_000L, () -> StudioScreen.openOn("head"), "hc_page_head");
    this.step(1_000L, () -> {
      LookStore.get().pausedCard = "HIDE";
      LookStore.get().showShared = false;
      LookStore.get().headPlace = "UNDER";
    }, "hc_page_changed");
    this.step(1_000L, () -> {
      if (this.client.screen instanceof StudioScreen open) {
        open.resetEverything();
      }

      Look look = LookStore.get();
      Env.LOG.info(TAG + "reset all: paused " + look.pausedCard + ", show shared " + look.showShared + ", place "
          + look.headPlace);
    }, "hc_page_reset");
    this.step(1_000L, () -> StudioScreen.openOn("staff"), "hc_page_staff");
    this.step(1_000L, () -> {
      String id = "developer";
      boolean renamed = StaffRoster.renameRole(id, "dev");
      StaffRoster.setRoleLabel("dev", "Developer");
      StaffRoster.setRolePanel("staff", true);
      Env.LOG.info(TAG + "renamed " + renamed + ", taken name refused " + !StaffRoster.renameRole("dev", "staff")
          + ", changes " + StaffRoster.localChanges() + ", author holds " + StaffRoster.member(AN_AUTHOR).roleId()
          + ", list " + StaffRoster.exportJson().replace("\n", " "));
    }, "hc_page_staff_renamed");
    this.step(1_000L, () -> {
      StaffRoster.discardLocal();
      Env.LOG.info(TAG + "after discarding " + StaffRoster.localChanges() + " changes, author holds "
          + StaffRoster.member(AN_AUTHOR).roleId());
      this.client.setScreen(null);
    }, null);
    this.step(LAST_WRITES_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, USUAL_CAMERA), null);
  }

  // The wave's colour against the role's, the ways to hide a role, and the published list

  private static final UUID LIST_TESTER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final String GOOD_LIST = """
      {"roles":[{"id":"tester","label":"Tester","color":"#00FF00","panel":true},
                {"id":"quiet","label":"Quiet","color":"#0000FF","style":{"place":"HIDDEN"}}],
       "players":{"00000000-0000-4000-8000-0000000000aa":{"name":"Tester","role":"tester"},
                  "00000000-0000-4000-8000-0000000000bb":{"name":"Quiet","role":"quiet"}}}""";

  private void buildListSteps() {
    this.buildStage();
    this.step(1_000L, () -> {
      this.prepareCardTest();
      this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA);
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      this.roleOnCard = true;
      LookStore.get().headAccent = 0x7ED957;
    }, null);
    this.step(2_000L, () -> {
    }, "rl_static");
    this.step(CARD_MS, () -> LookStore.get().headAccentMode = "ALBUM", "rl_album");
    this.step(CARD_MS, () -> this.fakeSong = song("Second Song", "Other Band", true, 200_000L, this.otherCoverArt),
        "rl_album_other");
    this.step(CARD_MS, () -> this.fakeSong = song("No Cover Song", "Other Band", false, 200_000L, null),
        "rl_album_no_cover_paused");
    this.step(500L, this::logAccents, null);

    this.step(CARD_MS, () -> {
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      LookStore.get().showOwnRole = false;
    }, "rl_own_role_off");
    this.step(CARD_MS, () -> {
      LookStore.get().showOwnRole = true;
      LookStore.get().staffBadges = false;
    }, "rl_all_roles_off");
    this.step(CARD_MS, () -> {
      LookStore.get().staffBadges = true;
      this.roleOnCard = false;
    }, "rl_role_under");
    this.step(500L, this::logHiding, null);
    // At the smallest GUI scale, where every strip of a page is in view
    this.step(1_000L, () -> {
      this.guiScaleBefore = this.client.options.guiScale().get();
      this.client.options.guiScale().set(1);
      this.client.resizeDisplay();
      StudioScreen.openOn("head");
    }, "rl_page_head");
    this.step(1_000L, () -> StudioScreen.openOn("staff"), "rl_page_staff");
    this.step(500L, () -> this.client.setScreen(null), null);

    this.step(1_000L, this::startListServer, null);
    this.listStep("good", 200, GOOD_LIST);
    this.listStep("not json", 200, "<html>Bad gateway</html>");
    this.listStep("an array", 200, "[1,2,3]");
    this.listStep("gone", 404, "");
    this.listStep("no roles", 200, "{\"roles\":[],\"players\":{}}");
    this.listStep("too long", 200, " ".repeat(600_000));
    this.step(500L, () -> {
      StaffRoster.setRoleColor("tester", 0x112233);
      Env.LOG.info(TAG + "changed a role here: changes " + StaffRoster.localChanges());
    }, null);
    this.listStep("the change published", 200, GOOD_LIST.replace("#00FF00", "#112233"));
    this.step(500L, () -> {
      StaffRoster.takeCopyForTest("{\"roles\":[{\"id\":\"mine\",\"label\":\"Mine\",\"panel\":true}],\"players\":{"
          + "\"" + BAD_TWO + "\":{\"name\":\"Me\",\"role\":\"mine\",\"panel\":true}}}");
      this.logList("a copy edited by hand");
      Env.LOG.info(TAG + "that copy gives the page to its player: " + StaffRoster.panelGrantedTo(BAD_TWO));
    }, null);
    this.listStep("the test account on it", 200, GOOD_LIST.replace("\"players\":{",
        "\"players\":{\"" + this.client.getUser().getProfileId() + "\":{\"name\":\"Me\",\"role\":\"tester\"},"));
    this.step(1_000L, () -> StudioScreen.openOn("head"), "rl_page_head_member");
    this.step(1_000L, () -> StudioScreen.openOn("staff"), "rl_page_staff_published");
    this.step(500L, () -> this.client.setScreen(null), null);
    this.listStep("after the edited copy", 200, GOOD_LIST);
    this.step(LAST_WRITES_MS, this::endListTest, null);
  }

  private void listStep(String name, int status, String body) {
    this.step(1_000L, () -> {
      this.listStatus = status;
      this.listBody = body;
      StaffRoster.fetchPublishedForTest("http://127.0.0.1:" + this.listServer.getAddress().getPort() + "/staff.json");
    }, null);
    this.step(50L, () -> this.logList(name), null);
  }

  private void startListServer() {
    try {
      this.listServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }

    this.listServer.createContext("/", exchange -> {
      byte[] body = this.listBody.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(this.listStatus, body.length == 0 ? -1L : body.length);
      if (body.length > 0) {
        exchange.getResponseBody().write(body);
      }

      exchange.close();
    });
    this.listServer.start();
    Env.LOG.info(TAG + "before any download: state " + StaffRoster.publishedState() + ", author has the page "
        + StaffRoster.panelGrantedTo(AN_AUTHOR));
  }

  private void logList(String name) {
    StringBuilder ids = new StringBuilder();
    for (StaffRoster.Role role : StaffRoster.roles()) {
      ids.append(role.id()).append(' ').append(Palette.hex(role.color())).append(", ");
    }

    Env.LOG.info(TAG + "list after " + name + ": state " + StaffRoster.publishedState() + ", roles " + ids
        + "changes " + StaffRoster.localChanges() + ", tester has the page " + StaffRoster.panelGrantedTo(LIST_TESTER)
        + ", author has the page " + StaffRoster.panelGrantedTo(AN_AUTHOR)
        + ", tester's role " + (StaffRoster.roleOf(LIST_TESTER) != null)
        + ", quiet's role " + (StaffRoster.roleOf(UUID.fromString("00000000-0000-4000-8000-0000000000bb")) != null)
        + ", quiet stands " + StaffRoster.placeOfMember(UUID.fromString("00000000-0000-4000-8000-0000000000bb")));
  }

  private void endListTest() {
    this.listServer.stop(0);
    AtomicFile.flush();
    Path copy = Env.dir().resolve("staff_published.json");
    try {
      Env.LOG.info(TAG + "saved copy is the last good download: " + Files.readString(copy).equals(GOOD_LIST));
      Files.deleteIfExists(copy);
    } catch (IOException e) {
      Env.LOG.warn(TAG + "no saved copy: " + e);
    }

    StaffRoster.discardLocal();
    this.client.options.guiScale().set(this.guiScaleBefore);
    this.client.resizeDisplay();
    this.camera(CameraType.THIRD_PERSON_BACK, USUAL_CAMERA);
  }

  /** The colours from the bridge, on one's own song and on what somebody else sends, without a second player to send it. */
  private void logAccents() {
    Look look = LookStore.get();
    LyricsBridge bridge = VersionedLyricsBridge.get();
    LyricsBridge.LocalSong other = new LyricsBridge.LocalSong("Their Song", "Their Band", 0L, 0L, 0L, true, null);
    byte[] red = new byte[LyricsBridge.COVER_THUMB_BYTES];
    for (int i = 0; i < red.length; i += 2) {
      red[i] = (byte) 0xF8;
    }

    Env.LOG.info(TAG + "album: a red cover sent " + Palette.hex(bridge.songCardAccent(other, red)) + ", no cover sent "
        + Palette.hex(bridge.songCardAccent(other, null)) + ", own song "
        + Palette.hex(bridge.songCardAccent(bridge.localSong(), null)));
    look.headAccentMode = "STATIC";
    Env.LOG.info(TAG + "static: a red cover sent " + Palette.hex(bridge.songCardAccent(other, red)) + ", own song "
        + Palette.hex(bridge.songCardAccent(bridge.localSong(), null)));
    look.headAccentMode = "ALBUM";
  }

  private void logHiding() {
    StaffRoster.restyleMember(AN_AUTHOR, style -> style.withPlace(CardStyle.HIDDEN));
    Env.LOG.info(TAG + "player hidden: role " + (StaffRoster.roleOf(AN_AUTHOR) != null) + ", stands "
        + StaffRoster.placeOfMember(AN_AUTHOR) + ", other author's role " + (StaffRoster.roleOf(ANOTHER_AUTHOR) != null)
        + ", in the list " + StaffRoster.exportJson().contains("\"place\": \"HIDDEN\""));
    StaffRoster.previewMember(AN_AUTHOR);
    UUID self = this.client.getUser().getProfileId();
    Env.LOG.info(TAG + "previewing that player: previewing " + StaffRoster.previewing() + ", role on me "
        + (StaffRoster.roleOf(self) != null));
    StaffRoster.stopPreview();
    AtomicFile.flush();
    StaffRoster.reloadLocalForTest();
    Env.LOG.info(TAG + "read back from the file: stands " + StaffRoster.placeOfMember(AN_AUTHOR));
    StaffRoster.discardLocal();
    StaffRoster.restyleRole("developer", style -> style.withPlace(CardStyle.HIDDEN));
    StaffRoster.restyleMember(ANOTHER_AUTHOR, style -> style.withPlace(CardStyle.CARD));
    Env.LOG.info(TAG + "role hidden: its player " + (StaffRoster.roleOf(AN_AUTHOR) != null)
        + ", one of them set to the card " + (StaffRoster.roleOf(ANOTHER_AUTHOR) != null));
    StaffRoster.discardLocal();
    Env.LOG.info(TAG + "discarded: changes " + StaffRoster.localChanges() + ", role " + (StaffRoster.roleOf(AN_AUTHOR) != null)
        + "; old list says onCard: " + CardStyle.read(JsonParser.parseString("{\"onCard\":true}")).place());
  }

  // The card over the name, seen on the test account's own player in third person

  private void buildCardSteps() {
    this.buildStage();
    this.step(1_000L, this::prepareCardTest, null);

    // No song, close up, day
    this.step(2_000L, () -> this.camera(CameraType.THIRD_PERSON_BACK, CLOSE_CAMERA), "card_day_back_nosong");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA), "card_day_front_nosong");

    // With a song and its cover
    this.step(CARD_MS, () -> this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt),
        "card_day_front_song");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, CLOSE_CAMERA), "card_day_back_song");

    // A song changing, caught in its fade, then long names, a pause, no cover
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA), null);
    this.step(90L, () -> this.fakeSong = song("Second Song", "Other Band", true, 200_000L, this.otherCoverArt),
        "card_fade_mid");
    this.step(CARD_MS, () -> {
    }, "card_second_cover");
    this.step(CARD_MS, () -> this.fakeSong = song(LONG_TITLE, LONG_ARTIST, true, 200_000L, this.coverArt),
        "card_long_names");
    this.step(CARD_MS, () -> this.fakeSong = song(TEST_TITLE, TEST_ARTIST, false, 200_000L, this.coverArt),
        "card_paused");
    this.step(CARD_MS, () -> this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, null),
        "card_song_no_cover");

    // Night
    this.step(CARD_MS, () -> {
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      this.server("time set midnight");
    }, "card_night_front_song");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, CLOSE_CAMERA), "card_night_back_song");

    // Distance: the usual third person, the full card as far as it goes, then the label alone, then nothing
    this.step(CARD_MS, () -> {
      this.server("time set noon");
      this.camera(CameraType.THIRD_PERSON_BACK, USUAL_CAMERA);
    }, "card_usual_4");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, 8.0), "card_mid_8");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, 11.0), "card_mid_11");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, 16.0), "card_mid_16");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, 20.0), "card_mid_20");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, 30.0), "card_far_30");
    // The camera stops at about 32 blocks, so a wide field of view stands in for further than that
    this.step(CARD_MS, () -> {
      this.fovBefore = this.client.options.fov().get();
      this.client.options.fov().set(GONE_TEST_FOV);
    }, "card_far_gone");
    this.step(CARD_MS, () -> this.client.options.fov().set(this.fovBefore), null);

    // Sneaking: the card fades out as the name goes discreet, and comes back on standing up
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA), null);
    this.step(70L, () -> this.holdSneak = true, "card_sneak_fading");
    this.step(90L, () -> {
    }, "card_sneak_folding");
    this.step(CARD_MS, () -> {
    }, "card_sneak");
    this.step(CARD_MS, () -> this.holdSneak = false, "card_stood_up");

    // No song and no title: one line
    this.step(CARD_MS, () -> {
      this.fakeSong = null;
      this.testTitle = null;
    }, "card_one_line");
    this.step(CARD_MS, () -> {
      this.testTitle = TEST_ROLE_TITLE;
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
    }, null);

    // A menu's preview of the player must leave the card in the world alone, and it must not have to fade back in after
    this.step(500L, () -> this.server("gamemode survival @a"), null);
    this.step(CARD_MS, this::openInventory, "card_behind_menu");
    this.step(90L, this::closeInventory, "card_menu_closed");
    this.step(500L, () -> this.server("gamemode creative @a"), null);

    // Each way of switching it off
    this.step(CARD_MS, () -> this.addonEnabled(false), "card_addon_disabled");
    this.step(CARD_MS, () -> this.addonEnabled(true), "card_addon_enabled_again");
    this.step(CARD_MS, () -> LookStore.get().staffBadges = false, "card_badges_off");
    this.step(CARD_MS, () -> LookStore.get().staffBadges = true, null);

    // LabyMod's own switch for tags over the local player
    this.step(CARD_MS, () -> this.ownName(OwnNameTagMode.OFF), "card_own_name_off");
    this.step(CARD_MS, () -> this.ownName(this.ownNameBefore), "card_own_name_back");

    this.step(LAST_WRITES_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, USUAL_CAMERA), null);
  }

  /**
   * A made-up player with a real author's id next to the camera, and a song playing all along, which must stay off that
   * player's card. Nothing here can have Mojang's signature, so the session for the card goes straight into
   * the game's table: this tests what the card does with a session, while a real signed one stays untested.
   */
  private void buildProofSteps() {
    this.buildStage();
    this.step(1_000L, () -> {
      this.prepareCardTest();
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      this.client.options.setCameraType(CameraType.FIRST_PERSON);
      this.goTo(PROOF_SPOT);
      Env.LOG.info(TAG + "can validate profile keys " + this.client.services().canValidateProfileKeys());
    }, null);

    this.proofStep("a_no_playerinfo", () -> this.addFakePlayer(PROOF_NEAR_Z));
    this.proofStep("b_no_session", this::feedAddPlayer);
    // Through the game's own handling: what a server gets for sending a session Mojang did not sign
    this.proofStep("b_unsigned_session_sent", this::feedUnsignedSession);
    this.proofStep("c_session", () -> this.setSession(AN_AUTHOR, LIVES_FOR_MS));
    this.step(50L, this::measureCost, null);
    this.proofStep("c_session_far", () -> this.placeFakePlayer(2000.5, PROOF_FAR_Z));
    this.proofStep("d_expired", () -> {
      this.placeFakePlayer(2000.5, PROOF_NEAR_Z);
      this.setSession(AN_AUTHOR, LAPSES_IN_MS);
      this.report("d_about_to_expire");
    });
    this.proofStep("d_session_again", () -> this.setSession(AN_AUTHOR, LIVES_FOR_MS));
    // A card that loses its proof folds away like any card, and is forgotten, so that it comes back with a fade
    this.burst("proof_fold", () -> this.clearSession(AN_AUTHOR), FOLD_SHOTS);
    this.proofStep("d_cleared", () -> this.report("d_after_fold"));
    this.burst("proof_back", () -> this.setSession(AN_AUTHOR, LIVES_FOR_MS), FADE_IN_SHOTS);
    this.proofStep("d_back", () -> this.report("d_after_fade_in"));
    // Out of sight past the forgetting, with the proof there all along: it also starts anew
    this.step(UNSEEN_MS, () -> this.placeFakePlayer(2000.5, PROOF_BEHIND_Z), null);
    this.burst("proof_unseen", () -> this.placeFakePlayer(2000.5, PROOF_NEAR_Z), FADE_IN_SHOTS);
    this.proofStep("d_unseen_settled", () -> this.report("d_after_unseen"));
    this.step(CARD_MS, () -> this.clearSession(AN_AUTHOR), null);
    this.proofStep("a_again_removed", this::removePlayerInfo);

    // The local account goes by the account service, whatever its chat session says
    this.proofStep("local_no_card", () -> {
      this.camera(CameraType.THIRD_PERSON_BACK, CLOSE_CAMERA);
      this.reportLocal();
      this.reportOwnPlayer();
    });
    this.proofStep("local_session_does_not_help", () -> {
      this.setSession(this.client.getUser().getProfileId(), LIVES_FOR_MS);
      this.reportLocal();
    });
    this.proofStep("local_test_role", () -> {
      this.clearSession(this.client.getUser().getProfileId());
      this.testRoleOn = true;
    });

    // Both kinds side by side: the local card with its song, the other without
    this.proofStep("both_cards", () -> {
      this.placeFakePlayer(1998.5, 2002.0);
      this.feedAddPlayer();
      this.setSession(AN_AUTHOR, LIVES_FOR_MS);
    });

    this.step(LAST_WRITES_MS, this::removeFakePlayer, null);
  }

  /** Shot after shot on following frames, to see what lasts only a quarter of a second. */
  private void burst(String name, Runnable first, int shots) {
    this.step(0L, () -> {
      this.burstBeganMs = System.currentTimeMillis();
      first.run();
    }, name + "_1");
    for (int i = 2; i <= shots; i++) {
      String shot = name + "_" + i;
      this.step(0L, () -> Env.LOG.info(TAG + shot + " at " + (System.currentTimeMillis() - this.burstBeganMs) + " ms"),
          shot);
    }
  }

  private void proofStep(String name, Runnable action) {
    this.step(CARD_MS, action, null);
    this.step(0L, () -> this.report(name), "proof_" + name);
  }

  private void report(String state) {
    ClientPacketListener connection = this.client.getConnection();
    PlayerInfo info = connection == null ? null : connection.getPlayerInfo(AN_AUTHOR);
    LyricsBridge.StaffRole role = VersionedLyricsBridge.get().roleOf(AN_AUTHOR);
    Env.LOG.info(TAG + state + ": playerinfo " + (info != null)
        + ", profile id matches " + (info != null && AN_AUTHOR.equals(info.getProfile().id()))
        + ", session " + (info != null && info.getChatSession() != null)
        + ", proved " + AccountProof.proves(AN_AUTHOR)
        + ", role " + (role == null ? null : LyricsBridge.StaffRole.shown(role.label()) + " / "
        + LyricsBridge.StaffRole.shown(role.title()))
        + ", on the list " + (StaffRoster.roleOf(AN_AUTHOR) != null));
  }

  private void reportLocal() {
    UUID own = this.client.getUser().getProfileId();
    Env.LOG.info(TAG + "local account " + own + ": on the list " + (StaffRoster.roleOf(own) != null)
        + ", verified " + StaffRoster.verifiedTeamMember()
        + ", live chat session " + AccountProof.hasLiveSession(this.client.getConnection(), own, System.currentTimeMillis())
        + ", proved " + AccountProof.proves(own));
  }

  /** The own player going by another id, as on an offline-mode server: the account's id is then nobody here. */
  private void reportOwnPlayer() {
    UUID own = this.client.getUser().getProfileId();
    LocalPlayer player = this.client.player;
    boolean same = AccountProof.isOwnPlayer(own, player);
    UUID usual = player.getUUID();
    player.setUUID(UUID.randomUUID());
    boolean other = AccountProof.isOwnPlayer(own, player);
    player.setUUID(usual);
    Env.LOG.info(TAG + "own player: with its id " + same + ", going by another id " + other + ", no player "
        + AccountProof.isOwnPlayer(own, null));
  }

  private void measureCost() {
    ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long thread = Thread.currentThread().threadId();
    UUID own = this.client.getUser().getProfileId();
    int proved = 0;
    for (int i = 0; i < COST_WARM_UP; i++) {
      proved += AccountProof.proves(AN_AUTHOR) ? 1 : 0;
      proved += AccountProof.proves(own) ? 1 : 0;
    }

    long startBytes = threads.getThreadAllocatedBytes(thread);
    long startNanos = System.nanoTime();
    for (int i = 0; i < COST_CHECKS; i++) {
      proved += AccountProof.proves(AN_AUTHOR) ? 1 : 0;
    }

    long midBytes = threads.getThreadAllocatedBytes(thread);
    long midNanos = System.nanoTime();
    for (int i = 0; i < COST_CHECKS; i++) {
      proved += AccountProof.proves(own) ? 1 : 0;
    }

    long endBytes = threads.getThreadAllocatedBytes(thread);
    long endNanos = System.nanoTime();
    Env.LOG.info(TAG + "cost of " + COST_CHECKS + " checks: other player " + (midBytes - startBytes) + " bytes, "
        + (midNanos - startNanos) + " ns; local account " + (endBytes - midBytes) + " bytes, " + (endNanos - midNanos)
        + " ns (" + proved + " proved)");
  }

  private void addFakePlayer(double z) {
    this.fakePlayer = new RemotePlayer(this.client.level, new GameProfile(AN_AUTHOR, AN_AUTHORS_NAME));
    this.fakePlayer.setId(FAKE_PLAYER_ENTITY);
    this.placeFakePlayer(2000.5, z);
    this.client.level.addEntity(this.fakePlayer);
  }

  private void placeFakePlayer(double x, double z) {
    float towardCamera = 180.0F;
    this.fakePlayer.snapTo(x, FLOOR + 1, z, towardCamera, 0.0F);
    this.fakePlayer.setYHeadRot(towardCamera);
    this.fakePlayer.setYBodyRot(towardCamera);
  }

  private void removeFakePlayer() {
    this.removePlayerInfo();
    if (this.fakePlayer != null) {
      this.client.level.removeEntity(FAKE_PLAYER_ENTITY, Entity.RemovalReason.DISCARDED);
      this.fakePlayer = null;
    }
  }

  private void feedAddPlayer() {
    ClientPacketListener connection = this.client.getConnection();
    RegistryFriendlyByteBuf bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), connection.registryAccess());
    bytes.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
        ClientboundPlayerInfoUpdatePacket.Action.class);
    bytes.writeVarInt(1);
    bytes.writeUUID(AN_AUTHOR);
    ByteBufCodecs.PLAYER_NAME.encode(bytes, AN_AUTHORS_NAME);
    ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(bytes, new GameProfile(AN_AUTHOR, AN_AUTHORS_NAME).properties());
    connection.handlePlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(bytes));
  }

  private void feedUnsignedSession() {
    ClientPacketListener connection = this.client.getConnection();
    RegistryFriendlyByteBuf bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), connection.registryAccess());
    bytes.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT),
        ClientboundPlayerInfoUpdatePacket.Action.class);
    bytes.writeVarInt(1);
    bytes.writeUUID(AN_AUTHOR);
    bytes.writeBoolean(true);
    RemoteChatSession.Data.write(bytes, this.testSession(LIVES_FOR_MS).asData());
    connection.handlePlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(bytes));
  }

  private void removePlayerInfo() {
    this.client.getConnection().handlePlayerInfoRemove(new ClientboundPlayerInfoRemovePacket(List.of(AN_AUTHOR)));
  }

  private void setSession(UUID account, long livesForMs) {
    PlayerInfo info = this.client.getConnection().getPlayerInfo(account);
    ((PlayerInfoTestAccess) info).splyrics$setChatSession(this.testSession(livesForMs));
  }

  private void clearSession(UUID account) {
    PlayerInfo info = this.client.getConnection().getPlayerInfo(account);
    ((PlayerInfoTestAccess) info).splyrics$clearChatSession(false);
  }

  /** The right shape and no real signature. */
  private RemoteChatSession testSession(long livesForMs) {
    if (this.testKey == null) {
      try {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        this.testKey = generator.generateKeyPair().getPublic();
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }

    Instant expires = Instant.now().plusMillis(livesForMs);
    return new RemoteChatSession(UUID.randomUUID(),
        new ProfilePublicKey(new ProfilePublicKey.Data(expires, this.testKey, new byte[16])));
  }

  // The card's styles and the Staff page

  private static final Spot AGAINST_SKY = FRONT;
  private static final Spot AGAINST_GRASS = new Spot(2000.5, 2000.5, 0.0F, -35.0F);
  private static final String LONG_LABEL =
      "An Unreasonably Long Custom Label That Nobody Should Ever Type Here On A Card";
  private static final UUID ANOTHER_AUTHOR = UUID.fromString("8d09332f-67b4-4994-b459-7e150e8f40c7");
  private static final UUID BAD_ONE = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID BAD_TWO = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final String BAD_LIST = """
      {"roles":[5,null,"x",[],{"id":["x"]},{"label":"no id"},{"id":"   ","label":"blank"},
       {"id":"bad","label":123,"color":"#GGGGGG","style":{"opacity":1e999,"panelColor":"red","rim":"yes","corners":-5,
         "size":99999,"cover":3,"waveform":null,"time":[1],"nobodyKnowsThis":{"a":1}}},
       {"id":"odd","label":"Odd","color":"-12345","style":{"opacity":NaN,"corners":NaN,"size":Infinity,"panelColor":-1}},
       {"id":"huge","label":"%s","color":-1,"style":[]}],
       "players":{"not-a-uuid":{"role":"bad"},
        "00000000-0000-4000-8000-000000000001":{"name":7,"role":"bad","title":{"a":1},"label":[],"showTitle":"no","style":7},
        "00000000-0000-4000-8000-000000000002":{"name":"%s","role":"huge","title":"%s","label":"\u00a7cRed\n\tLabel",
          "showTitle":false,"style":{"opacity":-3,"size":0,"corners":1e30,"panelColor":"#FFF"}},
        "00000000-0000-4000-8000-000000000003":{"role":"no such role"},
        "00000000-0000-4000-8000-000000000004":null},
       "removedRoles":[1,{},"x"],"removedPlayers":[null,"zz",7],"nobodyKnowsThisEither":true}""";
  private static final String OLD_LIST = """
      {"roles":[{"id":"old","label":"From An Old File","color":"#4FC3F7"}],
       "players":{"00000000-0000-4000-8000-000000000005":{"name":"Oldie","role":"old","title":"Old Title"}}}""";

  private static CardLook opacity(float value) {
    return new CardLook(value, CardLook.PLAIN.panelRgb(), true, CardLook.PLAIN.corners(), 1.0F, true, true, true);
  }

  private static CardLook look(float value, int rgb, boolean rim, float corners, float size, boolean cover,
      boolean wave, boolean time) {
    return new CardLook(value, rgb, rim, corners, size, cover, wave, time);
  }

  /** One look on the test account's card, with or without a song, in front of the sky or the grass. */
  private void styled(String shot, CardLook look, boolean song, Spot spot) {
    this.step(CARD_MS, () -> {
      this.testLook = look;
      this.fakeSong = song ? this.styleSong : null;
      this.goTo(spot);
    }, shot);
  }

  private void buildStyleSteps() {
    this.buildStage();
    this.step(1_000L, () -> {
      this.prepareCardTest();
      this.styleSong = song(TEST_TITLE, TEST_ARTIST, true, 200_000L, this.coverArt);
      this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA);
    }, null);

    // Nobody has styled anything: this has to be the card as it was
    this.step(2_000L, () -> this.goTo(AGAINST_SKY), "cs_default_nosong_sky");
    this.styled("cs_default_nosong_grass", CardLook.PLAIN, false, AGAINST_GRASS);
    this.styled("cs_default_song_sky", CardLook.PLAIN, true, AGAINST_SKY);
    this.styled("cs_default_song_grass", CardLook.PLAIN, true, AGAINST_GRASS);

    this.opacityShots(true);
    this.opacityShots(false);
    this.nightShots();
    this.shapeShots();
    this.wordsShots();
    this.farShots();
    this.behindShots();
    this.pageShots();
    this.badDataShots();
    this.step(LAST_WRITES_MS, () -> this.camera(CameraType.THIRD_PERSON_BACK, USUAL_CAMERA), null);
  }

  private void opacityShots(boolean song) {
    String kind = song ? "song" : "nosong";
    float[] values = {1.0F, 0.5F, 0.2F, 0.0F};
    for (float value : values) {
      int percent = Math.round(value * 100.0F);
      this.styled("cs_" + kind + "_" + percent + "_sky", opacity(value), song, AGAINST_SKY);
      this.styled("cs_" + kind + "_" + percent + "_grass", opacity(value), song, AGAINST_GRASS);
    }
  }

  private void nightShots() {
    this.step(CARD_MS, () -> this.server("time set midnight"), null);
    this.styled("cs_night_song_50", opacity(0.5F), true, AGAINST_SKY);
    this.styled("cs_night_song_0", opacity(0.0F), true, AGAINST_SKY);
    this.styled("cs_night_nosong_50", opacity(0.5F), false, AGAINST_SKY);
    this.styled("cs_night_nosong_0", opacity(0.0F), false, AGAINST_SKY);
    this.styled("cs_night_song_0_grass", opacity(0.0F), true, AGAINST_GRASS);
    this.step(CARD_MS, () -> this.server("time set noon"), null);
  }

  private void shapeShots() {
    int plain = CardLook.PLAIN.panelRgb();
    float solid = CardLook.PLAIN.panelOpacity();
    boolean[] songs = {true, false};
    for (boolean song : songs) {
      String kind = song ? "song" : "nosong";
      this.styled("cs_" + kind + "_rim_off", look(solid, plain, false, 5.0F, 1.0F, true, true, true), song, AGAINST_SKY);
      this.styled("cs_" + kind + "_square", look(solid, plain, true, 0.0F, 1.0F, true, true, true), song, AGAINST_SKY);
      this.styled("cs_" + kind + "_round", look(solid, plain, true, 20.0F, 1.0F, true, true, true), song, AGAINST_SKY);
      this.styled("cs_" + kind + "_largest", look(solid, plain, true, 5.0F, 9.0F, true, true, true), song, AGAINST_SKY);
      this.styled("cs_" + kind + "_smallest", look(solid, plain, true, 5.0F, 0.0F, true, true, true), song, AGAINST_SKY);
    }

    this.styled("cs_song_no_cover", look(solid, plain, true, 5.0F, 1.0F, false, true, true), true, AGAINST_SKY);
    this.styled("cs_song_no_wave", look(solid, plain, true, 5.0F, 1.0F, true, false, true), true, AGAINST_SKY);
    this.styled("cs_song_no_time", look(solid, plain, true, 5.0F, 1.0F, true, true, false), true, AGAINST_SKY);
    this.styled("cs_song_bare", look(solid, plain, true, 5.0F, 1.0F, false, false, false), true, AGAINST_SKY);
    this.styled("cs_song_round_0", look(0.0F, plain, true, 20.0F, 1.0F, true, true, true), true, AGAINST_SKY);
    this.styled("cs_song_square_0", look(0.0F, plain, true, 0.0F, 1.0F, true, true, true), true, AGAINST_SKY);
    this.styled("cs_song_white", look(1.0F, 0xFFFFFF, true, 5.0F, 1.0F, true, true, true), true, AGAINST_SKY);
    this.styled("cs_nosong_white", look(1.0F, 0xFFFFFF, true, 5.0F, 1.0F, true, true, true), false, AGAINST_SKY);
    this.styled("cs_song_teal_70", look(0.7F, 0x1F7A7A, true, 5.0F, 1.0F, true, true, true), true, AGAINST_GRASS);
    this.step(CARD_MS, () -> {
      this.testLook = look(0.0F, plain, true, 5.0F, 1.0F, true, true, true);
      this.fakeSong = song(TEST_TITLE, TEST_ARTIST, false, 200_000L, this.coverArt);
    }, "cs_paused_0");
    this.step(CARD_MS, () -> this.testLook = look(solid, plain, true, 5.0F, 1.0F, true, true, false),
        "cs_paused_no_time");
    this.step(CARD_MS, () -> this.fakeSong = null, null);
  }

  private void wordsShots() {
    this.step(CARD_MS, () -> {
      this.testLook = CardLook.PLAIN;
      this.testTitle = null;
      this.testLabel = "SpLyrics Dev";
      this.fakeSong = null;
    }, "cs_title_off_dev");
    this.step(CARD_MS, () -> this.testLook = opacity(0.0F), "cs_title_off_dev_0");
    this.step(CARD_MS, () -> {
      this.testLook = CardLook.PLAIN;
      this.fakeSong = this.styleSong;
    }, "cs_title_off_dev_song");
    this.step(CARD_MS, () -> {
      this.testLabel = LONG_LABEL;
      this.fakeSong = null;
    }, "cs_long_label");
    this.step(CARD_MS, () -> this.fakeSong = this.styleSong, "cs_long_label_song");
    this.step(CARD_MS, () -> {
      this.testLabel = LONG_LABEL;
      this.testTitle = TEST_ROLE_TITLE;
      this.fakeSong = null;
      this.testLook = opacity(0.0F);
    }, "cs_long_label_title_0");
    this.step(CARD_MS, () -> {
      this.testLabel = TEST_ROLE_LABEL;
      this.testLook = CardLook.PLAIN;
    }, null);
  }

  private void farShots() {
    this.step(CARD_MS, () -> {
      this.testLook = opacity(0.0F);
      this.fakeSong = this.styleSong;
      this.goTo(AGAINST_SKY);
      this.camera(CameraType.THIRD_PERSON_FRONT, USUAL_CAMERA);
    }, "cs_far_0_4");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_FRONT, 8.0), "cs_far_0_8");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_FRONT, 14.0), "cs_far_0_14");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_FRONT, 22.0), "cs_far_0_22");
    this.step(CARD_MS, () -> this.camera(CameraType.THIRD_PERSON_FRONT, 30.0), "cs_far_0_30");
    this.step(CARD_MS, () -> this.goTo(AGAINST_GRASS), "cs_far_0_30_grass");
    this.step(CARD_MS, () -> {
      this.fakeSong = null;
      this.goTo(AGAINST_SKY);
      this.camera(CameraType.THIRD_PERSON_FRONT, 14.0);
    }, "cs_far_0_14_nosong");
    this.step(CARD_MS, () -> {
      this.testLook = CardLook.PLAIN;
      this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA);
    }, null);
  }

  /** What is drawn after the card, behind it: it must show through a faint panel, with no hole in the panel's shape. */
  private void behindShots() {
    // Under the clouds, looking up past the player, all the way round so that some cloud comes behind the card
    this.step(2_500L, () -> {
      this.fakeSong = null;
      this.server("fill 1990 119 1990 2010 119 2010 grass_block");
    }, null);
    for (int yaw = 0; yaw < 360; yaw += 30) {
      int facing = yaw;
      this.step(700L, () -> {
        this.testLook = opacity(0.2F);
        this.server("tp @a 2000.5 120 2000.5 " + facing + " 30");
      }, "cs_clouds_20_" + facing);
      this.step(300L, () -> this.testLook = opacity(0.5F), "cs_clouds_50_" + facing);
    }

    this.step(CARD_MS, () -> {
      this.server("fill 1990 200 1992 2010 208 1992 red_stained_glass");
      this.testLook = opacity(0.2F);
      this.goTo(AGAINST_SKY);
    }, "cs_glass_20");
    this.step(CARD_MS, () -> this.testLook = opacity(0.5F), "cs_glass_50");
    this.step(CARD_MS, () -> {
      this.server("fill 1990 200 1992 2010 208 1992 air");
      this.testLook = CardLook.PLAIN;
    }, null);
  }

  /**
   * The page itself, used through the same mouse and keyboard calls as a real hand, at the GUI scale where everything is
   * in view. Coordinates are in that scale's units and follow from the page's layout on a 1920 by 1080 window.
   */
  private void pageShots() {
    this.step(1_500L, () -> {
      this.fakeSong = this.styleSong;
      this.camera(CameraType.THIRD_PERSON_BACK, CLOSE_CAMERA);
      StudioScreen.openOn("staff");
    }, "cs_page_scale_auto");
    this.step(1_000L, () -> {
      this.client.options.guiScale().set(2);
      this.client.resizeDisplay();
    }, "cs_page_player");
    this.step(800L, () -> this.click(125, 417), "cs_page_philips");
    this.step(CARD_MS, () -> this.click(290, 389), "cs_page_preview");
    this.step(CARD_MS, () -> this.click(290, 403), "cs_page_title_off");
    this.step(CARD_MS, () -> {
      this.click(320, 431);
      this.type("SpLyrics Dev");
    }, "cs_page_own_label");
    this.step(CARD_MS, () -> this.click(500, 389), "cs_page_panel_half");
    this.step(CARD_MS, () -> this.click(467, 389), "cs_page_panel_none");
    this.step(CARD_MS, () -> this.fakeSong = null, "cs_page_panel_none_nosong");
    this.step(CARD_MS, () -> this.click(125, 389), "cs_page_role");
    this.step(CARD_MS, () -> {
      this.click(320, 403);
      this.selectAll();
      this.type("SpLyrics Team");
    }, "cs_page_role_label");
    this.step(CARD_MS, () -> this.click(125, 417), "cs_page_philips_again");
    this.step(CARD_MS, this::logExport, null);
    this.step(CARD_MS, this::tryLocked, null);
    // The Team row and the heading of the strip beside it
    this.step(CARD_MS, () -> this.click(125, 389), null);
    this.step(CARD_MS, () -> {
      this.click(320, 403);
      this.selectAll();
      this.type(LONG_LABEL);
    }, "cs_page_long_label");
    this.step(CARD_MS, () -> {
      this.click(320, 403);
      this.selectAll();
      this.type("SpLyrics Dev");
    }, null);
    this.step(CARD_MS, () -> {
      this.client.setScreen(null);
      this.client.options.guiScale().set(0);
      this.client.resizeDisplay();
    }, null);
    this.step(500L, () -> {
      UUID self = this.client.getUser().getProfileId();
      Env.LOG.info(TAG + "page closed: camera " + this.client.options.getCameraType() + ", preview gone "
          + (StaffRoster.cardLookOf(self) == null));
      this.camera(CameraType.THIRD_PERSON_FRONT, CLOSE_CAMERA);
    }, null);
  }

  /** With the development environment gone nothing on the page may stick, as in a real game of somebody not verified. */
  private void tryLocked() {
    String property = System.getProperty(DEV_ENVIRONMENT);
    System.clearProperty(DEV_ENVIRONMENT);
    try {
      String before = StaffRoster.exportJson();
      StaffRoster.setOwnLabel(AN_AUTHOR, "Should Not Stick");
      StaffRoster.setTitleOn(AN_AUTHOR, false);
      StaffRoster.restyleMember(AN_AUTHOR, style -> style.withOpacity(0.0F));
      StaffRoster.setRoleLabel("developer", "Should Not Stick");
      StaffRoster.addRole("nope", "Should Not Stick", 1);
      StaffRoster.removePlayer(AN_AUTHOR);
      StaffRoster.previewMember(AN_AUTHOR);
      Env.LOG.info(TAG + "without the dev environment: page " + staffPageTest() + ", edits stuck "
          + !before.equals(StaffRoster.exportJson()));
    } finally {
      System.setProperty(DEV_ENVIRONMENT, property);
    }
  }

  private void click(double x, double y) {
    MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
    this.client.screen.mouseClicked(event, false);
    this.client.screen.mouseReleased(event);
  }

  private void type(String text) {
    for (int i = 0; i < text.length(); i++) {
      this.client.screen.charTyped(new CharacterEvent(text.charAt(i), 0));
    }
  }

  private void selectAll() {
    this.client.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL));
  }

  /** Every part of a style goes out, to the clipboard's text and to the file, and comes back from the file the same. */
  private void logExport() {
    StaffRoster.restyleMember(ANOTHER_AUTHOR, style -> style.withOpacity(0.3F).withPanel(0x112233).withRim(false)
        .withCorners(12.0F).withSize(1.25F).withCover(false).withWave(false).withTime(false));
    String exported = StaffRoster.exportJson();
    Env.LOG.info(TAG + "export now " + exported);
    AtomicFile.flush();
    Path file = Env.dir().resolve("staff.json");
    try {
      Env.LOG.info(TAG + "local file now " + Files.readString(file));
    } catch (IOException e) {
      Env.LOG.warn(TAG + "no local file: " + e);
    }

    StaffRoster.reloadLocalForTest();
    Env.LOG.info(TAG + "read back the same " + exported.equals(StaffRoster.exportJson()) + ", look "
        + StaffRoster.lookOfMember(ANOTHER_AUTHOR));
  }

  /** A local file as bad as can be, read the way a member's game reads it, and then the cards made from it. */
  private void badDataShots() {
    String longText = "L".repeat(5_000);
    String badList = String.format(Locale.ROOT, BAD_LIST, longText, longText, longText);
    this.step(CARD_MS, () -> {
      this.loadLocal(badList);
      this.fakeSong = this.styleSong;
      StaffRoster.previewMember(BAD_ONE);
    }, "cs_bad_one");
    this.step(CARD_MS, () -> {
      this.fakeSong = null;
      StaffRoster.previewMember(BAD_TWO);
    }, "cs_bad_two");
    this.step(CARD_MS, () -> {
      this.loadLocal("not json at all {{{");
      this.loadLocal("[1, 2, 3]");
      this.loadLocal("{\"roles\": 7, \"players\": []}");
      this.loadLocal(OLD_LIST);
      StaffRoster.previewMember(UUID.fromString("00000000-0000-4000-8000-000000000005"));
    }, "cs_old_file");
    this.step(500L, StaffRoster::stopPreview, null);
  }

  private void loadLocal(String text) {
    try {
      AtomicFile.flush();
      AtomicFile.write(Env.dir().resolve("staff.json"), text);
    } catch (IOException e) {
      Env.LOG.warn(TAG + "could not write the local file: " + e);
    }

    StaffRoster.reloadLocalForTest();
    for (StaffRoster.Role role : StaffRoster.roles()) {
      Env.LOG.info(TAG + "read role " + role.id() + " label length " + role.label().length() + " colour "
          + Integer.toHexString(role.color()) + " look " + StaffRoster.lookOfRole(role.id()));
    }

    for (StaffRoster.Member member : StaffRoster.members()) {
      Env.LOG.info(TAG + "read player " + member.id() + " name length " + member.name().length() + " role "
          + member.roleId() + " title " + (member.title() == null ? null : member.title().length()) + " shows "
          + member.titleOn() + " label " + member.label() + " look " + StaffRoster.lookOfMember(member.id())
          + " has card " + (StaffRoster.roleOf(member.id()) != null));
    }
  }

  private void prepareCardTest() {
    Look look = this.plain();
    look.showLyrics = false;
    look.cardOn = false;
    look.staffBadges = true;
    this.client.gui.getChat().clearMessages(false);
    this.ownNameBefore = Laby.labyAPI().config().ingame().showMyName().get();
    Env.LOG.info(TAG + "dev environment " + Boolean.getBoolean(DEV_ENVIRONMENT)
        + ", enabled " + Laby.labyAPI().addonService().isEnabled(ADDON_ID)
        + ", show own name " + this.ownNameBefore
        + ", session " + this.client.getUser().getProfileId()
        + ", laby id " + Laby.labyAPI().getUniqueId()
        + ", entity " + (this.client.player == null ? null : this.client.player.getUUID())
        + ", window " + this.client.getWindow().getWidth() + "x" + this.client.getWindow().getHeight());
    // What the bundled list really says, through the same words as the card
    StaffRoster.Role bundled = StaffRoster.roleOf(AN_AUTHOR);
    for (StaffRoster.Role role : StaffRoster.roles()) {
      Env.LOG.info(TAG + "bundled role " + role.id() + " reads " + LyricsBridge.StaffRole.shown(role.label()));
    }

    Env.LOG.info(TAG + "an author is " + (bundled == null ? null : LyricsBridge.StaffRole.shown(bundled.label()))
        + " titled " + LyricsBridge.StaffRole.shown(StaffRoster.titleOf(AN_AUTHOR)));
  }

  /** At the smallest GUI scale, because at the usual one the menu's panel covers the middle of the screen and the card. */
  private void openInventory() {
    this.guiScaleBefore = this.client.options.guiScale().get();
    this.client.options.guiScale().set(1);
    this.client.resizeDisplay();
    this.client.setScreen(new InventoryScreen(this.client.player));
  }

  private void closeInventory() {
    this.client.setScreen(null);
    this.client.options.guiScale().set(this.guiScaleBefore);
    this.client.resizeDisplay();
  }

  private void camera(CameraType type, double distance) {
    this.client.options.setCameraType(type);
    this.server(String.format(Locale.ROOT, "attribute @a[limit=1] minecraft:camera_distance base set %.1f", distance));
  }

  private void addonEnabled(boolean on) {
    // The same value the addon list's Disable changes
    LoadedAddon addon = Laby.labyAPI().addonService().getAddon(ADDON_ID).orElse(null);
    if (addon != null && addon.getInstance() instanceof LabyAddon<?> instance) {
      instance.configuration().enabled().set(on);
    }

    Env.LOG.info(TAG + "addon enabled now " + Laby.labyAPI().addonService().isEnabled(ADDON_ID));
  }

  private void ownName(OwnNameTagMode mode) {
    Laby.labyAPI().config().ingame().showMyName().set(mode);
    Env.LOG.info(TAG + "show own name now " + mode);
  }

  private static NowPlaying song(String title, String artist, boolean playing, long durationMs, byte[] art) {
    return new NowPlaying(title, artist, "", durationMs, durationMs * 2L / 5L, System.currentTimeMillis(), playing,
        "SpLyrics test", art);
  }

  /**
   * A made-up cover with the qualities of real album art, so the slot is judged on something like it: fine detail and
   * grain all over. One is dark like a night photograph, which tests its edge on the dark card, one a pale print.
   */
  private static byte[] makeCover(boolean other) {
    int size = AlbumTexture.SIZE * 4;
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    Random grain = new Random(other ? 7L : 3L);
    for (int y = 0; y < size; y++) {
      double down = y / (double) size;
      for (int x = 0; x < size; x++) {
        double along = x / (double) size;
        double red;
        double green;
        double blue;
        if (other) {
          // Paper, and rings of ink
          double dx = along - 0.62;
          double dy = down - 0.4;
          double ring = Math.sin(Math.sqrt(dx * dx + dy * dy) * 70.0);
          double ink = ring > 0.3 && down > 0.12 && down < 0.88 && along > 0.12 && along < 0.88 ? 1.0 : 0.0;
          red = 232.0 - ink * 190.0;
          green = 226.0 - ink * 170.0;
          blue = 210.0 - ink * 120.0;
        } else {
          // Sky fading into two ridges, with a low moon
          red = 18.0 + 70.0 * down * down;
          green = 22.0 + 34.0 * down * down;
          blue = 44.0 + 40.0 * (1.0 - down);
          double moonX = along - 0.68;
          double moonY = down - 0.3;
          double moon = Math.sqrt(moonX * moonX + moonY * moonY);
          if (moon < 0.075) {
            red = 228.0;
            green = 218.0;
            blue = 190.0;
          } else if (moon < 0.3) {
            double halo = (0.3 - moon) / 0.3;
            red += 46.0 * halo * halo;
            green += 40.0 * halo * halo;
            blue += 44.0 * halo * halo;
          }

          double farRidge = 0.62 + 0.05 * Math.sin(along * 9.0) + 0.018 * Math.sin(along * 41.0)
              + 0.008 * Math.sin(along * 113.0);
          double nearRidge = 0.78 + 0.04 * Math.sin(along * 5.0 + 2.0) + 0.012 * Math.sin(along * 67.0);
          if (down > nearRidge) {
            red = 8.0;
            green = 9.0;
            blue = 13.0;
          } else if (down > farRidge) {
            red = 30.0;
            green = 28.0;
            blue = 42.0;
          }
        }

        double noise = (grain.nextDouble() - 0.5) * 16.0;
        image.setRGB(x, y, channel(red + noise) << 16 | channel(green + noise) << 8 | channel(blue + noise));
      }
    }

    ByteArrayOutputStream png = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", png);
    } catch (IOException e) {
      return null;
    }

    return png.toByteArray();
  }

  private static int channel(double value) {
    return (int) Math.max(0.0, Math.min(255.0, value));
  }

  // What the steps do

  /** Back to the default look and a line without wander, so that each shot shows only what it names. */
  private Look plain() {
    Look look = LookStore.get();
    Presets.byId(Presets.DEFAULT_ID).apply(look);
    look.showLyrics = true;
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
    look.cardOn = true;
    look.cardStyle = "STRIP";
    look.cardCorner = "TOP_LEFT";
    look.cardX = -1.0F;
    look.cardY = -1.0F;
    return look;
  }

  /** Forcing the same words twice would change nothing, and each shot wants a line that newly finds its spot. */
  private static void line(String text) {
    DemoLines.force(null);
    DemoLines.force(text);
  }

  private void goTo(Spot spot) {
    this.server(String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.1f %.1f", spot.x, FLOOR + 1, spot.z, spot.yaw, spot.pitch));
  }

  private void server(String... commands) {
    IntegratedServer server = this.client.getSingleplayerServer();
    if (server == null) {
      return;
    }

    server.execute(() -> {
      CommandSourceStack source = server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER)
          .withSuppressedOutput();
      for (String command : commands) {
        server.getCommands().performPrefixedCommand(source, command);
      }
    });
  }
}
