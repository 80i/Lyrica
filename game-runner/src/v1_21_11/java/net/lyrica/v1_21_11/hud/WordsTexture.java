package net.lyrica.v1_21_11.hud;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.labymod.api.client.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.lyrica.api.LyricsBridge.TextImage;
import net.lyrica.v1_21_11.Env;

/**
 * Words on a card in scripts LabyMod's tag font does not have, as pictures made with the fonts on this PC. Java lays them
 * out properly, joined letters and right-to-left runs included. White on clear, so that a card can tint them. A picture
 * is made on a short-lived thread and registered on the render thread, so until then there is none.
 */
public final class WordsTexture {

  // Text pixels are this many picture pixels, which leaves them sharp at any size a card gets
  private static final float PICTURE_PER_TEXT = 4.0F;
  private static final float EM_TEXT_PIXELS = 9.6F;
  private static final float CAP_TEXT_PIXELS = 7.0F;
  private static final int MOST_KEPT = 48;
  private static final String ELLIPSIS = "…";
  private static final String[] FAMILIES = {"Segoe UI", "Tahoma", "Microsoft YaHei", "Yu Gothic", "Malgun Gothic",
      "Nirmala UI", "Leelawadee UI", "Ebrima", "Gadugi", "Segoe UI Symbol", "Arial", "Dialog"};

  private static final Map<String, Entry> KEPT = new LinkedHashMap<>(MOST_KEPT, 0.75F, true);
  private static int nextId;

  private WordsTexture() {
  }

  /** The picture of these words, or null while it is still being made. On the render thread. */
  public static TextImage of(String text, boolean bold, float mostWidth) {
    String key = (bold ? "b" : "p") + Math.round(mostWidth) + ":" + text;
    Entry entry = KEPT.get(key);
    if (entry == null) {
      entry = new Entry(nextId++);
      KEPT.put(key, entry);
      forgetOldest();
      Entry filling = entry;
      Thread maker = new Thread(() -> make(filling, text, bold, mostWidth), "Lyrica words");
      maker.setDaemon(true);
      maker.start();
    }

    if (entry.image == null && entry.pixels != null) {
      entry.image = register(entry);
    }

    return entry.image;
  }

  private static void forgetOldest() {
    Iterator<Entry> oldest = KEPT.values().iterator();
    while (KEPT.size() > MOST_KEPT && oldest.hasNext()) {
      Entry entry = oldest.next();
      if (entry.image != null) {
        Minecraft.getInstance().getTextureManager().release(entry.texture());
      }

      oldest.remove();
    }
  }

  private static TextImage register(Entry entry) {
    NativeImage image = new NativeImage(entry.width, entry.height, false);
    for (int y = 0; y < entry.height; y++) {
      for (int x = 0; x < entry.width; x++) {
        image.setPixel(x, y, entry.pixels[y * entry.width + x]);
      }
    }

    entry.pixels = null;
    Identifier texture = entry.texture();
    Minecraft.getInstance().getTextureManager().register(texture, new AlbumTexture.SmoothTexture("Lyrica words", image));
    return new TextImage(ResourceLocation.create(texture.getNamespace(), texture.getPath()),
        entry.width / PICTURE_PER_TEXT, entry.height / PICTURE_PER_TEXT, entry.above / PICTURE_PER_TEXT);
  }

  private static void make(Entry entry, String text, boolean bold, float mostWidth) {
    try {
      Font font = fontFor(text, bold);
      FontRenderContext context = new FontRenderContext(null, true, true);
      float most = mostWidth * PICTURE_PER_TEXT;
      String words = text;
      TextLayout layout = new TextLayout(words, font, context);
      while (layout.getAdvance() > most && words.length() > 1) {
        words = words.substring(0, words.offsetByCodePoints(words.length() - (words.endsWith(ELLIPSIS) ? 1 : 0), -1))
            .stripTrailing() + ELLIPSIS;
        layout = new TextLayout(words, font, context);
      }

      Rectangle2D ink = layout.getBounds();
      int ascent = (int) Math.ceil(Math.max(layout.getAscent(), -ink.getMinY())) + 1;
      int descent = (int) Math.ceil(Math.max(layout.getDescent(), ink.getMaxY())) + 1;
      int width = Math.max(1, (int) Math.ceil(layout.getAdvance()) + 2);
      int height = Math.max(1, ascent + descent);
      BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = picture.createGraphics();
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setColor(java.awt.Color.WHITE);
      layout.draw(graphics, 1.0F, ascent);
      graphics.dispose();
      int[] pixels = picture.getRGB(0, 0, width, height, null, 0, width);
      entry.width = width;
      entry.height = height;
      // How far above the top of the capitals the picture begins, which is how a card's lines are placed
      entry.above = ascent - CAP_TEXT_PIXELS * PICTURE_PER_TEXT;
      entry.pixels = pixels;
    } catch (RuntimeException | LinkageError failure) {
      Env.LOG.debug("Could not make a picture of words: {}", failure.toString());
    }
  }

  private static Font fontFor(String text, boolean bold) {
    int style = bold ? Font.BOLD : Font.PLAIN;
    float size = EM_TEXT_PIXELS * PICTURE_PER_TEXT;
    for (int i = 0; i < FAMILIES.length; i++) {
      Font font = new Font(FAMILIES[i], style, 1).deriveFont(size);
      if (font.canDisplayUpTo(text) < 0) {
        return font;
      }
    }

    return new Font("Dialog", style, 1).deriveFont(size);
  }

  private static final class Entry {

    final int id;
    volatile int[] pixels;
    int width;
    int height;
    float above;
    TextImage image;

    Entry(int id) {
      this.id = id;
    }

    Identifier texture() {
      return Identifier.fromNamespaceAndPath("lyrica", "words_" + this.id);
    }
  }
}
