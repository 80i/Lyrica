package net.lyrica.v1_21_10.stage;

import java.util.List;
import net.lyrica.v1_21_10.look.Look;

/**
 * The enter, exit and idle animations. A letter's pose is worked out afresh each frame from the time, its index and the
 * line's seed, so there is nothing to keep between frames and any moment can be asked for in any order.
 *
 * <p>The line is on stage from 0 to its window. The enter plays at the start of the window and the exit plays after its
 * end, while the next line is already entering. Unknown names, say from an old file, mean no animation.
 */
final class Motions {

  private static final long DAY_MS = 86_400_000L;

  // An enter never takes more than this share of a short line, or the line would start leaving before it had arrived.
  private static final float ENTER_SHARE = 0.6F;
  private static final float ENTER_LIMIT_MS = 1500.0F;
  private static final float EXIT_LIMIT_MS = 1200.0F;
  private static final float IDLE_EASE_MS = 500.0F;
  // Inside a word that arrives together, its letters still follow each other by a hair so it does not look stamped.
  private static final float WORD_RIPPLE_MS = 12.0F;
  private static final int WORD_RIPPLE_LETTERS = 10;

  // The moment within a drop at which the letter first touches down, and the two smaller hops after it.
  private static final float FIRST_LANDING = 0.56F;
  private static final float SECOND_LANDING = 0.80F;
  private static final float THIRD_LANDING = 0.94F;
  private static final float FIRST_HOP = 0.11F;
  private static final float SECOND_HOP = 0.028F;

  private static final float TIP_LANDING = 0.72F;
  // Short of a full 90, so that idle motion added on top cannot carry the letter over edge-on and turn its face away.
  private static final float TIP_FLAT = 86.0F;

  private Motions() {
  }

  static void apply(Look look, ComposedLine line, long elapsedMs, long windowMs, long nowMs, long seed) {
    List<Letter> letters = line.letters;
    int count = letters.size();

    String enter = look.enter;
    boolean enterByWord = arrivesByWord(enter);
    float enterLimit = Math.min(ENTER_LIMIT_MS, Math.max(1L, windowMs) * ENTER_SHARE);
    float enterTime = Math.min(enterDuration(enter), enterLimit);
    float enterGap = fitStagger(enterStagger(enter), enterTime, enterByWord ? line.wordCount : count, enterLimit);
    float enterTotal = enterTime + enterGap * Math.max(0, (enterByWord ? line.wordCount : count) - 1);

    String exit = look.exit;
    boolean exitByWord = leavesByWord(exit);
    float exitTime = exitDuration(exit);
    int exitSteps = exitByWord ? line.wordCount : count;
    float exitGap = fitStagger(exitStagger(exit), exitTime, exitSteps, EXIT_LIMIT_MS);
    float exitTotal = exitTime + exitGap * Math.max(0, exitSteps - 1)
        + (exitByWord ? WORD_RIPPLE_MS * WORD_RIPPLE_LETTERS : 0.0F);

    float afterWindow = elapsedMs - windowMs;
    line.finished = afterWindow >= exitTotal;
    if (elapsedMs < 0L || line.finished) {
      for (int i = 0; i < count; i++) {
        letters.get(i).alpha = 0.0F;
      }

      return;
    }

    boolean leaving = afterWindow >= 0.0F;
    float idleStrength = look.idleAmount * smooth(elapsedMs / (enterTotal + IDLE_EASE_MS));
    if (leaving) {
      idleStrength *= 1.0F - smooth(afterWindow / Math.max(1.0F, exitTotal));
    }

    double clock = (nowMs % DAY_MS) * 0.001 * look.idleSpeed;
    // Which way the wind blows is decided once per line, so all its letters go together.
    float wind = noise(seed, 3, 0) < 0.5F ? -1.0F : 1.0F;

    for (int i = 0; i < count; i++) {
      Letter l = letters.get(i);
      float height = line.glyphHeight * l.homeScale;
      if (idleStrength > 0.0F) {
        idle(l, line, look.idle, idleStrength, clock, seed, height);
      }

      if (leaving) {
        float delay = exitDelay(l, exit, exitGap, count, wind, seed);
        exit(l, line, exit, clamp01((afterWindow - delay) / Math.max(1.0F, exitTime)), wind, seed, height);
      } else {
        float delay = enterDelay(l, enter, enterGap, count, seed);
        enter(l, enter, clamp01((elapsedMs - delay) / Math.max(1.0F, enterTime)), seed, height);
      }
    }
  }

  // Timing

  private static boolean arrivesByWord(String enter) {
    return "FLY_IN".equals(enter) || "FADE".equals(enter);
  }

  private static boolean leavesByWord(String exit) {
    return "FLY_OUT".equals(exit) || "FADE".equals(exit);
  }

  private static float enterDuration(String enter) {
    return switch (enter) {
      case "DROP" -> 640.0F;
      case "RISE_UP" -> 760.0F;
      case "FLY_IN" -> 720.0F;
      case "FLIP" -> 820.0F;
      case "UNFOLD" -> 600.0F;
      case "TYPE" -> 130.0F;
      case "SCATTER_IN" -> 800.0F;
      case "FADE" -> 380.0F;
      default -> 0.0F;
    };
  }

  private static float enterStagger(String enter) {
    return switch (enter) {
      case "DROP" -> 38.0F;
      case "RISE_UP" -> 34.0F;
      case "FLY_IN" -> 110.0F;
      case "FLIP" -> 30.0F;
      case "UNFOLD" -> 44.0F;
      case "TYPE" -> 52.0F;
      case "SCATTER_IN" -> 16.0F;
      case "FADE" -> 70.0F;
      default -> 0.0F;
    };
  }

  private static float exitDuration(String exit) {
    return switch (exit) {
      case "SINK" -> 620.0F;
      case "TIP_OVER" -> 780.0F;
      case "BLOW_AWAY" -> 900.0F;
      case "FLY_OUT" -> 620.0F;
      case "SHATTER" -> 1_050.0F;
      case "ERASE" -> 120.0F;
      case "FADE" -> 380.0F;
      default -> 0.0F;
    };
  }

  private static float exitStagger(String exit) {
    return switch (exit) {
      case "SINK" -> 16.0F;
      case "TIP_OVER" -> 26.0F;
      case "BLOW_AWAY" -> 22.0F;
      case "FLY_OUT" -> 80.0F;
      case "SHATTER" -> 5.0F;
      case "ERASE" -> 26.0F;
      case "FADE" -> 40.0F;
      default -> 0.0F;
    };
  }

  /** The wanted gap between letters, tightened if a long line would otherwise overrun the limit. */
  private static float fitStagger(float wanted, float duration, int steps, float limit) {
    if (steps <= 1) {
      return 0.0F;
    }

    return Math.max(0.0F, Math.min(wanted, (limit - duration) / (steps - 1)));
  }

  private static float enterDelay(Letter l, String enter, float gap, int count, long seed) {
    if ("SCATTER_IN".equals(enter)) {
      return noise(seed, l.index, 40) * gap * Math.max(0, count - 1);
    }

    // Typed along with the singing, when it is known when that is.
    if ("TYPE".equals(enter) && l.timed) {
      return l.singFrom;
    }

    return arrivesByWord(enter) ? wordDelay(l, gap) : l.index * gap;
  }

  private static float exitDelay(Letter l, String exit, float gap, int count, float wind, long seed) {
    int fromEnd = count - 1 - l.index;
    return switch (exit) {
      // The end of the line goes first when erasing, like holding backspace.
      case "ERASE" -> fromEnd * gap;
      // The downwind end lifts first, so letters do not fly through each other.
      case "BLOW_AWAY" -> (wind > 0.0F ? fromEnd : l.index) * gap;
      // All at once but not in the same instant: a crack running through.
      case "SHATTER" -> noise(seed, l.index, 41) * gap * Math.max(0, count - 1);
      case "FLY_OUT", "FADE" -> wordDelay(l, gap);
      default -> l.index * gap;
    };
  }

  private static float wordDelay(Letter l, float gap) {
    return l.word * gap + Math.min(l.letterInWord, WORD_RIPPLE_LETTERS) * WORD_RIPPLE_MS;
  }

  // Enter

  private static void enter(Letter l, String name, float p, long seed, float h) {
    if (p >= 1.0F) {
      return;
    }

    if (p <= 0.0F && enterDuration(name) > 0.0F) {
      l.alpha = 0.0F;
      return;
    }

    switch (name) {
      case "DROP" -> drop(l, p, seed, h);
      case "RISE_UP" -> {
        // Slow to get going, like something heavy being levered up, then swinging a little past upright.
        hinge(l, 86.0F * (1.0F - spring((float) Math.pow(p, 1.25), 4.6F, 8.0F)), h);
        l.alpha *= Math.min(1.0F, p * 5.0F);
      }
      case "FLY_IN" -> {
        float e = backOut(p, 1.25F);
        float rest = 1.0F - e;
        l.z -= 16.0F * h * rest;
        l.y -= 1.6F * h * rest;
        l.scale *= 0.55F + 0.45F * Math.min(1.0F, e);
        l.tiltX += 12.0F * rest;
        l.alpha *= Math.min(1.0F, p * 3.0F);
      }
      case "FLIP" -> {
        l.tiltY += 360.0F * (1.0F - spring(p, 4.2F, 7.0F));
        // Swings out toward the viewer to make room for its own turn.
        l.z += 1.6F * h * (float) Math.sin(Math.PI * p);
        l.alpha *= Math.min(1.0F, p * 8.0F);
      }
      case "UNFOLD" -> {
        door(l, 88.0F * (1.0F - spring(p, 5.0F, 8.0F)));
        l.alpha *= Math.min(1.0F, p * 6.0F);
      }
      case "TYPE" -> {
        float struck = (1.0F - p) * (1.0F - p);
        l.scale *= 1.0F + 0.28F * struck;
        l.z += h * (0.8F * struck - 0.25F * (float) Math.sin(Math.PI * p));
      }
      case "SCATTER_IN" -> {
        float rest = 1.0F - backOut(p, 0.9F);
        l.x += signed(seed, l.index, 10) * 12.0F * h * rest;
        l.y += signed(seed, l.index, 11) * 7.0F * h * rest;
        l.z += (signed(seed, l.index, 12) * 8.0F - 6.0F) * h * rest;
        l.tiltX += signed(seed, l.index, 13) * 260.0F * rest;
        l.tiltY += signed(seed, l.index, 14) * 320.0F * rest;
        l.tiltZ += signed(seed, l.index, 15) * 180.0F * rest;
        l.scale *= 0.7F + 0.3F * Math.min(1.0F, 1.0F - rest);
        l.alpha *= Math.min(1.0F, p * 3.0F);
      }
      case "FADE" -> {
        float shown = smooth(p);
        l.alpha *= shown;
        l.z -= 0.6F * h * (1.0F - shown);
        l.scale *= 0.97F + 0.03F * shown;
      }
      default -> {
      }
    }
  }

  private static void drop(Letter l, float p, long seed, float h) {
    float fallHeight = (7.0F + 2.0F * noise(seed, l.index, 20)) * h;
    l.y -= fallHeight * landingHeight(p);
    l.alpha *= Math.min(1.0F, p * 6.0F);
    if (p < FIRST_LANDING) {
      // A slight tumble in the air that has straightened out by the time it lands.
      float air = 1.0F - p / FIRST_LANDING;
      l.tiltZ += signed(seed, l.index, 21) * 16.0F * air * air;
      l.tiltY += signed(seed, l.index, 22) * 24.0F * air * air;
      return;
    }

    // The thud: the letter rocks on its foot and gives a little under its own weight.
    float q = (p - FIRST_LANDING) / (1.0F - FIRST_LANDING);
    float fade = (1.0F - q) * (1.0F - q);
    hinge(l, 7.0F * (float) Math.sin(q * Math.PI * 3.0) * fade, h);
    l.scale *= 1.0F - 0.07F * (float) Math.sin(q * Math.PI) * fade;
  }

  /** Height above the ground from 1 down to 0: a gravity fall, then two smaller parabolas. */
  private static float landingHeight(float p) {
    if (p < FIRST_LANDING) {
      float t = p / FIRST_LANDING;
      return 1.0F - t * t;
    }

    if (p < SECOND_LANDING) {
      return arch((p - FIRST_LANDING) / (SECOND_LANDING - FIRST_LANDING)) * FIRST_HOP;
    }

    if (p < THIRD_LANDING) {
      return arch((p - SECOND_LANDING) / (THIRD_LANDING - SECOND_LANDING)) * SECOND_HOP;
    }

    return 0.0F;
  }

  // Exit

  private static void exit(Letter l, ComposedLine line, String name, float q, float wind, long seed, float h) {
    if (q <= 0.0F) {
      return;
    }

    if (q >= 1.0F) {
      l.alpha = 0.0F;
      return;
    }

    switch (name) {
      case "SINK" -> {
        // A small lift first, as if the ground had to let go. Letters never go into the ground: the stage stops them on
        // it, so they settle down onto it, drawing together and fading as they arrive.
        float e = backIn(q, 1.4F);
        l.y += (line.height - l.homeY + h) * e;
        l.scale *= 1.0F - 0.55F * smooth((q - 0.35F) / 0.65F);
        l.tiltX += signed(seed, l.index, 30) * 6.0F * q;
        l.tiltZ += signed(seed, l.index, 31) * 4.0F * q;
        l.alpha *= 1.0F - smooth((q - 0.6F) / 0.4F);
      }
      case "TIP_OVER" -> {
        hinge(l, tipAngle(q), h);
        l.alpha *= 1.0F - smooth((q - 0.8F) / 0.2F);
      }
      case "BLOW_AWAY" -> {
        float e = (float) Math.pow(q, 2.2);
        float gust = noise(seed, l.index, 32);
        l.x += wind * (24.0F + 12.0F * gust) * h * e;
        l.y -= (1.5F + 5.0F * noise(seed, l.index, 33)) * h * e;
        l.y += 0.4F * h * q * (float) Math.sin(q * 8.0F + l.index);
        l.z += 2.5F * h * q * (float) Math.sin(q * 5.0F + l.index * 1.7F);
        l.tiltY += wind * (180.0F + 200.0F * gust) * e;
        l.tiltZ += wind * (60.0F + 80.0F * noise(seed, l.index, 34)) * e;
        l.tiltX += signed(seed, l.index, 35) * 120.0F * e;
        l.alpha *= 1.0F - smooth((q - 0.45F) / 0.55F);
      }
      case "FLY_OUT" -> {
        float e = backIn(q, 1.6F);
        l.z -= 20.0F * h * e;
        l.y -= 1.0F * h * e;
        l.tiltX += 10.0F * e;
        l.scale *= 1.0F - 0.4F * clamp01(e);
        l.alpha *= 1.0F - smooth((q - 0.55F) / 0.45F);
      }
      case "SHATTER" -> shatter(l, line, q, seed, h);
      case "ERASE" -> {
        l.scale *= 1.0F - 0.85F * smooth(q);
        l.z -= 0.5F * h * q;
        l.alpha *= 1.0F - q;
      }
      case "FADE" -> {
        float gone = smooth(q);
        l.alpha *= 1.0F - gone;
        l.z -= 0.6F * h * gone;
      }
      default -> {
      }
    }
  }

  /** The angle of a letter falling over backward: a nudge the wrong way, a fall that speeds up, a bounce flat. */
  private static float tipAngle(float q) {
    if (q < TIP_LANDING) {
      float u = q / TIP_LANDING;
      return TIP_FLAT * (float) Math.pow(u, 2.6) - 5.0F * (float) Math.sin(Math.PI * Math.min(1.0F, u / 0.3F));
    }

    float v = (q - TIP_LANDING) / (1.0F - TIP_LANDING);
    return TIP_FLAT - 11.0F * Math.abs((float) Math.sin(v * Math.PI * 2.0)) * (1.0F - v) * (1.0F - v);
  }

  private static void shatter(Letter l, ComposedLine line, float q, long seed, float h) {
    if (line.outlined) {
      // Real letters break into their own pieces where they stand; see LetterMesher.shards.
      l.shards = q;
      l.z -= 0.5F * h * q;
      l.alpha *= 1.0F - smooth((q - 0.6F) / 0.4F);
      return;
    }

    // A picture from the font's texture cannot break, so it flies off whole.
    float out = 1.0F - (1.0F - q) * (1.0F - q) * (1.0F - q);
    float fromMiddle = l.homeX / Math.max(1.0F, line.width * 0.5F);
    l.x += (fromMiddle * 0.7F + signed(seed, l.index, 36) * 0.6F) * 14.0F * h * out;
    l.y -= (0.4F + noise(seed, l.index, 37)) * 9.0F * h * out;
    // The burst slows while gravity keeps pulling, so the pieces arc over and fall.
    l.y += 12.0F * h * q * q;
    l.z += signed(seed, l.index, 38) * 12.0F * h * out;
    l.tiltX += (300.0F + 420.0F * noise(seed, l.index, 39)) * out * direction(seed, l.index, 50);
    l.tiltY += (300.0F + 420.0F * noise(seed, l.index, 51)) * out * direction(seed, l.index, 52);
    l.tiltZ += (120.0F + 300.0F * noise(seed, l.index, 53)) * out * direction(seed, l.index, 54);
    l.scale *= 1.0F - 0.25F * q;
    l.alpha *= 1.0F - smooth((q - 0.55F) / 0.45F);
  }

  // Idle

  private static void idle(Letter l, ComposedLine line, String name, float amount, double clock, long seed, float h) {
    switch (name) {
      case "BREATHE" -> {
        float s = 1.0F + 0.045F * amount * wave(clock * 1.7, l.index * 0.02F);
        float middle = line.height * 0.5F;
        l.x *= s;
        l.y = middle + (l.y - middle) * s;
        l.scale *= s;
      }
      case "BOB" -> {
        double phase = clock * 3.0 + l.word * 0.9;
        l.y -= 0.17F * h * amount * (float) Math.sin(phase);
        l.tiltX += 3.0F * amount * (float) Math.cos(phase);
      }
      case "SWAY" -> {
        float turn = (float) Math.toRadians(9.0F * amount * wave(clock * 0.9, 0.0F));
        float roll = (float) Math.toRadians(1.3F * amount * wave(clock * 0.9, 1.2F));
        float at = l.x;
        l.x = at * (float) Math.cos(turn);
        l.z -= at * (float) Math.sin(turn);
        l.y += at * (float) Math.sin(roll);
        l.tiltY += (float) Math.toDegrees(turn);
        l.tiltZ += (float) Math.toDegrees(roll);
      }
      case "WAVE" -> {
        double phase = clock * 2.6 - l.homeX / h * 0.7;
        l.y -= 0.24F * h * amount * (float) Math.sin(phase);
        l.tiltX += 11.0F * amount * (float) Math.cos(phase);
      }
      case "RIPPLE" -> {
        double phase = clock * 2.4 - Math.abs(l.homeX) / h * 0.8;
        l.z += 0.65F * h * amount * (float) Math.sin(phase);
        l.tiltY += (l.homeX < 0.0F ? -7.0F : 7.0F) * amount * (float) Math.cos(phase);
      }
      case "JITTER" -> {
        int step = (int) (clock * 14.0);
        l.x += signed(seed, l.index, step * 4) * 0.05F * h * amount;
        l.y += signed(seed, l.index, step * 4 + 1) * 0.05F * h * amount;
        l.z += signed(seed, l.index, step * 4 + 2) * 0.1F * h * amount;
        l.tiltZ += signed(seed, l.index, step * 4 + 3) * 3.0F * amount;
      }
      case "FLICKER" -> l.brightness *= flicker(l, amount, clock, seed);
      case "GLITCH" -> glitch(l, line, amount, clock, seed, h);
      case "ORBIT_LETTERS" -> {
        double phase = clock * 2.8 + l.index * 0.6;
        float radius = 0.13F * h * amount;
        l.x += radius * (float) Math.cos(phase);
        l.y += radius * (float) Math.sin(phase);
        l.z += radius * 1.6F * (float) Math.sin(phase + 0.8);
      }
      default -> {
      }
    }
  }

  /** A tube that is mostly steady, with short fits where the whole line stutters and now and then one letter fails. */
  private static float flicker(Letter l, float amount, double clock, long seed) {
    double beats = clock / 2.2;
    int beat = (int) beats;
    float within = (float) (beats - beat);
    int stutter = (int) (clock * 30.0);
    if (within < 0.28F && noise(seed, beat, 60) < 0.3F + 0.4F * amount && noise(seed, stutter, 61) > 0.5F) {
      return 0.22F;
    }

    if (noise(seed, l.index, beat * 2 + 62) < 0.06F * amount && noise(seed, l.index, stutter + 63) > 0.45F) {
      return 0.28F;
    }

    return 1.0F;
  }

  private static void glitch(Letter l, ComposedLine line, float amount, double clock, long seed, float h) {
    if (l.wholeRow || line.glitchGlyphs.length == 0) {
      return;
    }

    int moment = (int) (clock * 7.0);
    if (noise(seed, l.index, moment * 2) >= 0.05F * amount) {
      return;
    }

    int pick = (int) (noise(seed, l.index, moment * 2 + 1) * line.glitchGlyphs.length);
    l.glyph = line.glitchGlyphs[pick];
    l.width = line.glitchWidths[pick];
    l.x += signed(seed, l.index, moment * 2 + 70) * 0.12F * h;
  }

  private static float wave(double phase, float lag) {
    return (float) Math.sin(phase - lag);
  }

  // Hinges

  /**
   * Turns a letter around its foot rather than its centre: the centre travels on the circle around the foot, so the
   * foot stays planted while the top swings.
   */
  private static void hinge(Letter l, float degrees, float h) {
    double a = Math.toRadians(degrees);
    float half = h * 0.5F;
    l.tiltX += degrees;
    l.y += half * (1.0F - (float) Math.cos(a));
    l.z -= half * (float) Math.sin(a);
  }

  /** Turns a letter around its left edge, like a door. */
  private static void door(Letter l, float degrees) {
    double a = Math.toRadians(degrees);
    float half = l.homeWidth * l.homeScale * 0.5F;
    l.tiltY += degrees;
    l.x += half * ((float) Math.cos(a) - 1.0F);
    l.z -= half * (float) Math.sin(a);
  }

  /**
   * Letters from the font's texture only have a front, so a spin past edge-on would make one vanish for half of every
   * turn. Past edge-on it continues from the other edge instead, which looks the same as the far side coming around.
   */
  static float faceFront(float degrees) {
    if (degrees >= -90.0F && degrees <= 90.0F) {
      return degrees;
    }

    return degrees - 180.0F * Math.round(degrees / 180.0F);
  }

  // Easing

  static float clamp01(float value) {
    return value <= 0.0F ? 0.0F : Math.min(value, 1.0F);
  }

  static float smooth(float value) {
    float t = clamp01(value);
    return t * t * (3.0F - 2.0F * t);
  }

  /** Runs past 1 by an amount set by {@code pull}, then comes back to exactly 1. */
  private static float backOut(float p, float pull) {
    float t = p - 1.0F;
    return 1.0F + (pull + 1.0F) * t * t * t + pull * t * t;
  }

  /** Goes a little below 0 first, by an amount set by {@code pull}, then speeds up to exactly 1. */
  private static float backIn(float p, float pull) {
    return (pull + 1.0F) * p * p * p - pull * p * p;
  }

  /** A damped spring from 0 to rest at 1 that overshoots and settles; the last factor makes it end exactly at 1. */
  private static float spring(float p, float decay, float frequency) {
    return 1.0F - (float) (Math.exp(-decay * p) * Math.cos(p * frequency)) * (1.0F - p);
  }

  /** A parabola from 0 up to 1 and back to 0 as {@code t} goes from 0 to 1. */
  private static float arch(float t) {
    return 4.0F * t * (1.0F - t);
  }

  // Noise

  private static final float TOP_24_BITS_TO_UNIT = 1.0F / (1 << 24);

  /** A number from 0 to 1 that depends only on its inputs. */
  static float noise(long seed, int a, int b) {
    long h = scramble(scramble(seed + a) + b);
    return (h >>> 40) * TOP_24_BITS_TO_UNIT;
  }

  /** The finishing step of MurmurHash3: every input bit reaches every output bit. */
  private static long scramble(long value) {
    long h = value ^ (value >>> 33);
    h *= 0xFF51AFD7ED558CCDL;
    h ^= h >>> 33;
    h *= 0xC4CEB9FE1A85EC53L;
    return h ^ (h >>> 33);
  }

  private static float signed(long seed, int a, int b) {
    return noise(seed, a, b) * 2.0F - 1.0F;
  }

  private static float direction(long seed, int a, int b) {
    return noise(seed, a, b) < 0.5F ? -1.0F : 1.0F;
  }
}
