package net.lyrica.v1_21_11.look;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.Arrays;
import net.lyrica.v1_21_11.Env;
import net.lyrica.v1_21_11.song.NowPlaying;

/** Colours: the named ones, the rainbow, mixing, and the two colours of an album cover. All 0xRRGGBB unless noted. */
public final class Palette {

  public static final int PAPER = 0xF6F1E3;
  public static final int INK = 0x12110F;
  public static final int ACCENT = 0xFF5A36;
  public static final int AMBER = 0xFFC24A;
  public static final int EMBER = 0xE5340B;
  public static final int ROSE = 0xFF2F92;
  public static final int CANDY = 0xFF8FC0;
  public static final int LEMON = 0xFFE81F;
  public static final int MOSS = 0x7ED957;
  public static final int TEAL = 0x01CDFE;
  public static final int ICE = 0xBFEFFF;
  public static final int STEEL = 0x6F7F91;
  public static final int STONE = 0xA8A496;
  public static final int VIOLET = 0x8E5BFF;

  /** The row of squares offered in colour controls. */
  public static final int[] SWATCHES = {
      PAPER, STONE, STEEL, INK, ACCENT, EMBER, AMBER, LEMON, MOSS, TEAL, ICE, VIOLET, ROSE, CANDY
  };

  private static final int[] NO_ALBUM = {PAPER, AMBER};
  private static final int HUE_BUCKETS = 12;
  private static final int SAMPLES_PER_SIDE = 48;

  private static volatile AlbumColors album;

  private Palette() {
  }

  /** A colour of the rainbow: neighbouring indexes sit close together and the whole band flows with time. */
  public static int rgbAt(int index, long nowMs, float speed) {
    float hue = index * 0.035F + (float) ((nowMs % 3_600_000L) * 0.00012 * speed);
    return hsv(hue - (float) Math.floor(hue), 0.78F, 1.0F);
  }

  /** Mixes two colours, the top byte too; {@code t} 0 gives the first. */
  public static int mix(int a, int b, float t) {
    float amount = Math.max(0.0F, Math.min(1.0F, t));
    return (mixByte(a >>> 24, b >>> 24, amount) << 24)
        | (mixByte((a >> 16) & 255, (b >> 16) & 255, amount) << 16)
        | (mixByte((a >> 8) & 255, (b >> 8) & 255, amount) << 8)
        | mixByte(a & 255, b & 255, amount);
  }

  /** Multiplies the brightness of a colour, leaving the top byte alone. */
  public static int dim(int rgb, float factor) {
    float f = Math.max(0.0F, factor);
    return (rgb & 0xFF000000)
        | (scaleByte((rgb >> 16) & 255, f) << 16)
        | (scaleByte((rgb >> 8) & 255, f) << 8)
        | scaleByte(rgb & 255, f);
  }

  /** Hue, saturation and value from 0 to 1. */
  public static int hsv(float hue, float saturation, float value) {
    float h = (hue - (float) Math.floor(hue)) * 6.0F;
    int sector = (int) h;
    float part = h - sector;
    float p = value * (1.0F - saturation);
    float q = value * (1.0F - saturation * part);
    float t = value * (1.0F - saturation * (1.0F - part));
    return switch (sector) {
      case 0 -> pack(value, t, p);
      case 1 -> pack(q, value, p);
      case 2 -> pack(p, value, t);
      case 3 -> pack(p, q, value);
      case 4 -> pack(t, p, value);
      default -> pack(value, p, q);
    };
  }

  /** Reads "#RRGGBB" or "RRGGBB"; anything else gives the fallback. */
  public static int parseHex(String text, int fallback) {
    if (text == null) {
      return fallback;
    }

    String digits = text.strip();
    if (digits.startsWith("#")) {
      digits = digits.substring(1);
    }

    if (digits.length() != 6) {
      return fallback;
    }

    try {
      return Integer.parseInt(digits, 16);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public static String hex(int rgb) {
    return String.format("#%06X", rgb & 0xFFFFFF);
  }

  /**
   * Two colours from the song's cover, bright enough to read as letters. Do not change the array: it is shared. The
   * cover is decoded on a short-lived thread, so until that is done, and with no cover, the song gets colours from its
   * name, which at least stay the same each time it plays.
   */
  public static int[] albumColors(NowPlaying song) {
    if (song == null) {
      return NO_ALBUM;
    }

    AlbumColors known = album;
    if (known != null && known.source == song) {
      return known.colors;
    }

    String key = song.key();
    byte[] art = song.artPng();
    boolean sameSong = known != null && known.key.equals(key);
    if (sameSong && known.art == art) {
      album = new AlbumColors(song, key, art, known.colors);
      return known.colors;
    }

    int[] colors = sameSong ? known.colors : fromName(key);
    album = new AlbumColors(song, key, art, colors);
    if (art != null) {
      Thread reader = new Thread(() -> readCover(key, art), "Lyrica cover colours");
      reader.setDaemon(true);
      reader.start();
    }

    return colors;
  }

  private static void readCover(String key, byte[] art) {
    int[] colors = fromCover(art);
    AlbumColors known = album;
    if (colors != null && known != null && known.art == art && known.key.equals(key)) {
      album = new AlbumColors(known.source, key, art, colors);
    }
  }

  private static int[] fromName(String key) {
    float hue = (key.hashCode() & 0xFFFF) / 65536.0F;
    return new int[] {hsv(hue, 0.55F, 1.0F), hsv(hue + 0.12F, 0.7F, 0.95F)};
  }

  /** The main colour of a cover in a message: 16 bits a pixel, red 5, green 6, blue 5. Read as plain pixels. */
  public static int thumbColor(byte[] thumb) {
    int[] pixels = new int[thumb.length / 2];
    for (int i = 0; i < pixels.length; i++) {
      int pixel = (thumb[i * 2] & 0xFF) << 8 | thumb[i * 2 + 1] & 0xFF;
      pixels[i] = 0xFF000000 | (pixel >> 11 & 0x1F) * 255 / 31 << 16 | (pixel >> 5 & 0x3F) * 255 / 63 << 8
          | (pixel & 0x1F) * 255 / 31;
    }

    return fromPixels(pixels)[0];
  }

  /** A colour for a song with no cover to go by, the same each time it plays. */
  public static int nameColor(String title, String artist) {
    float hue = ((title.hashCode() * 31 + artist.hashCode()) & 0xFFFF) / 65536.0F;
    return hsv(hue, 0.55F, 1.0F);
  }

  private static int[] fromCover(byte[] art) {
    try (NativeImage image = NativeImage.read(art)) {
      int stepX = Math.max(1, image.getWidth() / SAMPLES_PER_SIDE);
      int stepY = Math.max(1, image.getHeight() / SAMPLES_PER_SIDE);
      int[] pixels = new int[(image.getWidth() / stepX + 1) * (image.getHeight() / stepY + 1)];
      int count = 0;
      for (int y = 0; y < image.getHeight(); y += stepY) {
        for (int x = 0; x < image.getWidth(); x += stepX) {
          pixels[count++] = image.getPixel(x, y);
        }
      }

      return fromPixels(Arrays.copyOf(pixels, count));
    } catch (IOException | RuntimeException e) {
      Env.LOG.debug("Could not read the cover's colours: {}", e.toString());
      return null;
    }
  }

  private static int[] fromPixels(int[] pixels) {
    float[] weight = new float[HUE_BUCKETS];
    float[] hueX = new float[HUE_BUCKETS];
    float[] hueY = new float[HUE_BUCKETS];
    float[] satSum = new float[HUE_BUCKETS];
    for (int i = 0; i < pixels.length; i++) {
      int argb = pixels[i];
      if ((argb >>> 24) < 128) {
        continue;
      }

      float r = ((argb >> 16) & 255) / 255.0F;
      float g = ((argb >> 8) & 255) / 255.0F;
      float b = (argb & 255) / 255.0F;
      float max = Math.max(r, Math.max(g, b));
      float min = Math.min(r, Math.min(g, b));
      float chroma = max - min;
      float saturation = max <= 0.0F ? 0.0F : chroma / max;
      // Near-greys and near-blacks have no hue worth taking.
      if (saturation < 0.2F || max < 0.2F) {
        continue;
      }

      float hue = hueOf(r, g, b, max, chroma);
      int bucket = Math.min(HUE_BUCKETS - 1, (int) (hue * HUE_BUCKETS));
      float amount = saturation * max;
      weight[bucket] += amount;
      hueX[bucket] += (float) Math.cos(hue * Math.PI * 2.0) * amount;
      hueY[bucket] += (float) Math.sin(hue * Math.PI * 2.0) * amount;
      satSum[bucket] += saturation * amount;
    }

    int first = -1;
    for (int i = 0; i < HUE_BUCKETS; i++) {
      if (weight[i] > 0.0F && (first < 0 || weight[i] > weight[first])) {
        first = i;
      }
    }

    if (first < 0) {
      // A black and white cover.
      return new int[] {PAPER, hsv(0.0F, 0.0F, 0.72F)};
    }

    int second = -1;
    for (int i = 0; i < HUE_BUCKETS; i++) {
      int apart = Math.abs(i - first);
      if (Math.min(apart, HUE_BUCKETS - apart) >= 2 && weight[i] > 0.0F && (second < 0 || weight[i] > weight[second])) {
        second = i;
      }
    }

    int main = readable(hueX[first], hueY[first], satSum[first] / weight[first], 0.0F);
    // A cover of one hue still gets a gradient, by moving a little along the wheel.
    int other = second < 0
        ? readable(hueX[first], hueY[first], satSum[first] / weight[first] * 0.6F, 0.08F)
        : readable(hueX[second], hueY[second], satSum[second] / weight[second], 0.0F);
    return new int[] {main, other};
  }

  private static int readable(float hueX, float hueY, float saturation, float shift) {
    float hue = (float) (Math.atan2(hueY, hueX) / (Math.PI * 2.0)) + shift;
    return hsv(hue, Math.max(0.4F, Math.min(0.9F, saturation)), 1.0F);
  }

  private static float hueOf(float r, float g, float b, float max, float chroma) {
    float hue;
    if (max == r) {
      hue = (g - b) / chroma;
    } else if (max == g) {
      hue = (b - r) / chroma + 2.0F;
    } else {
      hue = (r - g) / chroma + 4.0F;
    }

    hue /= 6.0F;
    return hue < 0.0F ? hue + 1.0F : hue;
  }

  private static int pack(float r, float g, float b) {
    return (Math.round(r * 255.0F) << 16) | (Math.round(g * 255.0F) << 8) | Math.round(b * 255.0F);
  }

  private static int mixByte(int a, int b, float t) {
    return Math.round(a + (b - a) * t);
  }

  private static int scaleByte(int value, float factor) {
    return Math.min(255, Math.round(value * factor));
  }

  private record AlbumColors(NowPlaying source, String key, byte[] art, int[] colors) {

  }
}
