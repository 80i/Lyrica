package net.lyrica.v1_21_11.hud;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.lyrica.api.LyricsBridge;
import net.lyrica.v1_21_11.Env;
import net.lyrica.v1_21_11.look.Palette;
import net.lyrica.v1_21_11.song.NowPlaying;

/**
 * The current song's cover as a texture, and a record pressed with that cover as its label. A cover is decoded on a
 * short-lived thread and registered on the render thread, so call everything here from the render thread.
 */
public final class AlbumTexture {

  public static final int SIZE = 128;
  public static final Identifier COVER = Identifier.fromNamespaceAndPath("lyrical", "cover");
  public static final Identifier RECORD = Identifier.fromNamespaceAndPath("lyrical", "cover_record");

  /** Which of a cover's corners are rounded off. */
  public enum Rounded { LEFT, TOP, ALL }

  // The card over the name: each cover at these sizes, because the game's samplers here have no mipmaps. Two slots,
  // taken in turn, so a card fading out the last song's cover still has it while the next one is loaded.
  private static final int[] CARD_SIZES = {SIZE, SIZE / 2, SIZE / 4};
  private static final int CARD_SLOTS = 2;
  private static final int MOST_SHARED = 128;

  private static final Identifier BLANK_RECORD = Identifier.fromNamespaceAndPath("lyrical", "blank_record");
  private static final Identifier ROUNDED_COVER = Identifier.fromNamespaceAndPath("lyrical", "cover_rounded");
  private static final Identifier PANEL_CORNERS = Identifier.fromNamespaceAndPath("lyrical", "panel_corners");
  private static final long FADE_IN_MS = 220L;

  private static final float CENTER = (SIZE - 1) * 0.5F;
  private static final float RADIUS = SIZE * 0.5F - 0.5F;
  private static final float LABEL_RADIUS = RADIUS * 0.58F;
  private static final float HOLE_RADIUS = RADIUS * 0.055F;
  private static final int VINYL = 0x15;

  private static final AtomicReference<Decoded> FINISHED = new AtomicReference<>();
  private static volatile byte[] wanted;
  private static byte[] loaded;
  private static byte[] loadedThumb;
  private static int[] loadedPixels;
  private static int[] roundedFrom;
  private static int roundedTexels = -1;
  private static Rounded roundedCorners;
  private static int cornersRadius;
  private static final Map<UUID, Shared> SHARED = new HashMap<>();
  private static long loadedAtMs;
  private static boolean blankMade;
  private static int cardSlot;
  private static final boolean[] CARD_SLOT_MADE = new boolean[CARD_SLOTS];

  private AlbumTexture() {
  }

  /** Follows what plays: starts decoding a new cover and registers a finished one. Cheap to call each frame. */
  public static void update(NowPlaying song) {
    byte[] art = song == null ? null : song.artPng();
    if (art != wanted) {
      wanted = art;
      if (art != null && art != loaded) {
        Thread reader = new Thread(() -> decode(art), "Lyrica cover");
        reader.setDaemon(true);
        reader.start();
      }
    }

    Decoded done = FINISHED.getAndSet(null);
    if (done != null) {
      if (done.art == wanted) {
        register(done);
      } else {
        done.close();
      }
    }
  }

  /** True when the textures hold this song's cover. */
  public static boolean shows(NowPlaying song) {
    return song != null && song.artPng() != null && song.artPng() == loaded;
  }

  /** How far this song's cover has faded in, 0 to 1; 0 while it has none. */
  public static float fade(NowPlaying song) {
    if (!shows(song)) {
      return 0.0F;
    }

    return Math.min(1.0F, (System.currentTimeMillis() - loadedAtMs) / (float) FADE_IN_MS);
  }

  /** The slot holding the loaded cover's textures for the card over the name. */
  public static int cardSlot() {
    return cardSlot;
  }

  /** @param size 0 for the largest of the card's textures, 1 and 2 for the next ones */
  public static Identifier cardCover(int slot, int size) {
    return Identifier.fromNamespaceAndPath("lyrical", "card_cover_" + slot + "_" + size);
  }

  /** How many pixels wide that size of the card's texture is. */
  public static int cardSize(int size) {
    return CARD_SIZES[size];
  }

  /** A record with a plain label, made when first asked for. */
  public static Identifier blankRecord() {
    if (!blankMade) {
      blankMade = true;
      Minecraft.getInstance().getTextureManager().register(BLANK_RECORD,
          new SmoothTexture("Lyrica blank record", pressRecord(null)));
    }

    return BLANK_RECORD;
  }

  /**
   * The loaded cover with some of its corners rounded off, to fit the card it stands on. Made again only when the cover or
   * the corners change. Colour stays in the see-through pixels, so smooth sampling gives no pale fringe. Only while
   * {@link #shows} says there is a cover.
   *
   * @param texels the radius, in the texture's pixels
   */
  public static Identifier roundedCover(int texels, Rounded rounded) {
    if (loadedPixels != roundedFrom || texels != roundedTexels || rounded != roundedCorners) {
      roundedFrom = loadedPixels;
      roundedTexels = texels;
      roundedCorners = rounded;
      NativeImage image = new NativeImage(SIZE, SIZE, false);
      for (int y = 0; y < SIZE; y++) {
        for (int x = 0; x < SIZE; x++) {
          boolean left = x < texels;
          boolean top = y < texels;
          boolean corner = (left || x >= SIZE - texels) && (top || y >= SIZE - texels)
              && (rounded == Rounded.ALL || rounded == Rounded.LEFT && left || rounded == Rounded.TOP && top);
          float alpha = 1.0F;
          if (corner) {
            float dx = left ? texels - (x + 0.5F) : x + 0.5F - (SIZE - texels);
            float dy = top ? texels - (y + 0.5F) : y + 0.5F - (SIZE - texels);
            alpha = clamp01(texels + 0.5F - (float) Math.sqrt(dx * dx + dy * dy));
          }

          image.setPixel(x, y, Math.round(alpha * 255.0F) << 24 | loadedPixels[y * SIZE + x] & 0xFFFFFF);
        }
      }

      Minecraft.getInstance().getTextureManager().register(ROUNDED_COVER, new SmoothTexture("Lyrica rounded cover", image));
    }

    return ROUNDED_COVER;
  }

  /**
   * A disc to take a panel's four corners from, drawn at one texture pixel to the screen pixel: its outermost pixel the
   * rim's colour, its edge blended. Made again only when the radius changes.
   *
   * @param radius the corner's radius, in screen pixels
   */
  public static Identifier panelCorners(int radius, int bodyRgb, int rimRgb) {
    if (radius != cornersRadius) {
      cornersRadius = radius;
      int side = radius * 2;
      NativeImage image = new NativeImage(side, side, false);
      for (int y = 0; y < side; y++) {
        for (int x = 0; x < side; x++) {
          float dx = x + 0.5F - radius;
          float dy = y + 0.5F - radius;
          float d = (float) Math.sqrt(dx * dx + dy * dy);
          int rgb = Palette.mix(bodyRgb, rimRgb, clamp01(d - radius + 1.5F));
          image.setPixel(x, y, Math.round(clamp01(radius + 0.5F - d) * 255.0F) << 24 | rgb);
        }
      }

      Minecraft.getInstance().getTextureManager().register(PANEL_CORNERS, new SmoothTexture("Lyrica panel corners", image));
    }

    return PANEL_CORNERS;
  }

  /** Lets go of the textures, for when the addon is switched off. */
  public static void clear() {
    wanted = null;
    Decoded done = FINISHED.getAndSet(null);
    if (done != null) {
      done.close();
    }

    TextureManager textures = Minecraft.getInstance().getTextureManager();
    if (loaded != null) {
      textures.release(COVER);
      textures.release(RECORD);
      loaded = null;
      loadedPixels = null;
    }

    if (roundedFrom != null) {
      textures.release(ROUNDED_COVER);
      roundedFrom = null;
      roundedTexels = -1;
    }

    if (cornersRadius != 0) {
      textures.release(PANEL_CORNERS);
      cornersRadius = 0;
    }

    for (int slot = 0; slot < CARD_SLOTS; slot++) {
      if (CARD_SLOT_MADE[slot]) {
        CARD_SLOT_MADE[slot] = false;
        for (int size = 0; size < CARD_SIZES.length; size++) {
          textures.release(cardCover(slot, size));
        }
      }
    }

    if (blankMade) {
      textures.release(BLANK_RECORD);
      blankMade = false;
    }

    for (Shared shared : SHARED.values()) {
      textures.release(shared.texture);
    }

    SHARED.clear();
  }

  /** The loaded cover made small enough to travel in a message, or null while there is none. */
  public static byte[] thumb() {
    return loaded == null ? null : loadedThumb;
  }

  /**
   * A cover another player sent, as a texture of its own for each player, replaced when theirs changes. Its 16-bit pixels
   * are read as just that, so nothing a stranger sends goes through an image decoder.
   */
  public static Identifier sharedCover(UUID player, byte[] thumb) {
    Shared known = SHARED.get(player);
    if (known != null && known.thumb == thumb) {
      return known.texture;
    }

    int side = LyricsBridge.COVER_THUMB_SIDE;
    NativeImage image = new NativeImage(side, side, false);
    for (int y = 0; y < side; y++) {
      for (int x = 0; x < side; x++) {
        int at = (y * side + x) * 2;
        int pixel = (thumb[at] & 0xFF) << 8 | thumb[at + 1] & 0xFF;
        int red = (pixel >> 11 & 0x1F) * 255 / 31;
        int green = (pixel >> 5 & 0x3F) * 255 / 63;
        int blue = (pixel & 0x1F) * 255 / 31;
        image.setPixel(x, y, 0xFF000000 | red << 16 | green << 8 | blue);
      }
    }

    // A crowded server's worth, which is far more than can be in sight
    if (known == null && SHARED.size() >= MOST_SHARED) {
      for (Shared shared : SHARED.values()) {
        Minecraft.getInstance().getTextureManager().release(shared.texture);
      }

      SHARED.clear();
    }

    Identifier texture = known != null ? known.texture : Identifier.fromNamespaceAndPath("lyrical",
        "shared_cover_" + player.toString().replace("-", ""));
    Minecraft.getInstance().getTextureManager().register(texture, new SmoothTexture("Lyrica shared cover", image));
    SHARED.put(player, new Shared(thumb, texture));
    return texture;
  }

  /** The cover squeezed to 5, 6 and 5 bits, each pixel the average of those it replaces. */
  private static byte[] shrink(int[] pixels) {
    int side = LyricsBridge.COVER_THUMB_SIDE;
    byte[] out = new byte[LyricsBridge.COVER_THUMB_BYTES];
    for (int y = 0; y < side; y++) {
      for (int x = 0; x < side; x++) {
        int red = 0;
        int green = 0;
        int blue = 0;
        int count = 0;
        for (int sy = y * SIZE / side; sy < (y + 1) * SIZE / side; sy++) {
          for (int sx = x * SIZE / side; sx < (x + 1) * SIZE / side; sx++) {
            int pixel = pixels[sy * SIZE + sx];
            red += pixel >> 16 & 255;
            green += pixel >> 8 & 255;
            blue += pixel & 255;
            count++;
          }
        }

        int packed = (red / count >> 3) << 11 | (green / count >> 2) << 5 | blue / count >> 3;
        int at = (y * side + x) * 2;
        out[at] = (byte) (packed >> 8);
        out[at + 1] = (byte) packed;
      }
    }

    return out;
  }

  private static void register(Decoded done) {
    TextureManager textures = Minecraft.getInstance().getTextureManager();
    // Registering under a name in use closes the texture that had it, so covers do not pile up.
    textures.register(COVER, new SmoothTexture("Lyrica cover", done.cover));
    textures.register(RECORD, new SmoothTexture("Lyrica record", done.record));
    cardSlot = (cardSlot + 1) % CARD_SLOTS;
    CARD_SLOT_MADE[cardSlot] = true;
    for (int size = 0; size < CARD_SIZES.length; size++) {
      textures.register(cardCover(cardSlot, size), new SmoothTexture("Lyrica card cover", done.card[size]));
    }

    loaded = done.art;
    loadedPixels = done.pixels;
    loadedThumb = done.thumb;
    loadedAtMs = System.currentTimeMillis();
  }

  private static void decode(byte[] art) {
    try (NativeImage source = NativeImage.read(art)) {
      if (art != wanted) {
        return;
      }

      int[] pixels = squareCrop(source);
      NativeImage cover = new NativeImage(SIZE, SIZE, false);
      for (int y = 0; y < SIZE; y++) {
        for (int x = 0; x < SIZE; x++) {
          cover.setPixel(x, y, pixels[y * SIZE + x]);
        }
      }

      Decoded overtaken = FINISHED.getAndSet(new Decoded(art, cover, pressRecord(pixels), shrunkenCopies(pixels),
          shrink(pixels), pixels));
      if (overtaken != null) {
        overtaken.close();
      }
    } catch (IOException | RuntimeException e) {
      Env.LOG.debug("Could not read a cover: {}", e.toString());
    }
  }

  /** The middle square of the image, averaged down to the texture's size. */
  private static int[] squareCrop(NativeImage source) {
    int width = source.getWidth();
    int height = source.getHeight();
    int side = Math.min(width, height);
    int left = (width - side) / 2;
    int top = (height - side) / 2;
    int[] from = source.getPixels();
    int[] out = new int[SIZE * SIZE];
    for (int y = 0; y < SIZE; y++) {
      int fromY = top + y * side / SIZE;
      int toY = Math.max(fromY + 1, top + (y + 1) * side / SIZE);
      for (int x = 0; x < SIZE; x++) {
        int fromX = left + x * side / SIZE;
        int toX = Math.max(fromX + 1, left + (x + 1) * side / SIZE);
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int sy = fromY; sy < toY; sy++) {
          for (int sx = fromX; sx < toX; sx++) {
            int pixel = from[sy * width + sx];
            red += (pixel >> 16) & 255;
            green += (pixel >> 8) & 255;
            blue += pixel & 255;
          }
        }

        int count = (toY - fromY) * (toX - fromX);
        out[y * SIZE + x] = 0xFF000000 | (red / count) << 16 | (green / count) << 8 | (blue / count);
      }
    }

    return out;
  }

  /**
   * The cover at each of the card's sizes, each pixel the average of those it replaces. The card draws a texture at
   * anything from its full size down to half, which smooth sampling alone leaves jagged on any hard edge in the art, so
   * each copy also gets the small blur that shrinking by that much calls for.
   */
  private static NativeImage[] shrunkenCopies(int[] pixels) {
    NativeImage[] images = new NativeImage[CARD_SIZES.length];
    for (int i = 0; i < images.length; i++) {
      int size = CARD_SIZES[i];
      int across = SIZE / size;
      int[] small = new int[size * size];
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          int red = 0;
          int green = 0;
          int blue = 0;
          for (int sy = 0; sy < across; sy++) {
            for (int sx = 0; sx < across; sx++) {
              int pixel = pixels[(y * across + sy) * SIZE + x * across + sx];
              red += (pixel >> 16) & 255;
              green += (pixel >> 8) & 255;
              blue += pixel & 255;
            }
          }

          int count = across * across;
          small[y * size + x] = (red / count) << 16 | (green / count) << 8 | (blue / count);
        }
      }

      NativeImage image = new NativeImage(size, size, false);
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          image.setPixel(x, y, 0xFF000000 | softened(small, size, x, y));
        }
      }

      images[i] = image;
    }

    return images;
  }

  /** One pixel with its neighbours weighed in, 1 2 1 each way; the image's edge repeats outward. */
  private static int softened(int[] pixels, int size, int x, int y) {
    int red = 0;
    int green = 0;
    int blue = 0;
    for (int dy = -1; dy <= 1; dy++) {
      int row = Math.max(0, Math.min(size - 1, y + dy));
      for (int dx = -1; dx <= 1; dx++) {
        int column = Math.max(0, Math.min(size - 1, x + dx));
        int weight = (2 - Math.abs(dx)) * (2 - Math.abs(dy));
        int pixel = pixels[row * size + column];
        red += ((pixel >> 16) & 255) * weight;
        green += ((pixel >> 8) & 255) * weight;
        blue += (pixel & 255) * weight;
      }
    }

    return (red / 16) << 16 | (green / 16) << 8 | (blue / 16);
  }

  /** @param label the cover's pixels, or null for a plain label with one radial mark so the turning still shows */
  private static NativeImage pressRecord(int[] label) {
    NativeImage image = new NativeImage(SIZE, SIZE, false);
    for (int y = 0; y < SIZE; y++) {
      for (int x = 0; x < SIZE; x++) {
        float dx = x - CENTER;
        float dy = y - CENTER;
        float d = (float) Math.sqrt(dx * dx + dy * dy);

        float groove = d > LABEL_RADIUS + 3.0F && d < RADIUS - 2.0F ? 0.5F + 0.5F * (float) Math.sin(d * 1.9F) : 0.0F;
        float sheen = (float) Math.pow(Math.abs(Math.cos(Math.atan2(dy, dx) - 0.9)), 18.0);
        int grey = Math.round(VINYL + groove * 7.0F + sheen * 38.0F);
        int rgb = grey << 16 | grey << 8 | grey;

        float onLabel = clamp01(LABEL_RADIUS + 0.5F - d);
        if (onLabel > 0.0F) {
          int ink;
          if (label != null) {
            int u = clampIndex(Math.round((dx / LABEL_RADIUS * 0.5F + 0.5F) * (SIZE - 1)));
            int v = clampIndex(Math.round((dy / LABEL_RADIUS * 0.5F + 0.5F) * (SIZE - 1)));
            ink = label[v * SIZE + u] & 0xFFFFFF;
          } else {
            ink = dx > 0.0F && Math.abs(dy) < 1.0F ? Palette.INK : Palette.PAPER;
          }

          rgb = Palette.mix(rgb, ink, onLabel);
        }

        // A hairline where the label meets the grooves.
        rgb = Palette.mix(rgb, Palette.PAPER, 0.32F * clamp01(1.0F - Math.abs(d - LABEL_RADIUS)));

        // Colour stays in the see-through pixels too, so smooth sampling gives no pale fringe at the edges.
        float alpha = clamp01(RADIUS + 0.5F - d) * clamp01(d - HOLE_RADIUS);
        image.setPixel(x, y, Math.round(alpha * 255.0F) << 24 | rgb);
      }
    }

    return image;
  }

  private static float clamp01(float value) {
    return Math.max(0.0F, Math.min(1.0F, value));
  }

  private static int clampIndex(int value) {
    return Math.max(0, Math.min(SIZE - 1, value));
  }

  private record Shared(byte[] thumb, Identifier texture) {

  }

  private record Decoded(byte[] art, NativeImage cover, NativeImage record, NativeImage[] card, byte[] thumb, int[] pixels) {

    void close() {
      this.cover.close();
      this.record.close();
      for (NativeImage image : this.card) {
        image.close();
      }
    }
  }

  // Covers get turned and shrunk on the card, where the default nearest sampling shimmers.
  static final class SmoothTexture extends DynamicTexture {

    SmoothTexture(String label, NativeImage image) {
      super(() -> label, image);
      this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
  }
}
