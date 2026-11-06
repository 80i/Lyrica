package net.lyrica.v26_1_1.stage;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;

/**
 * What a line of lyrics needs before it can be set as separate letters, in scripts that are not simply one shape per
 * character running left to right.
 */
final class Script {

  private static final ArabicShaping JOINING = new ArabicShaping(ArabicShaping.LETTERS_SHAPE);
  private static final String MIRRORED = "()[]{}<>«»";

  private Script() {
  }

  /**
   * Arabic letters change shape with their neighbours. Unicode has a character for each of those shapes, which installed
   * fonts carry, so joining them here leaves one outline per character like any other script. Other text comes back as
   * the same object.
   */
  static String joined(String text) {
    if (!hasJoiningLetters(text)) {
      return text;
    }

    try {
      return JOINING.shape(text);
    } catch (ArabicShapingException | RuntimeException failure) {
      return text;
    }
  }

  /** Letters of a joining script take no extra letter gap, which would pull their strokes apart. */
  static boolean joins(int codePoint) {
    return codePoint >= 0x0600 && codePoint <= 0x06FF
        || codePoint >= 0x0750 && codePoint <= 0x077F
        || codePoint >= 0x08A0 && codePoint <= 0x08FF
        || codePoint >= 0xFB50 && codePoint <= 0xFDFF
        || codePoint >= 0xFE70 && codePoint <= 0xFEFF;
  }

  /** The bracket that points the other way, for one in a run that reads right to left; the same text otherwise. */
  static String mirrored(String glyph) {
    if (glyph.length() != 1) {
      return glyph;
    }

    int at = MIRRORED.indexOf(glyph.charAt(0));
    return at < 0 ? glyph : String.valueOf(MIRRORED.charAt(at % 2 == 0 ? at + 1 : at - 1));
  }

  private static boolean hasJoiningLetters(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (joins(text.charAt(i))) {
        return true;
      }
    }

    return false;
  }
}
