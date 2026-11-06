package net.lyrica.api;

import java.util.UUID;
import net.labymod.api.client.resources.ResourceLocation;
import net.labymod.api.reference.annotation.Referenceable;
import net.labymod.api.util.I18n;

/**
 * What the version-specific side of Lyrica does for the addon core: it owns the media reader, the lyrics, the
 * renderers and the menu, all written against Minecraft itself; the core only tells it when to run and what the
 * addon's settings say.
 */
@Referenceable
public interface LyricsBridge {

  /** Starts reading the media session and loading lyrics (the addon was enabled). */
  void start();

  /** Stops everything and saves the settings. */
  void stop();

  /** Once a game tick, from the core. */
  void tick();

  /** Once at the end of every frame, in and out of worlds. */
  void frame();

  /** True while a key press should reach the addon: in a world, with no screen open. */
  boolean canHandleKeys();

  /** Opens the lyrics menu. */
  void openMenu();

  /** Moves this song's sync by the given milliseconds (kept for the song) and says so on the action bar. */
  void nudgeSync(int byMs);

  /** Hides or shows the lyrics, leaving the now-playing card. */
  void toggleLyrics();

  /** Tells the version side which key opens the menu, so its own key row shows and binds it. */
  void menuKey(MenuKeyAccess access);

  /** What the look says about a switch that also stands on LabyMod's settings screen. */
  boolean isOn(LookSwitch which);

  /** Sets such a switch in the look and saves it. */
  void turn(LookSwitch which, boolean on);

  /** How many lines already sung stay above the current one. */
  int earlierLines();

  void earlierLines(int lines);

  /** The sync of every song, in milliseconds. */
  int syncAllMs();

  void syncAllMs(int ms);

  /**
   * What the card over this account's name says: the role's label, its colour, and the player's optional title. Null for
   * an account not on the list and also for one on it that this game has no proof of right now, so the answer can change
   * from one frame to the next. It is no way to read the list.
   */
  StaffRole roleOf(UUID playerId);

  /** How the title of an account looks under its name. Never null. */
  CardLook cardLookOf(UUID playerId);

  /** Whether the team's titles are switched on in the look. */
  boolean staffBadges();

  /**
   * What this PC plays right now, or null. The same object comes back until the song or its reading changes, so asking
   * each frame costs nothing.
   */
  LocalSong localSong();

  /** The song for the card over one's own head: null when that card is off, or hidden during a pause. */
  LocalSong ownCardSong();

  /** The song to tell other players about: null unless the player chose to share, and also when it is hidden. */
  LocalSong songToShare();

  /** The current cover in {@link #COVER_THUMB_BYTES} bytes, the same array until it changes; null to send none. */
  byte[] coverToShare();

  /** Whether songs other players share show over their heads. */
  boolean showSharedSongs();

  /** Whether the covers of songs other players share show too. */
  boolean showSharedCovers();

  /** The textures for a cover another player sent. On the render thread. */
  LocalSong.Cover sharedCover(UUID playerId, byte[] thumb);

  /** How the song card looks, for every card on this game. The same object until it changes. */
  CardLook songCardLook();

  /**
   * The colour of what has played on a song card's wave and of its word Paused, as this game's player chose it: one colour
   * of their own or that song's cover. It has nothing to do with a role, whose words keep the role's colour.
   *
   * @param sharedThumb the cover another player sends with this song, or null. Goes by {@link #COVER_THUMB_BYTES}
   */
  int songCardAccent(LocalSong song, byte[] sharedThumb);

  /** Whether every song card on this game stands under the name rather than over it. */
  boolean songCardUnderName();

  /** Words as a picture, for scripts the tag font lacks; null while it is being made. On the render thread. */
  TextImage wordsImage(String text, boolean bold, float mostWidth);

  /**
   * Whether a team member's role stands on the song card rather than under the name; false without proof of that account.
   * Asked per player per frame.
   */
  boolean roleOnCard(UUID playerId);

  /**
   * @param texture white on clear, so that it can take any colour
   * @param width   in text pixels
   * @param height  in text pixels
   * @param above   how far the picture begins above the top of the capitals, in text pixels
   */
  record TextImage(ResourceLocation texture, float width, float height, float above) {

  }

  /** A cover in a message: this many pixels a side at 16 bits each, red 5, green 6, blue 5. */
  int COVER_THUMB_SIDE = 24;
  int COVER_THUMB_BYTES = COVER_THUMB_SIDE * COVER_THUMB_SIDE * 2;

  /** The switches of the look that LabyMod's settings screen shows as well as the menu. */
  enum LookSwitch {
    LYRICS, HIDE_WHEN_PAUSED, KARAOKE, NEXT_LINE, BREAK_DOTS, CARD, CARD_COVER, CARD_TIMES, CARD_LINE, OWN_CARD,
    SHARE_SONG, SHOW_SHARED, TEAM_ROLES
  }

  interface MenuKeyAccess {

    String name();

    boolean isMenuKey(int keyCode);

    void bind(int keyCode);
  }

  /**
   * @param label a translation key on the bundled list, see {@link #shown(String)}
   * @param title shown under the label when no song is, or null; a translation key on the bundled list
   */
  record StaffRole(String name, String label, int colorHex, String title) {

    /** Only texts under our own keys are looked up, so a typed label can never pull in somebody else's translation. */
    private static final String KEY_PREFIX = "lyrica.";

    /**
     * The words of a label or a title. The bundled list holds translation keys of ours; what a team member types on the
     * Staff page is shown exactly as typed.
     */
    public static String shown(String text) {
      return text != null && text.startsWith(KEY_PREFIX) && I18n.has(text) ? I18n.translate(text) : text;
    }
  }

  /**
   * How a staff card looks. Whatever goes in comes out within limits, so a bad value cannot make a huge card or stop it
   * drawing. Comparing with {@code ==} is enough to see a change: the same object comes back until the style changes.
   *
   * @param panelOpacity 0 for no panel at all, 1 for fully solid
   * @param panelRgb     the panel's colour
   * @param rim          whether the panel has its one-pixel lighter edge
   * @param corners      the radius of the panel's corners in text pixels, 0 for square
   * @param size         a share of the card's usual size
   * @param cover        whether a song's cover is shown
   * @param wave         whether a song's wave is shown
   * @param time         whether a song's time is shown
   */
  record CardLook(float panelOpacity, int panelRgb, boolean rim, float corners, float size, boolean cover, boolean wave,
      boolean time) {

    public static final float MOST_CORNERS = 20.0F;
    public static final float LEAST_SIZE = 0.6F;
    public static final float MOST_SIZE = 1.6F;
    private static final float PLAIN_OPACITY = 0.95F;
    private static final float PLAIN_CORNERS = 5.0F;
    private static final float PLAIN_SIZE = 1.0F;
    private static final int PLAIN_PANEL = 0x101216;

    /** The card as it is when nobody has styled it. */
    public static final CardLook PLAIN = new CardLook(PLAIN_OPACITY, PLAIN_PANEL, true, PLAIN_CORNERS, PLAIN_SIZE, true,
        true, true);

    /** A team member's title as it is when nobody has styled it: bare words under the name, with no panel. */
    public static final CardLook BADGE = new CardLook(0.0F, PLAIN_PANEL, true, PLAIN_CORNERS, PLAIN_SIZE, false, false,
        false);

    public CardLook {
      panelOpacity = within(panelOpacity, 0.0F, 1.0F, PLAIN_OPACITY);
      panelRgb &= 0xFFFFFF;
      corners = within(corners, 0.0F, MOST_CORNERS, PLAIN_CORNERS);
      size = within(size, LEAST_SIZE, MOST_SIZE, PLAIN_SIZE);
    }

    private static float within(float value, float least, float most, float otherwise) {
      if (Float.isNaN(value)) {
        return otherwise;
      }

      return value < least ? least : Math.min(value, most);
    }
  }

  /**
   * @param title      may be empty, never null
   * @param artist     may be empty, never null
   * @param positionMs how far into the song the reading was
   * @param durationMs the song's length, or 0 when unknown
   * @param atMs       the system time of the reading
   * @param playing    false while paused
   * @param cover      the textures holding this song's cover, or null while it has none
   */
  record LocalSong(String title, String artist, long positionMs, long durationMs, long atMs, boolean playing,
      Cover cover) {

    public boolean hasProgress() {
      return this.durationMs > 0L;
    }

    /**
     * Whether the position means anything. It can before the length is known, because a player may publish a new song's
     * length long after its title; a player that publishes no timeline at all stays at nothing.
     */
    public boolean hasTime() {
      return this.durationMs > 0L || this.positionMs > 0L;
    }

    /** Where the song is at this moment, moving on smoothly between readings. */
    public long positionAt(long nowMs) {
      long at = this.positionMs + (this.playing ? Math.max(0L, nowMs - this.atMs) : 0L);
      return this.durationMs > 0L ? Math.min(at, this.durationMs) : at;
    }

    /** The same as a share of the song, 0 to 1. */
    public float progressAt(long nowMs) {
      return this.durationMs > 0L ? this.positionAt(nowMs) / (float) this.durationMs : 0.0F;
    }

    /**
     * One cover at three sizes, because textures here cannot have mipmaps and a cover shrunk to less than half of its
     * texture turns jagged.
     *
     * @param largeFrom draw {@code large} when the cover is wider than this many screen pixels
     * @param smallTo   draw {@code small} when it is at most this wide, and {@code middle} in between
     */
    public record Cover(ResourceLocation large, ResourceLocation middle, ResourceLocation small, float largeFrom,
        float smallTo) {

      public ResourceLocation forPixels(float pixels) {
        if (pixels > this.largeFrom) {
          return this.large;
        }

        return pixels > this.smallTo ? this.middle : this.small;
      }
    }
  }
}
