package net.lyrica.v1_21_10.look;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.labymod.api.util.I18n;

/**
 * The ready-made looks. Each starts from the plain look fields, so nothing of the one before lingers, and none touches
 * place, sync, card or master settings.
 */
public final class Presets {

  public static final String DEFAULT_ID = "billboard";

  /** The house look, as a {@link ShareCode}. It leads the row and is what a new player starts in. */
  public static final String LYRICA_ID = "lyrica";
  private static final String LYRICA_CODE =
      "LYRICA1-eNo9jt1Kw0AQhV9F5nop-WmSupcKrYHSBqOIV7I1kyZk2CmbDYql"
      + "7-6spp27MzPnO-cMFjTUnXHY3BHzAAoI9Blatl4u1b6qyl0t27H_QdDRIs-iMEsFB6YGtHcTKvhkYgc6TpJseV_k8yIBXaRREWepgpb4qz4hiiVeyMMBbRN4kQKePPUWr6wRqd32_iqPYpQmD8_l5ukF_vXjHJcXSbrKVuI5GTcQjjdGZ5pgaw2NIn3neDp2b4bo9oLWozBgvX3_KHcCxu8Q-qf3ryFpMM7wMPe6XH4BkWdcnA";

  private static final List<Preset> BUILT_IN = List.of(
      preset("billboard", l -> {
        // Road-side lettering: huge, thick, and lit only by the world it stands in.
        l.font = "BEBAS";
        l.bold = true;
        l.upper = true;
        l.size = 1.4F;
        l.letterSpacing = 0.5F;
        l.color = 0xF6F1E3;
        l.depth = 1.9F;
        l.sideShade = 0.38F;
        l.bend = 0.22F;
        l.enter = "RISE_UP";
        l.exit = "TIP_OVER";
      }),
      preset("marquee", l -> {
        // A theatre front: warm arch of letters with bulbs twinkling on the edges and a band sweeping through.
        l.font = "RIGHTEOUS";
        l.upper = true;
        l.size = 1.1F;
        l.letterSpacing = 0.8F;
        l.colorMode = "GRADIENT";
        l.color = 0xFFD76A;
        l.color2 = 0xFF6B2C;
        l.flowSpeed = 0.5F;
        l.depth = 1.1F;
        l.sideShade = 0.35F;
        l.bend = 0.3F;
        l.selfLit = true;
        l.glow = "SOFT";
        l.sparkles = true;
        l.enter = "FLIP";
        l.exit = "FLY_OUT";
        l.idle = "SWAY";
        l.idleSpeed = 0.6F;
        l.idleAmount = 0.5F;
        l.arrange = "ARC";
        l.karaoke = true;
        l.karaokeStyle = "SWEEP";
      }),
      preset("neon_tube", l -> {
        // A thin bent tube: almost no body, all light, and it stutters like a failing starter.
        l.font = "COMFORTAA";
        l.size = 1.05F;
        l.letterSpacing = 1.0F;
        l.color = 0xFF2F92;
        l.depth = 0.1F;
        l.sideShade = 0.8F;
        l.bend = 0.12F;
        l.selfLit = true;
        l.glow = "BRIGHT";
        l.shadow = false;
        l.enter = "FADE";
        l.exit = "FADE";
        l.idle = "FLICKER";
        l.idleSpeed = 0.7F;
        l.idleAmount = 0.6F;
      }),
      preset("chrome", l -> {
        // Car-badge metal: silver running over a heavy curved form, with dark edges and glints.
        l.font = "MONTSERRAT";
        l.bold = true;
        l.upper = true;
        l.size = 1.15F;
        l.letterSpacing = 0.3F;
        l.colorMode = "GRADIENT";
        l.color = 0xF4F8FC;
        l.color2 = 0x6F7F91;
        l.flowSpeed = 1.6F;
        l.depth = 1.5F;
        l.sideShade = 0.28F;
        l.bend = 0.55F;
        l.outline = true;
        l.outlineColor = 0x2B323A;
        l.selfLit = true;
        l.sparkles = true;
        l.enter = "FLY_IN";
        l.exit = "SHATTER";
        l.idle = "SWAY";
        l.idleSpeed = 0.5F;
        l.idleAmount = 0.4F;
      }),
      preset("ember", l -> {
        // Coals: a yellow-hot face over charred sides, smouldering, then carried off like ash.
        l.font = "PLAYFAIR";
        l.bold = true;
        l.size = 1.1F;
        l.colorMode = "GRADIENT";
        l.color = 0xFFD54A;
        l.color2 = 0xE5340B;
        l.flowSpeed = 0.9F;
        l.depth = 1.2F;
        l.sideShade = 0.16F;
        l.bend = 0.3F;
        l.selfLit = true;
        l.glow = "SOFT";
        l.glowColorMode = "CUSTOM";
        l.glowColor = 0xFF4A12;
        l.sparkles = true;
        l.enter = "SCATTER_IN";
        l.exit = "BLOW_AWAY";
        l.idle = "FLICKER";
        l.idleSpeed = 0.5F;
        l.idleAmount = 0.3F;
        l.arrange = "WORDS";
      }),
      preset("glacier", l -> {
        // Blocks of ice: the thickest form but one, pale sides as if light passes through, stacked and straight.
        l.font = "POPPINS";
        l.bold = true;
        l.upper = true;
        l.size = 1.25F;
        l.colorMode = "GRADIENT";
        l.color = 0xE6FAFF;
        l.color2 = 0x5BB6F2;
        l.flowSpeed = 0.3F;
        l.depth = 2.4F;
        l.sideShade = 0.72F;
        l.bend = 0.1F;
        l.glow = "SOFT";
        l.glowColorMode = "CUSTOM";
        l.glowColor = 0x7FDBFF;
        l.enter = "RISE_UP";
        l.exit = "SHATTER";
        l.idle = "BREATHE";
        l.idleSpeed = 0.4F;
        l.idleAmount = 0.35F;
        l.arrange = "STACK";
      }),
      preset("graffiti", l -> {
        // A marker tag: loud two-colour fill, a hard black keyline, words climbing like steps and never quite still.
        l.font = "COMIC_RELIEF";
        l.bold = true;
        l.size = 1.2F;
        l.colorMode = "GRADIENT";
        l.color = 0xFF3CAC;
        l.color2 = 0x2BD2FF;
        l.flowSpeed = 1.2F;
        l.depth = 0.7F;
        l.sideShade = 0.3F;
        l.bend = 0.75F;
        l.outline = true;
        l.outlineColor = 0x0E0E12;
        l.enter = "FLIP";
        l.exit = "BLOW_AWAY";
        l.idle = "JITTER";
        l.idleAmount = 0.35F;
        l.arrange = "STAIRS";
      }),
      preset("typewriter", l -> {
        // A page of type: small, flat and level, struck key by key; what has been sung turns ribbon red.
        l.font = "JETBRAINS_MONO";
        l.size = 0.8F;
        l.wrapWidth = 190;
        l.color = 0xEFE6D2;
        l.color2 = 0xD6453A;
        l.depth = 0.12F;
        l.sideShade = 0.6F;
        l.bend = 0.0F;
        l.enter = "TYPE";
        l.exit = "ERASE";
        l.karaoke = true;
        l.karaokeStyle = "FILL";
      }),
      preset("holo", l -> {
        // A projection: no body at all, wide-set cyan that shows through walls and drops characters.
        l.font = "POPPINS";
        l.upper = true;
        l.size = 0.95F;
        l.letterSpacing = 1.6F;
        l.color = 0x55F5FF;
        l.depth = 0.0F;
        l.bend = 0.2F;
        l.selfLit = true;
        l.glow = "SOFT";
        l.shadow = false;
        l.throughWalls = true;
        l.enter = "UNFOLD";
        l.exit = "ERASE";
        l.idle = "GLITCH";
        l.idleSpeed = 0.8F;
        l.idleAmount = 0.5F;
      }),
      preset("arcade", l -> {
        // A cabinet screen: chunky pixels hopping word by word, turning hot pink as they are sung.
        l.font = "PRESS_START";
        l.size = 0.72F;
        l.wrapWidth = 120;
        l.color = 0xFFE81F;
        l.color2 = 0xFF2D6F;
        l.depth = 1.3F;
        l.sideShade = 0.3F;
        l.bend = 0.0F;
        l.outline = true;
        l.outlineColor = 0x1A0B3D;
        l.selfLit = true;
        l.enter = "DROP";
        l.exit = "SHATTER";
        l.idle = "BOB";
        l.idleSpeed = 1.2F;
        l.idleAmount = 0.7F;
        l.karaoke = true;
        l.karaokeStyle = "FILL";
      }),
      preset("vapor", l -> {
        // Late-night pastel: very wide-set capitals, slowly rolling on a wave between pink and blue.
        l.font = "MONTSERRAT";
        l.upper = true;
        l.size = 0.95F;
        l.letterSpacing = 2.6F;
        l.colorMode = "GRADIENT";
        l.color = 0xFF71CE;
        l.color2 = 0x01CDFE;
        l.flowSpeed = 0.4F;
        l.depth = 0.45F;
        l.sideShade = 0.55F;
        l.bend = 0.4F;
        l.selfLit = true;
        l.glow = "SOFT";
        l.shadow = false;
        l.enter = "FLY_IN";
        l.exit = "FLY_OUT";
        l.idle = "WAVE";
        l.idleSpeed = 0.5F;
        l.idleAmount = 0.6F;
      }),
      preset("poster_stack", l -> {
        // A gig poster: tall red capitals packed in tight rows, dead straight; the sung word steps out.
        l.font = "BEBAS";
        l.upper = true;
        l.size = 1.3F;
        l.lineGap = -1.0F;
        l.color = 0xE8402A;
        l.color2 = 0xFFF3DC;
        l.depth = 0.6F;
        l.sideShade = 0.4F;
        l.bend = 0.0F;
        l.enter = "UNFOLD";
        l.exit = "TIP_OVER";
        l.arrange = "STACK";
        l.karaoke = true;
        l.karaokeStyle = "LIFT";
      }),
      preset("stone", l -> {
        // A carved monument: the deepest form of all, grey and unlit, it lands with weight and sinks back.
        l.font = "EAGLE_LAKE";
        l.bold = true;
        l.size = 1.15F;
        l.letterSpacing = 0.4F;
        l.color = 0xA8A496;
        l.depth = 2.8F;
        l.sideShade = 0.55F;
        l.bend = 0.0F;
        l.enter = "DROP";
        l.exit = "SINK";
      }),
      preset("candy", l -> {
        // Boiled sweets: a soft plump script in a white keyline, pale sides, bouncing and sparkling.
        l.font = "PACIFICO";
        l.bold = true;
        l.size = 1.15F;
        l.colorMode = "GRADIENT";
        l.color = 0xFF8FC0;
        l.color2 = 0xFFE680;
        l.flowSpeed = 0.8F;
        l.depth = 1.3F;
        l.sideShade = 0.68F;
        l.bend = 0.7F;
        l.outline = true;
        l.outlineColor = 0xFFFFFF;
        l.selfLit = true;
        l.sparkles = true;
        l.enter = "DROP";
        l.exit = "SHATTER";
        l.idle = "BOB";
        l.idleAmount = 0.8F;
      }),
      preset("ghost", l -> {
        // Barely there: a pale serif with no body and no shadow, hanging and drifting, seen through the walls.
        l.font = "PLAYFAIR";
        l.size = 1.05F;
        l.color = 0xDCE8EC;
        l.depth = 0.0F;
        l.bend = 0.3F;
        l.selfLit = true;
        l.glow = "SOFT";
        l.glowColorMode = "CUSTOM";
        l.glowColor = 0x8FBFD0;
        l.shadow = false;
        l.throughWalls = true;
        l.enter = "FADE";
        l.exit = "BLOW_AWAY";
        l.idle = "SWAY";
        l.idleSpeed = 0.4F;
        l.idleAmount = 0.6F;
      }),
      preset("disco", l -> {
        // The dance floor: a fat script in a racing rainbow, the brightest glow, every letter circling.
        l.font = "LOBSTER";
        l.bold = true;
        l.size = 1.2F;
        l.colorMode = "RAINBOW";
        l.flowSpeed = 2.4F;
        l.depth = 1.0F;
        l.sideShade = 0.4F;
        l.selfLit = true;
        l.glow = "BRIGHT";
        l.sparkles = true;
        l.enter = "SCATTER_IN";
        l.exit = "SHATTER";
        l.idle = "ORBIT_LETTERS";
        l.idleSpeed = 1.2F;
        l.idleAmount = 0.7F;
        l.arrange = "WORDS";
      }),
      preset("sleeve", l -> {
        // Dressed by the record: the cover's own colours, with a slow ripple in depth and unsung words held back.
        l.font = "RIGHTEOUS";
        l.bold = true;
        l.size = 1.1F;
        l.colorMode = "ALBUM_GRADIENT";
        l.flowSpeed = 0.5F;
        l.depth = 0.9F;
        l.sideShade = 0.4F;
        l.bend = 0.45F;
        l.glow = "SOFT";
        l.enter = "FLY_IN";
        l.exit = "FLY_OUT";
        l.idle = "RIPPLE";
        l.idleSpeed = 0.6F;
        l.idleAmount = 0.5F;
        l.karaoke = true;
        l.karaokeStyle = "DIM";
      }),
      preset("gold_leaf", l -> {
        // A sign-painter's shopfront: thin gilded script in a dark keyline, set on an arch, catching the light.
        l.font = "LOBSTER";
        l.size = 1.15F;
        l.colorMode = "GRADIENT";
        l.color = 0xFFE9A0;
        l.color2 = 0xC8902B;
        l.flowSpeed = 0.7F;
        l.depth = 0.35F;
        l.sideShade = 0.45F;
        l.bend = 0.25F;
        l.outline = true;
        l.outlineColor = 0x3A2408;
        l.sparkles = true;
        l.enter = "UNFOLD";
        l.exit = "ERASE";
        l.idle = "RIPPLE";
        l.idleSpeed = 0.3F;
        l.idleAmount = 0.3F;
        l.arrange = "ARC";
      }),
      preset("blockhead", l -> {
        // Made of the world: the game's own lettering, grass over dirt, as thick as a block and just as still.
        l.font = "DEFAULT";
        l.bold = true;
        l.size = 1.3F;
        l.letterSpacing = 0.5F;
        l.wrapWidth = 130;
        l.colorMode = "GRADIENT";
        l.color = 0x7ED957;
        l.color2 = 0x9C6B3F;
        l.flowSpeed = 0.0F;
        l.depth = 2.2F;
        l.sideShade = 0.5F;
        l.bend = 0.0F;
        l.enter = "RISE_UP";
        l.exit = "SINK";
      }),
      preset("relay", l -> {
        // A new colour for every line, handed on: bold rows that step back in depth and nod as they are sung.
        l.font = "POPPINS";
        l.bold = true;
        l.size = 1.1F;
        l.colorMode = "PER_LINE";
        l.flowSpeed = 0.6F;
        l.depth = 1.0F;
        l.sideShade = 0.35F;
        l.bend = 0.5F;
        l.enter = "FLY_IN";
        l.exit = "TIP_OVER";
        l.idle = "BOB";
        l.idleSpeed = 0.8F;
        l.idleAmount = 0.4F;
        l.arrange = "STAIRS";
        l.karaoke = true;
        l.karaokeStyle = "LIFT";
      })
  );

  private static final List<Preset> ALL = withHouseLook();

  private Presets() {
  }

  /** A ready-made look. Name and description come from {@code lyrical.preset.<id>.name} and {@code .desc}. */
  public record Preset(String id, Consumer<Look> apply) {

    public String name() {
      return I18n.translate("lyrical.preset." + this.id + ".name");
    }

    public String description() {
      return I18n.translate("lyrical.preset." + this.id + ".desc");
    }

    public void apply(Look look) {
      this.apply.accept(look);
    }

    /** A new look wearing this preset, for showing it without putting it on. */
    public Look look() {
      Look look = new Look();
      this.apply.accept(look);
      return look;
    }
  }

  public static List<Preset> all() {
    return ALL;
  }

  /** The preset with this id, or null. */
  public static Preset byId(String id) {
    for (int i = 0; i < ALL.size(); i++) {
      Preset preset = ALL.get(i);
      if (preset.id().equals(id)) {
        return preset;
      }
    }

    return null;
  }

  /** The Lyrica look, when its code reads; null otherwise. */
  public static Preset lyrica() {
    return byId(LYRICA_ID);
  }

  private static List<Preset> withHouseLook() {
    ShareCode.Shared house = ShareCode.decode(LYRICA_CODE);
    if (house == null) {
      return BUILT_IN;
    }

    List<Preset> all = new ArrayList<>(BUILT_IN.size() + 1);
    all.add(new Preset(LYRICA_ID, look -> look.copyLookFrom(house.look())));
    all.addAll(BUILT_IN);
    return List.copyOf(all);
  }

  private static Preset preset(String id, Consumer<Look> settings) {
    return new Preset(id, look -> {
      look.copyLookFrom(new Look());
      settings.accept(look);
    });
  }
}
