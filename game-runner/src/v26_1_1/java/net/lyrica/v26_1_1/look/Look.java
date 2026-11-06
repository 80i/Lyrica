package net.lyrica.v26_1_1.look;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the player can set. Plain public fields, saved as they are. Enum-like values are strings so that a file
 * from another version keeps loading; readers treat an unknown value as the default. Colours are 0xRRGGBB.
 */
public final class Look {

  private static final int CURRENT_VERSION = 1;

  /** Left at 0 here so that a file written before this field existed reads as old; {@link #repair()} brings it up. */
  public int lookVersion = 0;

  // Master
  public boolean showLyrics = true;
  public boolean hideWhenPaused = true;
  public boolean staffBadges = true;

  // Text
  public String font = "BEBAS";
  public float size = 1.0F;
  public boolean bold = false;
  public boolean upper = false;
  public float letterSpacing = 0.0F;
  public float lineGap = 0.0F;
  public int wrapWidth = 150;

  // Colour: SOLID, GRADIENT, RAINBOW, ALBUM, ALBUM_GRADIENT, PER_LINE
  public String colorMode = "SOLID";
  public int color = Palette.PAPER;
  public int color2 = Palette.AMBER;
  public float flowSpeed = 1.0F;

  // Solid form
  public float depth = 1.0F;
  public float sideShade = 0.45F;
  public float bend = 0.35F;
  public boolean outline = false;
  public int outlineColor = Palette.INK;

  // Light. glow: OFF, SOFT, BRIGHT. glowColorMode: MATCH, CUSTOM
  public boolean selfLit = false;
  public String glow = "OFF";
  public String glowColorMode = "MATCH";
  public int glowColor = Palette.AMBER;
  public float glowReach = 1.0F;
  public boolean sparkles = false;
  public boolean shadow = true;
  public boolean throughWalls = false;

  // Motion. arrange: LINE, WORDS, STAIRS, ARC, STACK
  public String enter = "RISE_UP";
  public String exit = "TIP_OVER";
  public String idle = "NONE";
  public float idleSpeed = 1.0F;
  public float idleAmount = 1.0F;
  public String arrange = "LINE";

  // Karaoke: DIM, SWEEP, FILL, LIFT
  public boolean karaoke = false;
  public String karaokeStyle = "DIM";

  // Extra lines
  public int earlierLines = 0;
  public boolean nextLine = false;
  public boolean breakDots = false;

  // Place: STAND, HOVER, SKY, WALL, FLOOR, ORBIT, TRAIL, SCREEN. facing: TURN, FIXED. screenSpot: TOP, MIDDLE, BOTTOM
  public String place = "STAND";
  public float distance = 12.0F;
  public float heightAdd = 0.0F;
  public float spread = 1.0F;
  public String facing = "FIXED";
  public boolean carryWhenFast = false;
  public String screenSpot = "BOTTOM";
  public float screenSize = 1.0F;
  public int trailLength = 3;

  // Sync
  public long offsetMs = 0L;
  public Map<String, Long> songOffsets = new LinkedHashMap<>();

  // Card. cardStyle: STRIP, DISC, COVER, TICKER, NONE. cardCorner: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
  public boolean cardOn = true;
  public String cardStyle = "STRIP";
  public String cardCorner = "TOP_LEFT";
  public float cardX = -1.0F;
  public float cardY = -1.0F;
  public float cardSize = 1.0F;
  public float cardOpacity = 1.0F;
  public boolean cardCover = true;
  public boolean cardTimes = true;
  public boolean cardLine = false;
  public int cardAccent = Palette.ACCENT;

  // The song card over the head. pausedCard: SHOW, HIDE. Sharing sends something, so it stays off until it is chosen.
  public boolean ownCard = true;
  public String pausedCard = "SHOW";
  // Where every song card on this game stands: OVER or UNDER the name
  public String headPlace = "OVER";
  public boolean shareSong = false;
  public boolean shareCover = true;
  public boolean showShared = true;
  public boolean showSharedCovers = true;
  public float headOpacity = 0.95F;
  public int headPanel = 0x101216;
  public boolean headRim = true;
  public float headCorners = 5.0F;
  public float headSize = 1.0F;
  public boolean headCover = true;
  public boolean headWave = true;
  public boolean headTime = true;
  public int headAccent = Palette.ACCENT;
  // What the wave of every song card on this game takes: STATIC, headAccent; or ALBUM, that song's cover
  public String headAccentMode = "STATIC";
  // Whether one's own role shows on this game. Others go by the list
  public boolean showOwnRole = true;
  // What may be read from the PC: ALL, any app that plays; or SPOTIFY, nothing but the Spotify app
  public String mediaSources = "ALL";

  public List<SavedLook> saved = new ArrayList<>();

  /** The sync of a song: the general offset plus the song's own. */
  public long offsetFor(String key) {
    Long own = key == null ? null : this.songOffsets.get(key);
    return own == null ? this.offsetMs : this.offsetMs + own;
  }

  public Look copy() {
    Look copy = new Look();
    copy.lookVersion = this.lookVersion;
    copy.copyLookFrom(this);
    copy.showLyrics = this.showLyrics;
    copy.hideWhenPaused = this.hideWhenPaused;
    copy.staffBadges = this.staffBadges;
    copy.earlierLines = this.earlierLines;
    copy.nextLine = this.nextLine;
    copy.breakDots = this.breakDots;
    copy.place = this.place;
    copy.distance = this.distance;
    copy.heightAdd = this.heightAdd;
    copy.spread = this.spread;
    copy.facing = this.facing;
    copy.carryWhenFast = this.carryWhenFast;
    copy.screenSpot = this.screenSpot;
    copy.screenSize = this.screenSize;
    copy.trailLength = this.trailLength;
    copy.offsetMs = this.offsetMs;
    copy.songOffsets = new LinkedHashMap<>(this.songOffsets);
    copy.cardOn = this.cardOn;
    copy.cardStyle = this.cardStyle;
    copy.cardCorner = this.cardCorner;
    copy.cardX = this.cardX;
    copy.cardY = this.cardY;
    copy.cardSize = this.cardSize;
    copy.cardOpacity = this.cardOpacity;
    copy.cardCover = this.cardCover;
    copy.cardTimes = this.cardTimes;
    copy.cardLine = this.cardLine;
    copy.cardAccent = this.cardAccent;
    copy.ownCard = this.ownCard;
    copy.pausedCard = this.pausedCard;
    copy.headPlace = this.headPlace;
    copy.shareSong = this.shareSong;
    copy.shareCover = this.shareCover;
    copy.showShared = this.showShared;
    copy.showSharedCovers = this.showSharedCovers;
    copy.headOpacity = this.headOpacity;
    copy.headPanel = this.headPanel;
    copy.headRim = this.headRim;
    copy.headCorners = this.headCorners;
    copy.headSize = this.headSize;
    copy.headCover = this.headCover;
    copy.headWave = this.headWave;
    copy.headTime = this.headTime;
    copy.headAccent = this.headAccent;
    copy.headAccentMode = this.headAccentMode;
    copy.showOwnRole = this.showOwnRole;
    copy.mediaSources = this.mediaSources;
    copy.saved =this.saved == null ? new ArrayList<>() : new ArrayList<>(this.saved);
    return copy;
  }

  /** Takes type, colour, solid form, light, motion and karaoke from another look; place, sync, card stay. */
  public void copyLookFrom(Look other) {
    this.font = other.font;
    this.size = other.size;
    this.bold = other.bold;
    this.upper = other.upper;
    this.letterSpacing = other.letterSpacing;
    this.lineGap = other.lineGap;
    this.wrapWidth = other.wrapWidth;
    this.colorMode = other.colorMode;
    this.color = other.color;
    this.color2 = other.color2;
    this.flowSpeed = other.flowSpeed;
    this.depth = other.depth;
    this.sideShade = other.sideShade;
    this.bend = other.bend;
    this.outline = other.outline;
    this.outlineColor = other.outlineColor;
    this.selfLit = other.selfLit;
    this.glow = other.glow;
    this.glowColorMode = other.glowColorMode;
    this.glowColor = other.glowColor;
    this.glowReach = other.glowReach;
    this.sparkles = other.sparkles;
    this.shadow = other.shadow;
    this.throughWalls = other.throughWalls;
    this.enter = other.enter;
    this.exit = other.exit;
    this.idle = other.idle;
    this.idleSpeed = other.idleSpeed;
    this.idleAmount = other.idleAmount;
    this.arrange = other.arrange;
    this.karaoke = other.karaoke;
    this.karaokeStyle = other.karaokeStyle;
  }

  /** Puts back what a file with holes or explicit nulls left empty, and pulls numbers into a usable range. */
  void repair() {
    Look plain = new Look();
    if (this.lookVersion < CURRENT_VERSION) {
      // Older files saved the first defaults, under which words followed the player. The owner's rule is that they
      // never do, so those two values go back once; after this, turning them on again is the player's own choice.
      this.facing = plain.facing;
      this.carryWhenFast = plain.carryWhenFast;
      this.lookVersion = CURRENT_VERSION;
    }

    this.font = orDefault(this.font, plain.font);
    this.colorMode = orDefault(this.colorMode, plain.colorMode);
    this.glow = orDefault(this.glow, plain.glow);
    this.glowColorMode = orDefault(this.glowColorMode, plain.glowColorMode);
    this.enter = orDefault(this.enter, plain.enter);
    this.exit = orDefault(this.exit, plain.exit);
    this.idle = orDefault(this.idle, plain.idle);
    this.arrange = orDefault(this.arrange, plain.arrange);
    this.karaokeStyle = orDefault(this.karaokeStyle, plain.karaokeStyle);
    this.place = orDefault(this.place, plain.place);
    this.facing = orDefault(this.facing, plain.facing);
    this.screenSpot = orDefault(this.screenSpot, plain.screenSpot);
    this.cardStyle = orDefault(this.cardStyle, plain.cardStyle);
    this.cardCorner = orDefault(this.cardCorner, plain.cardCorner);
    this.pausedCard = orDefault(this.pausedCard, plain.pausedCard);
    this.headPlace = orDefault(this.headPlace, plain.headPlace);
    this.headOpacity = clamp(this.headOpacity, 0.0F, 1.0F, plain.headOpacity);
    this.headCorners = clamp(this.headCorners, 0.0F, 20.0F, plain.headCorners);
    this.headSize = clamp(this.headSize, 0.6F, 1.6F, plain.headSize);
    if (this.songOffsets == null) {
      this.songOffsets = new LinkedHashMap<>();
    }

    if (this.saved == null) {
      this.saved = new ArrayList<>();
    }

    for (int i = this.saved.size() - 1; i >= 0; i--) {
      SavedLook entry = this.saved.get(i);
      if (entry == null || entry.name == null || entry.look == null) {
        this.saved.remove(i);
      } else {
        entry.look.repair();
      }
    }

    // Every float: the JSON reader lets NaN and infinities in and the writer throws on them, and a huge spread would
    // cast rays over a billion blocks on the render thread.
    this.glowReach = clamp(this.glowReach, 0.3F, 2.5F, plain.glowReach);
    this.size = clamp(this.size, 0.1F, 8.0F, plain.size);
    this.letterSpacing = clamp(this.letterSpacing, -1.0F, 6.0F, plain.letterSpacing);
    this.lineGap = clamp(this.lineGap, -4.0F, 12.0F, plain.lineGap);
    this.flowSpeed = clamp(this.flowSpeed, 0.0F, 4.0F, plain.flowSpeed);
    this.idleSpeed = clamp(this.idleSpeed, 0.1F, 3.0F, plain.idleSpeed);
    this.idleAmount = clamp(this.idleAmount, 0.0F, 2.0F, plain.idleAmount);
    this.heightAdd = clamp(this.heightAdd, -6.0F, 20.0F, plain.heightAdd);
    this.spread = clamp(this.spread, 0.0F, 3.0F, plain.spread);
    this.cardX = clamp(this.cardX, -1.0F, 1.0F, plain.cardX);
    this.cardY = clamp(this.cardY, -1.0F, 1.0F, plain.cardY);
    this.depth = clamp(this.depth, 0.0F, 8.0F, plain.depth);
    this.sideShade = clamp(this.sideShade, 0.0F, 1.0F, plain.sideShade);
    this.bend = clamp(this.bend, 0.0F, 2.0F, plain.bend);
    this.distance = clamp(this.distance, 2.0F, 128.0F, plain.distance);
    this.screenSize = clamp(this.screenSize, 0.1F, 8.0F, plain.screenSize);
    this.cardSize = clamp(this.cardSize, 0.1F, 8.0F, plain.cardSize);
    this.cardOpacity = clamp(this.cardOpacity, 0.0F, 1.0F, plain.cardOpacity);
    this.wrapWidth = Math.max(20, this.wrapWidth);
    this.earlierLines = Math.max(0, Math.min(3, this.earlierLines));
    this.trailLength = Math.max(1, Math.min(16, this.trailLength));
  }

  private static String orDefault(String value, String fallback) {
    return value == null ? fallback : value;
  }

  private static float clamp(float value, float low, float high, float fallback) {
    return Float.isNaN(value) ? fallback : Math.max(low, Math.min(high, value));
  }
}
