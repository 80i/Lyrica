package net.lyrica.v26_3.stage;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

/**
 * Prepared text per letter, kept from frame to frame while the letter and the colour stay the same. A solid line would
 * otherwise lay out hundreds of glyphs a frame to draw what it drew a frame ago.
 */
final class LetterPlates {

  static final int FRONT = 0;
  static final int NEAR_SIDE = 1;
  static final int MIDDLE_SIDE = 2;
  static final int FAR_SIDE = 3;
  static final int EDGE = 4;
  static final int FAINT = 5;
  private static final int KINDS = 6;

  private FormattedCharSequence[] sequences = new FormattedCharSequence[32];
  private Font.PreparedText[] texts = new Font.PreparedText[32 * KINDS];
  private int[] colors = new int[32 * KINDS];

  Font.PreparedText get(Font font, int letter, FormattedCharSequence sequence, int kind, int argb) {
    if (letter >= this.sequences.length) {
      this.grow(letter + 1);
    }

    if (this.sequences[letter] != sequence) {
      this.sequences[letter] = sequence;
      for (int k = 0; k < KINDS; k++) {
        this.texts[letter * KINDS + k] = null;
      }
    }

    int at = letter * KINDS + kind;
    Font.PreparedText known = this.texts[at];
    if (known != null && this.colors[at] == argb) {
      return known;
    }

    Font.PreparedText made = font.prepareText(sequence, 0.0F, 0.0F, argb, false, false, 0);
    this.texts[at] = made;
    this.colors[at] = argb;
    return made;
  }

  /** Lets go of everything, so that glyphs baked before a resource reload do not outlive the line they were for. */
  void clear() {
    for (int i = 0; i < this.sequences.length; i++) {
      this.sequences[i] = null;
    }

    for (int i = 0; i < this.texts.length; i++) {
      this.texts[i] = null;
    }
  }

  private void grow(int letters) {
    int size = Math.max(letters, this.sequences.length * 2);
    FormattedCharSequence[] moreSequences = new FormattedCharSequence[size];
    Font.PreparedText[] moreTexts = new Font.PreparedText[size * KINDS];
    int[] moreColors = new int[size * KINDS];
    System.arraycopy(this.sequences, 0, moreSequences, 0, this.sequences.length);
    System.arraycopy(this.texts, 0, moreTexts, 0, this.texts.length);
    System.arraycopy(this.colors, 0, moreColors, 0, this.colors.length);
    this.sequences = moreSequences;
    this.texts = moreTexts;
    this.colors = moreColors;
  }
}
