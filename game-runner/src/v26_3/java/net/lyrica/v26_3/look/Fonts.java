package net.lyrica.v26_3.look;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** The fonts by the names the look saves. Styles are made once, because they are asked for every frame. */
public final class Fonts {

  private static final String DEFAULT = "DEFAULT";
  private static final float MESH_EM = 10.0F;

  /**
   * A font with no bold file of its own, {@code boldFile} null, gets the game's own thickened drawing in text and a
   * grown outline in the world. The sizes are the ones in the font's json, which even out the fonts' capital heights.
   */
  private record Face(String name, String file, float size, String boldFile, float boldSize) {

    Face(String name, String file, float size) {
      this(name, file, size, null, size);
    }
  }

  private static final Face[] FACES = {
      new Face("BEBAS", "bebas", 10.0F),
      new Face("MONTSERRAT", "montserrat", 10.0F, "montserrat_bold", 10.0F),
      new Face("POPPINS", "poppins", 10.0F, "poppins_bold", 9.9F),
      new Face("RIGHTEOUS", "righteous", 10.0F),
      new Face("PLAYFAIR", "playfair", 9.9F),
      new Face("LOBSTER", "lobster", 9.4F),
      new Face("PACIFICO", "pacifico", 8.3F),
      new Face("PRESS_START", "press_start", 7.0F),
      new Face("COMIC_RELIEF", "comic_relief", 9.2F, "comic_relief_bold", 9.2F),
      new Face("EAGLE_LAKE", "eagle_lake", 9.4F),
      new Face("COMFORTAA", "comfortaa", 9.0F),
      new Face("JETBRAINS_MONO", "jetbrains_mono", 9.6F)
  };

  public static final String[] NAMES = new String[FACES.length + 1];

  private static final Map<String, Style> REGULAR = new HashMap<>();
  private static final Map<String, Style> BOLD = new HashMap<>();
  private static final Map<String, Face> BY_NAME = new HashMap<>();

  static {
    for (int i = 0; i < FACES.length; i++) {
      Face face = FACES[i];
      NAMES[i] = face.name;
      BY_NAME.put(face.name, face);
      Style regular = fontStyle(face.file);
      REGULAR.put(face.name, regular);
      BOLD.put(face.name, face.boldFile == null ? regular.withBold(true) : fontStyle(face.boldFile));
    }

    NAMES[FACES.length] = DEFAULT;
    REGULAR.put(DEFAULT, Style.EMPTY);
    BOLD.put(DEFAULT, Style.EMPTY.withBold(true));
  }

  private Fonts() {
  }

  /** The style of a font; an unknown name, say from an old file, gets the game's own font. */
  public static Style style(String fontName, boolean bold) {
    Style style = (bold ? BOLD : REGULAR).get(fontName);
    if (style != null) {
      return style;
    }

    return bold ? BOLD.get(DEFAULT) : REGULAR.get(DEFAULT);
  }

  /** The TrueType file of a font without its extension, or null for the game's own font and unknown names. */
  public static String file(String fontName) {
    Face face = BY_NAME.get(fontName);
    return face == null ? null : face.file;
  }

  /** Whether the font has a bold file of its own, rather than something that has to be thickened. */
  public static boolean hasBoldFile(String fontName) {
    Face face = BY_NAME.get(fontName);
    return face != null && face.boldFile != null;
  }

  /** Text pixels per outline pixel, as the outlines are 10 pixels to the em and the fonts have their own sizes. */
  public static float meshScale(String fontName, boolean bold) {
    Face face = BY_NAME.get(fontName);
    if (face == null) {
      return 1.0F;
    }

    return (bold && face.boldFile != null ? face.boldSize : face.size) / MESH_EM;
  }

  private static Style fontStyle(String file) {
    return Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("lyrical", file)));
  }
}
