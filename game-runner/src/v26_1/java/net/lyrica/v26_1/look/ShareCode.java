package net.lyrica.v26_1.look;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A look as a line of text that friends can pass around: the same parts a saved look keeps (type, colour, solid form,
 * light, motion, karaoke) and its name, and nothing else. Only what differs from the plain look goes in, squeezed and
 * written in URL-safe base64 after {@link #PREFIX}. Reading one never trusts it: the text is held to a size, unknown
 * keys are dropped, and the look is repaired as a file would be.
 */
public final class ShareCode {

  public static final String PREFIX = "LYRICA1-";

  private static final int MOST_CODE_CHARS = 8_192;
  private static final int MOST_JSON_BYTES = 32_768;
  private static final int MOST_NAME_CHARS = 32;
  private static final String NAME = "n";
  private static final String LOOK = "l";
  private static final Gson GSON = new Gson();

  private ShareCode() {
  }

  /** A look read from a code, with the name it was shared under. */
  public record Shared(String name, Look look) {
  }

  /** The code for the look parts of {@code look}, carrying {@code name}. */
  public static String encode(String name, Look look) {
    JsonObject plain = GSON.toJsonTree(lookPart(new Look())).getAsJsonObject();
    JsonObject mine = GSON.toJsonTree(lookPart(look)).getAsJsonObject();
    JsonObject differs = new JsonObject();
    for (Map.Entry<String, JsonElement> entry : mine.entrySet()) {
      if (!entry.getValue().equals(plain.get(entry.getKey()))) {
        differs.add(entry.getKey(), entry.getValue());
      }
    }

    JsonObject root = new JsonObject();
    root.addProperty(NAME, cleanName(name));
    root.add(LOOK, differs);
    byte[] json = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
    try {
      deflater.setInput(json);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[512];
      while (!deflater.finished()) {
        out.write(buffer, 0, deflater.deflate(buffer));
      }

      return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    } finally {
      deflater.end();
    }
  }

  /** The look in a code, or null when it is not one of ours or is broken. Spaces and line breaks are ignored. */
  public static Shared decode(String code) {
    if (code == null) {
      return null;
    }

    String text = code.replaceAll("\\s+", "");
    if (text.length() > MOST_CODE_CHARS || !text.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      return null;
    }

    try {
      byte[] packed = Base64.getUrlDecoder().decode(text.substring(PREFIX.length()));
      String json = inflate(packed);
      if (json == null) {
        return null;
      }

      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject fields = root.has(LOOK) && root.get(LOOK).isJsonObject() ? root.getAsJsonObject(LOOK)
          : new JsonObject();
      // Only the keys a look part has: nothing in a code reaches place, sync, card, sharing or the saved list
      JsonObject plain = GSON.toJsonTree(lookPart(new Look())).getAsJsonObject();
      JsonObject kept = new JsonObject();
      for (Map.Entry<String, JsonElement> entry : fields.entrySet()) {
        if (plain.has(entry.getKey()) && !entry.getValue().isJsonNull()) {
          kept.add(entry.getKey(), entry.getValue());
        }
      }

      Look read = GSON.fromJson(kept, Look.class);
      if (read == null) {
        return null;
      }

      read.repair();
      String name = root.has(NAME) && root.get(NAME).isJsonPrimitive() ? cleanName(root.get(NAME).getAsString()) : "";
      return new Shared(name, lookPart(read));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** A fresh look holding only the shareable parts of {@code look}. */
  private static Look lookPart(Look look) {
    Look part = new Look();
    part.copyLookFrom(look);
    part.saved = null;
    part.songOffsets = null;
    return part;
  }

  private static String inflate(byte[] packed) {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(packed);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      while (!inflater.finished()) {
        int made = inflater.inflate(buffer);
        if (made == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
          return null;
        }

        out.write(buffer, 0, made);
        if (out.size() > MOST_JSON_BYTES) {
          return null;
        }
      }

      return out.toString(StandardCharsets.UTF_8);
    } catch (DataFormatException e) {
      return null;
    } finally {
      inflater.end();
    }
  }

  private static String cleanName(String name) {
    if (name == null) {
      return "";
    }

    StringBuilder clean = new StringBuilder();
    String text = name.strip();
    int kept = 0;
    for (int at = 0; at < text.length() && kept < MOST_NAME_CHARS; ) {
      int point = text.codePointAt(at);
      at += Character.charCount(point);
      if (point != 0xA7 && !Character.isISOControl(point)) {
        clean.appendCodePoint(point);
        kept++;
      }
    }
    return clean.toString().strip();
  }
}
