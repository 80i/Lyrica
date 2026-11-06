package net.lyrica.v1_21_11;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.inject.Singleton;
import net.labymod.api.Laby;
import net.labymod.api.client.resources.ResourceLocation;
import net.labymod.api.models.Implements;
import net.labymod.api.util.I18n;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.lyrica.api.LyricsBridge;
import net.lyrica.v1_21_11.hud.AlbumTexture;
import net.lyrica.v1_21_11.hud.NowCard;
import net.lyrica.v1_21_11.hud.ScreenLyrics;
import net.lyrica.v1_21_11.hud.WordsTexture;
import net.lyrica.v1_21_11.look.AccountProof;
import net.lyrica.v1_21_11.look.Look;
import net.lyrica.v1_21_11.look.LookStore;
import net.lyrica.v1_21_11.look.Palette;
import net.lyrica.v1_21_11.look.StaffRoster;
import net.lyrica.v1_21_11.menu.StudioScreen;
import net.lyrica.v1_21_11.song.NowPlaying;
import net.lyrica.v1_21_11.song.Playback;
import net.lyrica.v1_21_11.stage.WorldStage;
import net.lyrica.v1_21_11.words.DemoLines;
import net.lyrica.v1_21_11.words.LyricsLibrary;

/** The Minecraft 1.21.11 side of Lyrica: runs the media reader and the lyrics, draws them, opens the menu. */
@Singleton
@Implements(LyricsBridge.class)
public class VersionedLyricsBridge implements LyricsBridge {

  private static final String ADDON_ID = "lyrica";
  private static final long ENABLED_POLL_MS = 250L;
  private static final int SONG_POLL_TICKS = 5;
  private static final long MOST_SONG_OFFSET_MS = 5_000L;
  private static final int MOST_THUMB_COLORS = 512;
  private static final int MOST_EARLIER_LINES = 3;
  private static final LocalSong.Cover[] CARD_COVERS = {cardCover(0), cardCover(1)};

  private static VersionedLyricsBridge instance;
  private static volatile boolean previewOwnCard;

  private boolean running;
  private int ticks;
  private long lastFrameTimeNs = -1L;
  private long nextEnabledPollMs;
  private NowPlaying localSongFrom;
  private boolean localSongHadCover;
  private LocalSong localSong;
  private CardLook songLook;
  private final Map<Identifier, LocalSong.Cover> sharedCovers = new HashMap<>();
  // By the array, which stays the same for as long as a player's cover does
  private final Map<byte[], Integer> thumbColors = new IdentityHashMap<>();

  public VersionedLyricsBridge() {
    instance = this;
  }

  /** For the mixins. */
  public static VersionedLyricsBridge get() {
    return instance;
  }

  @Override
  public void start() {
    if (this.running) {
      return;
    }

    LookStore.get();
    Playback.get().start();
    this.running = true;
    Env.LOG.info("Lyrics running");
  }

  @Override
  public void stop() {
    if (!this.running) {
      return;
    }

    this.running = false;
    Playback.get().stop();
    LyricsLibrary.get().onSong(null);
    WorldStage.reset();
    NowCard.clear();
    LookStore.flush();
  }

  @Override
  public void tick() {
    if (!this.running || ++this.ticks % SONG_POLL_TICKS != 0) {
      return;
    }

    Playback playback = Playback.get();
    playback.tick();
    // Early, so a team member's own additions show on badges before the menu was ever opened. Asks nothing of anybody
    // not on the bundled list.
    StaffRoster.verifiedTeamMember();
    StaffRoster.refreshPublished();
    LyricsLibrary.get().onSong(playback.current());
  }

  /**
   * Comes from the render mixin, whatever LabyMod thinks of the addon, and again from the core right after it. The
   * game's frame time only changes after both, so the second call of a frame is easy to tell.
   */
  @Override
  public void frame() {
    Minecraft client = Minecraft.getInstance();
    long frameTimeNs = client.getFrameTimeNs();
    if (frameTimeNs == this.lastFrameTimeNs) {
      return;
    }

    this.lastFrameTimeNs = frameTimeNs;
    this.followAddonState();
    if (client.level == null) {
      WorldStage.leftWorld();
    }

    // Here and not only with the HUD: the card over the name wants the cover with the widget off or the HUD hidden
    if (this.running) {
      AlbumTexture.update(currentSong());
    }

    DevSmoke.frame();
    LetterSmoke.frame();
  }

  /** LabyMod stops calling the addon's listeners once it is disabled, so this side has to notice by itself. */
  private void followAddonState() {
    long now = System.currentTimeMillis();
    if (now < this.nextEnabledPollMs) {
      return;
    }

    this.nextEnabledPollMs = now + ENABLED_POLL_MS;
    boolean enabled = Laby.labyAPI().addonService().isEnabled(ADDON_ID);
    if (enabled != this.running) {
      if (enabled) {
        this.start();
      } else {
        this.stop();
      }
    }
  }

  @Override
  public boolean canHandleKeys() {
    Minecraft client = Minecraft.getInstance();
    return this.running && client.player != null && client.screen == null;
  }

  @Override
  public void openMenu() {
    Minecraft client = Minecraft.getInstance();
    // From the game's task queue, once the key event that asked for it is over
    client.execute(() -> {
      if (!(client.screen instanceof StudioScreen)) {
        client.setScreen(new StudioScreen());
      }
    });
  }

  @Override
  public void nudgeSync(int byMs) {
    NowPlaying song = Playback.get().current();
    if (song == null) {
      return;
    }

    Look look = LookStore.get();
    String key = song.key();
    Long own = look.songOffsets.get(key);
    long now = Math.max(-MOST_SONG_OFFSET_MS, Math.min(MOST_SONG_OFFSET_MS, (own == null ? 0L : own) + byMs));
    if (now == 0L) {
      look.songOffsets.remove(key);
    } else {
      look.songOffsets.put(key, now);
    }

    LookStore.save();
    say(I18n.translate("lyrica.chat.nudge", (now > 0L ? "+" : "") + now));
  }

  @Override
  public void toggleLyrics() {
    Look look = LookStore.get();
    look.showLyrics = !look.showLyrics;
    LookStore.save();
    say(I18n.translate(look.showLyrics ? "lyrica.chat.shown" : "lyrica.chat.hidden"));
  }

  private static void say(String text) {
    Minecraft client = Minecraft.getInstance();
    if (client.player != null) {
      client.player.displayClientMessage(Component.literal(text), true);
    }
  }

  @Override
  public void menuKey(MenuKeyAccess access) {
    Env.menuKey(access::name, access::isMenuKey, access::bind);
  }

  @Override
  public boolean isOn(LookSwitch which) {
    Look look = LookStore.get();
    return switch (which) {
      case LYRICS -> look.showLyrics;
      case HIDE_WHEN_PAUSED -> look.hideWhenPaused;
      case KARAOKE -> look.karaoke;
      case NEXT_LINE -> look.nextLine;
      case BREAK_DOTS -> look.breakDots;
      case CARD -> look.cardOn;
      case CARD_COVER -> look.cardCover;
      case CARD_TIMES -> look.cardTimes;
      case CARD_LINE -> look.cardLine;
      case OWN_CARD -> look.ownCard;
      case SHARE_SONG -> look.shareSong;
      case SHOW_SHARED -> look.showShared;
      case TEAM_ROLES -> look.staffBadges;
    };
  }

  @Override
  public void turn(LookSwitch which, boolean on) {
    Look look = LookStore.get();
    switch (which) {
      case LYRICS -> look.showLyrics = on;
      case HIDE_WHEN_PAUSED -> look.hideWhenPaused = on;
      case KARAOKE -> look.karaoke = on;
      case NEXT_LINE -> look.nextLine = on;
      case BREAK_DOTS -> look.breakDots = on;
      case CARD -> look.cardOn = on;
      case CARD_COVER -> look.cardCover = on;
      case CARD_TIMES -> look.cardTimes = on;
      case CARD_LINE -> look.cardLine = on;
      case OWN_CARD -> look.ownCard = on;
      case SHARE_SONG -> look.shareSong = on;
      case SHOW_SHARED -> look.showShared = on;
      case TEAM_ROLES -> look.staffBadges = on;
    }

    LookStore.save();
  }

  @Override
  public int earlierLines() {
    return LookStore.get().earlierLines;
  }

  @Override
  public void earlierLines(int lines) {
    LookStore.get().earlierLines = Math.max(0, Math.min(MOST_EARLIER_LINES, lines));
    LookStore.save();
  }

  @Override
  public int syncAllMs() {
    return (int) Math.max(-MOST_SONG_OFFSET_MS, Math.min(MOST_SONG_OFFSET_MS, LookStore.get().offsetMs));
  }

  @Override
  public void syncAllMs(int ms) {
    LookStore.get().offsetMs = Math.max(-MOST_SONG_OFFSET_MS, Math.min(MOST_SONG_OFFSET_MS, ms));
    LookStore.save();
  }

  @Override
  public StaffRole roleOf(UUID playerId) {
    if (!LookStore.get().showOwnRole && !StaffRoster.previewing()
        && Objects.equals(playerId, Minecraft.getInstance().getUser().getProfileId())) {
      return null;
    }

    StaffRoster.Role role = StaffRoster.roleOf(playerId);
    if (role != null) {
      return AccountProof.proves(playerId)
          ? new StaffRole(role.id(), role.label(), role.color(), StaffRoster.titleOf(playerId))
          : null;
    }

    return DevSmoke.testRole(playerId);
  }

  @Override
  public CardLook cardLookOf(UUID playerId) {
    CardLook look = StaffRoster.cardLookOf(playerId);
    return look != null ? look : DevSmoke.testLook(playerId);
  }

  @Override
  public boolean staffBadges() {
    return LookStore.get().staffBadges;
  }

  @Override
  public TextImage wordsImage(String text, boolean bold, float mostWidth) {
    return WordsTexture.of(text, bold, mostWidth);
  }

  /** Only with the same proof as a title, which is why it asks for the role rather than the list. */
  @Override
  public boolean roleOnCard(UUID playerId) {
    return this.roleOf(playerId) != null && (DevSmoke.testRoleOnCard(playerId) || StaffRoster.onCardOf(playerId));
  }

  @Override
  public LocalSong ownCardSong() {
    Look look = LookStore.get();
    LocalSong song = look.ownCard ? this.localSong() : null;
    return song != null && !song.playing() && "HIDE".equals(look.pausedCard) ? null : song;
  }

  /** What is hidden from oneself is hidden from everybody, so a pause can take the song off other players' screens too. */
  @Override
  public LocalSong songToShare() {
    Look look = LookStore.get();
    if (!look.shareSong || !this.running) {
      return null;
    }

    LocalSong song = this.localSong();
    return song != null && !song.playing() && "HIDE".equals(look.pausedCard) ? null : song;
  }

  @Override
  public byte[] coverToShare() {
    return LookStore.get().shareCover ? AlbumTexture.thumb() : null;
  }

  @Override
  public boolean showSharedSongs() {
    return LookStore.get().showShared;
  }

  @Override
  public boolean showSharedCovers() {
    return LookStore.get().showSharedCovers;
  }

  @Override
  public LocalSong.Cover sharedCover(UUID playerId, byte[] thumb) {
    Identifier texture = AlbumTexture.sharedCover(playerId, thumb);
    LocalSong.Cover known = this.sharedCovers.get(texture);
    if (known == null) {
      ResourceLocation location = labyLocation(texture);
      known = new LocalSong.Cover(location, location, location, 0.0F, 0.0F);
      this.sharedCovers.put(texture, known);
    }

    return known;
  }

  @Override
  public CardLook songCardLook() {
    Look look = LookStore.get();
    CardLook wanted = this.songLook;
    boolean same = wanted != null && wanted.panelOpacity() == look.headOpacity && wanted.panelRgb() == look.headPanel
        && wanted.rim() == look.headRim && wanted.corners() == look.headCorners && wanted.size() == look.headSize
        && wanted.cover() == look.headCover && wanted.wave() == look.headWave && wanted.time() == look.headTime;
    if (!same) {
      wanted = new CardLook(look.headOpacity, look.headPanel, look.headRim, look.headCorners, look.headSize,
          look.headCover, look.headWave, look.headTime);
      this.songLook = wanted;
    }

    return wanted;
  }

  @Override
  public int songCardAccent(LocalSong song, byte[] sharedThumb) {
    Look look = LookStore.get();
    if (!"ALBUM".equals(look.headAccentMode)) {
      return look.headAccent;
    }

    if (song == this.localSong) {
      NowPlaying playing = currentSong();
      if (playing != null) {
        return Palette.albumColors(playing)[0];
      }
    }

    if (sharedThumb == null) {
      return Palette.nameColor(song.title(), song.artist());
    }

    Integer known = this.thumbColors.get(sharedThumb);
    if (known == null) {
      if (this.thumbColors.size() >= MOST_THUMB_COLORS) {
        this.thumbColors.clear();
      }

      known = Palette.thumbColor(sharedThumb);
      this.thumbColors.put(sharedThumb, known);
    }

    return known;
  }

  @Override
  public boolean songCardUnderName() {
    return "UNDER".equals(LookStore.get().headPlace);
  }

  @Override
  public LocalSong localSong() {
    NowPlaying song = this.running ? currentSong() : null;
    if (song == null) {
      this.localSongFrom = null;
      this.localSong = null;
      return null;
    }

    boolean cover = AlbumTexture.shows(song);
    if (song != this.localSongFrom || cover != this.localSongHadCover) {
      this.localSongFrom = song;
      this.localSongHadCover = cover;
      this.localSong = toLocalSong(song, cover);
    }

    return this.localSong;
  }

  private static LocalSong toLocalSong(NowPlaying song, boolean cover) {
    String title = song.title() == null ? "" : song.title().strip();
    String artist = song.artist() == null ? "" : song.artist().strip();
    if (title.isEmpty() && artist.isEmpty()) {
      return null;
    }

    return new LocalSong(title, artist, song.positionMs(), Math.max(0L, song.durationMs()), song.sampledAtMs(),
        song.playing(), cover ? CARD_COVERS[AlbumTexture.cardSlot()] : null);
  }

  private static LocalSong.Cover cardCover(int slot) {
    return new LocalSong.Cover(labyLocation(AlbumTexture.cardCover(slot, 0)),
        labyLocation(AlbumTexture.cardCover(slot, 1)), labyLocation(AlbumTexture.cardCover(slot, 2)),
        AlbumTexture.cardSize(1), AlbumTexture.cardSize(2));
  }

  private static ResourceLocation labyLocation(Identifier id) {
    return ResourceLocation.create(id.getNamespace(), id.getPath());
  }

  /** What plays on this PC; the development test puts its own song in here. */
  private static NowPlaying currentSong() {
    NowPlaying song = DevSmoke.overridesSong() ? DevSmoke.song() : Playback.get().current();
    return song == null && previewOwnCard ? DemoLines.song() : song;
  }

  /** With the camera turned to look at one's own card, a card that has nothing to show would show nothing. */
  public static void previewOwnCard(boolean on) {
    previewOwnCard = on;
  }

  /** From the world render mixin, where the hand is about to be drawn. */
  public void renderWorld(PoseStack pose, Camera camera) {
    Minecraft client = Minecraft.getInstance();
    if (this.running && client.player != null && client.level != null) {
      WorldStage.render(pose, camera);
    }
  }

  /** From the HUD mixin, after the game's own HUD. */
  public void renderHud(GuiGraphics graphics) {
    Minecraft client = Minecraft.getInstance();
    if (!this.running || client.player == null || client.options.hideGui) {
      return;
    }

    Look look = LookStore.get();
    NowPlaying song = currentSong();
    // Only when nothing plays: a real song without lyrics keeps its own card while the demo line shows
    if (song == null && DemoLines.active(null, null)) {
      song = DemoLines.song();
    }

    NowCard.render(graphics, song, look);
    ScreenLyrics.render(graphics, look);
  }
}
