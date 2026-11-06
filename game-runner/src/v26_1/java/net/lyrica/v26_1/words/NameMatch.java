package net.lyrica.v26_1.words;

import java.text.Normalizer;
import java.util.Locale;

/**
 * How alike two names of a song or an artist are, 0 to 1. Names in the same script are compared letter by letter. The
 * same name can also be written in Arabic and in Latin letters, and there is no one right way to go from one to the
 * other, so those are compared by what most spellings agree on, the consonants.
 */
final class NameMatch {

  private static final int SHORTEST_SURE_SOUND = 3;
  // A short name has too few consonants to tell much, so it counts only if they all agree, and then only just.
  private static final double SHORT_SOUND_CLOSENESS = 0.75;

  private NameMatch() {
  }

  /** How alike they are as written. */
  static double written(String a, String b) {
    String left = normalize(a);
    String right = normalize(b);
    if (left.isEmpty() || right.isEmpty()) {
      return 0.0;
    }

    if (left.equals(right)) {
      return 1.0;
    }

    int shorter = Math.min(left.length(), right.length());
    if (shorter >= 3 && (left.contains(right) || right.contains(left))) {
      return 0.85;
    }

    return 1.0 - editDistance(left, right) / (double) Math.max(left.length(), right.length());
  }

  /** How alike they sound, if one is in Arabic letters and the other in Latin ones; otherwise 0. */
  static double sounded(String a, String b) {
    boolean arabicLeft = hasArabic(a);
    boolean arabicRight = hasArabic(b);
    if (arabicLeft == arabicRight || !hasLatin(arabicLeft ? b : a)) {
      return 0.0;
    }

    String arabic = consonantsOfArabic(arabicLeft ? a : b);
    String latin = consonantsOfLatin(arabicLeft ? b : a);
    if (arabic.isEmpty() || latin.isEmpty()) {
      return 0.0;
    }

    if (Math.min(arabic.length(), latin.length()) < SHORTEST_SURE_SOUND) {
      return arabic.equals(latin) ? SHORT_SOUND_CLOSENESS : 0.0;
    }

    return 1.0 - editDistance(arabic, latin) / (double) Math.max(arabic.length(), latin.length());
  }

  static double closeness(String a, String b) {
    return Math.max(written(a, b), sounded(a, b));
  }

  static boolean hasArabic(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= 'ؠ' && c <= 'ۿ' && Character.isLetter(c)) {
        return true;
      }
    }

    return false;
  }

  private static boolean hasLatin(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
        return true;
      }
    }

    return false;
  }

  /**
   * Letters that are written as vowels or often left out in the other script count for nothing, and letters that Latin
   * spellings do not tell apart count as one. A repeated consonant is one, as Latin doubles them at will.
   */
  static String consonantsOfArabic(String text) {
    String plain = Normalizer.normalize(text, Normalizer.Form.NFKD);
    StringBuilder out = new StringBuilder(plain.length());
    for (int i = 0; i < plain.length(); i++) {
      char sound = switch (plain.charAt(i)) {
        case 'ب', 'پ' -> 'b';
        case 'ت', 'ط', 'ث' -> 't';
        case 'ج', 'چ', 'غ' -> 'j';
        case 'خ', 'ق', 'ك' -> 'k';
        case 'د', 'ض' -> 'd';
        case 'ذ', 'ز', 'ظ' -> 'z';
        case 'ر' -> 'r';
        case 'س', 'ش', 'ص' -> 's';
        case 'ف', 'ڤ' -> 'f';
        case 'ل' -> 'l';
        case 'م' -> 'm';
        case 'ن' -> 'n';
        default -> 0;
      };
      appendSound(out, sound);
    }

    return out.toString();
  }

  static String consonantsOfLatin(String text) {
    String plain = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder(plain.length());
    for (int i = 0; i < plain.length(); i++) {
      char c = plain.charAt(i);
      char after = i + 1 < plain.length() ? plain.charAt(i + 1) : 0;
      if (after == 'h' && (c == 's' || c == 'k' || c == 'g' || c == 't' || c == 'd' || c == 'p' || c == 'c')) {
        appendSound(out, switch (c) {
          case 's', 'c' -> 's';
          case 'k' -> 'k';
          case 'g' -> 'j';
          case 't' -> 't';
          case 'd' -> 'z';
          default -> 'f';
        });
        i++;
        continue;
      }

      appendSound(out, switch (c) {
        case 'b', 'p' -> 'b';
        case 't' -> 't';
        case 'g', 'j' -> 'j';
        case 'k', 'q', 'c' -> 'k';
        case 'd' -> 'd';
        case 'z' -> 'z';
        case 'r' -> 'r';
        case 's' -> 's';
        case 'f', 'v' -> 'f';
        case 'l' -> 'l';
        case 'm' -> 'm';
        case 'n' -> 'n';
        default -> (char) 0;
      });
    }

    return out.toString();
  }

  private static void appendSound(StringBuilder out, char sound) {
    if (sound != 0 && (out.length() == 0 || out.charAt(out.length() - 1) != sound)) {
      out.append(sound);
    }
  }

  private static String normalize(String text) {
    String plain = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder(plain.length());
    boolean space = false;
    for (int i = 0; i < plain.length(); i++) {
      char c = plain.charAt(i);
      if (c == 'ـ') {
        // The stretching line in Arabic only changes how a word looks.
        continue;
      }

      if (Character.isLetterOrDigit(c)) {
        if (space && out.length() > 0) {
          out.append(' ');
        }

        // Spellings that are used for one another all the time.
        out.append(c == 'ة' ? 'ه' : c == 'ى' ? 'ي' : c);
        space = false;
      } else if (Character.getType(c) != Character.NON_SPACING_MARK) {
        space = true;
      }
    }

    return out.toString();
  }

  private static int editDistance(String a, String b) {
    int[] row = new int[b.length() + 1];
    for (int j = 0; j < row.length; j++) {
      row[j] = j;
    }

    for (int i = 1; i <= a.length(); i++) {
      int diagonal = row[0];
      row[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int above = row[j];
        int change = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), diagonal + change);
        diagonal = above;
      }
    }

    return row[b.length()];
  }
}
