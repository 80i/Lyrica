package net.lyrica.v26_2.look;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.regex.Pattern;
import net.lyrica.api.LyricsBridge.CardLook;

/**
 * What a role or a player sets of a staff card's look, each part null when it is not set: a player's goes over the
 * role's, and the role's over the plain look, so an old list without styles reads as today's card. Everything read from
 * a file is checked here: what does not fit is left out, as if it had not been written.
 */
public record CardStyle(Float opacity, Integer panel, Boolean rim, Float corners, Float size, Boolean cover,
    Boolean wave, Boolean time, String place) {

  public static final CardStyle NONE = new CardStyle(null, null, null, null, null, null, null, null, null);

  /** Where a role stands: under the name, on the song card, or nowhere for anybody. */
  public static final String UNDER = "UNDER";
  public static final String CARD = "CARD";
  public static final String HIDDEN = "HIDDEN";

  private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
  private static final float SAME = 0.001F;
  private static final float STORED_STEPS = 1000.0F;
  private static final int MOST_RGB = 0xFFFFFF;
  private static final int COLOUR_CODE = 0xA7;

  public static CardStyle read(JsonElement element) {
    if (element == null || !element.isJsonObject()) {
      return NONE;
    }

    JsonObject json = element.getAsJsonObject();
    return new CardStyle(number(json, "opacity", 0.0F, 1.0F), color(json.get("panelColor")), flag(json, "rim"),
        number(json, "corners", 0.0F, CardLook.MOST_CORNERS),
        number(json, "size", CardLook.LEAST_SIZE, CardLook.MOST_SIZE), flag(json, "cover"), flag(json, "waveform"),
        flag(json, "time"), place(json));
  }

  /** Older lists say onCard, and those written while names had settings of their own keep that among them. */
  private static String place(JsonObject json) {
    JsonElement element = json.get("place");
    if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      String place = element.getAsString();
      if (UNDER.equals(place) || CARD.equals(place) || HIDDEN.equals(place)) {
        return place;
      }
    }

    Boolean flat = flag(json, "onCard");
    JsonElement old = json.get("name");
    Boolean onCard = flat != null || old == null || !old.isJsonObject() ? flat : flag(old.getAsJsonObject(), "onCard");
    return onCard == null ? null : onCard ? CARD : UNDER;
  }

  /** A colour as {@code #RRGGBB}, {@code RRGGBB} or a plain number; anything else is null. */
  public static Integer color(JsonElement element) {
    if (element == null || !element.isJsonPrimitive()) {
      return null;
    }

    JsonPrimitive value = element.getAsJsonPrimitive();
    if (value.isNumber()) {
      double number = value.getAsDouble();
      return number >= 0.0 && number <= MOST_RGB && number == Math.rint(number) ? Integer.valueOf((int) number) : null;
    }

    if (!value.isString()) {
      return null;
    }

    String text = value.getAsString().strip();
    return HEX.matcher(text).matches() ? Integer.valueOf(Palette.parseHex(text, 0)) : null;
  }

  /** Text as it may stand on a card: no control or format characters, no colour codes, and only so long. */
  public static String text(String raw, int most) {
    StringBuilder clean = new StringBuilder();
    int i = 0;
    while (i < raw.length()) {
      int point = raw.codePointAt(i);
      i += Character.charCount(point);
      if (point == COLOUR_CODE) {
        // With its letter, or the letter would be left standing in the words
        i = i < raw.length() ? raw.offsetByCodePoints(i, 1) : i;
        continue;
      }

      int type = Character.getType(point);
      boolean unwanted = type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
          || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
      if (!unwanted) {
        if (clean.length() + Character.charCount(point) > most) {
          break;
        }

        clean.appendCodePoint(point);
      }
    }

    return clean.toString().strip();
  }

  private static Float number(JsonObject json, String key, float least, float most) {
    JsonElement element = json.get(key);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      return null;
    }

    double value = element.getAsDouble();
    if (!Double.isFinite(value)) {
      return null;
    }

    return (float) Math.max(least, Math.min(most, value));
  }

  private static Boolean flag(JsonObject json, String key) {
    JsonElement element = json.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
        ? Boolean.valueOf(element.getAsBoolean()) : null;
  }

  public boolean empty() {
    return this.equals(NONE);
  }

  /** The parts that are set, in the file's words. */
  public JsonObject json() {
    JsonObject json = new JsonObject();
    add(json, "opacity", this.opacity);
    if (this.panel != null) {
      json.addProperty("panelColor", Palette.hex(this.panel));
    }

    if (this.rim != null) {
      json.addProperty("rim", this.rim);
    }

    add(json, "corners", this.corners);
    add(json, "size", this.size);
    if (this.cover != null) {
      json.addProperty("cover", this.cover);
    }

    if (this.wave != null) {
      json.addProperty("waveform", this.wave);
    }

    if (this.time != null) {
      json.addProperty("time", this.time);
    }

    if (this.place != null) {
      json.addProperty("place", this.place);
    }

    return json;
  }

  private static void add(JsonObject json, String key, Float value) {
    if (value != null) {
      json.addProperty(key, Math.round(value * STORED_STEPS) / STORED_STEPS);
    }
  }

  /** The look this makes of what lies under it. */
  public CardLook over(CardLook under) {
    return new CardLook(this.opacity != null ? this.opacity : under.panelOpacity(),
        this.panel != null ? this.panel : under.panelRgb(),
        this.rim != null ? this.rim : under.rim(),
        this.corners != null ? this.corners : under.corners(),
        this.size != null ? this.size : under.size(),
        this.cover != null ? this.cover : under.cover(),
        this.wave != null ? this.wave : under.wave(),
        this.time != null ? this.time : under.time());
  }

  /** Forgets what says the same as what lies under it, so that only real differences are kept and exported. */
  public CardStyle apartFrom(CardLook under) {
    return new CardStyle(differs(this.opacity, under.panelOpacity()),
        this.panel != null && this.panel != under.panelRgb() ? this.panel : null,
        this.rim != null && this.rim != under.rim() ? this.rim : null,
        differs(this.corners, under.corners()),
        differs(this.size, under.size()),
        this.cover != null && this.cover != under.cover() ? this.cover : null,
        this.wave != null && this.wave != under.wave() ? this.wave : null,
        this.time != null && this.time != under.time() ? this.time : null, this.place);
  }

  private static Float differs(Float value, float under) {
    return value != null && Math.abs(value - under) > SAME ? value : null;
  }

  /** Forgets where the role stands if that says the same as what lies under it. */
  public CardStyle placeApartFrom(String under) {
    return under.equals(this.place)
        ? new CardStyle(this.opacity, this.panel, this.rim, this.corners, this.size, this.cover, this.wave, this.time, null)
        : this;
  }

  public CardStyle withPlace(String value) {
    return new CardStyle(this.opacity, this.panel, this.rim, this.corners, this.size, this.cover, this.wave, this.time,
        value);
  }

  public CardStyle withOpacity(float value) {
    return new CardStyle(value, this.panel, this.rim, this.corners, this.size, this.cover, this.wave, this.time, this.place);
  }

  public CardStyle withPanel(int value) {
    return new CardStyle(this.opacity, value & MOST_RGB, this.rim, this.corners, this.size, this.cover, this.wave,
        this.time, this.place);
  }

  public CardStyle withRim(boolean value) {
    return new CardStyle(this.opacity, this.panel, value, this.corners, this.size, this.cover, this.wave, this.time, this.place);
  }

  public CardStyle withCorners(float value) {
    return new CardStyle(this.opacity, this.panel, this.rim, value, this.size, this.cover, this.wave, this.time, this.place);
  }

  public CardStyle withSize(float value) {
    return new CardStyle(this.opacity, this.panel, this.rim, this.corners, value, this.cover, this.wave, this.time, this.place);
  }

  public CardStyle withCover(boolean value) {
    return new CardStyle(this.opacity, this.panel, this.rim, this.corners, this.size, value, this.wave, this.time, this.place);
  }

  public CardStyle withWave(boolean value) {
    return new CardStyle(this.opacity, this.panel, this.rim, this.corners, this.size, this.cover, value, this.time, this.place);
  }

  public CardStyle withTime(boolean value) {
    return new CardStyle(this.opacity, this.panel, this.rim, this.corners, this.size, this.cover, this.wave, value, this.place);
  }
}
