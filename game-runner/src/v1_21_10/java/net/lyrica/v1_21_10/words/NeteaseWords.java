package net.lyrica.v1_21_10.words;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word timings from NetEase Cloud Music, for the many songs lrclib only times by line. Its public API needs no account,
 * but it is not documented and may change or go away, so every failure here only means falling back to lrclib.
 *
 * <p>Its word-timed lyrics ("yrc") have a line per row, {@code [start,length]} and then each word as
 * {@code (start,length,0)text}, all in milliseconds from the start of the song. Rows that start with {@code {} are
 * credits, not lyrics.
 */
public final class NeteaseWords {

  private static final String SEARCH = "https://music.163.com/api/search/get?type=1&limit=10&s=";
  private static final String LYRICS = "https://music.163.com/api/song/lyric/v1?lv=0&yv=0&tv=-1&id=";
  private static final int MOST_BYTES = 1 << 20;
  // A different cut or a live take is a different song, and its words would run at other times.
  private static final long SAME_LENGTH_MS = 3_000L;
  private static final double SAME_NAME = 0.8;
  private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)]");
  private static final Pattern WORD = Pattern.compile("\\((\\d+),(\\d+),-?\\d+\\)([^(]*)");

  private NeteaseWords() {
  }

  /**
   * The word-timed lyrics of the song, as NetEase writes them, or null when it has none or no song matches.
   *
   * @throws IOException when NetEase cannot be reached, which is not the same as having nothing
   */
  public static String find(String artist, String title, long durationMs) throws IOException {
    long id = songId(artist, title, durationMs);
    if (id < 0L) {
      return null;
    }

    JsonObject reply = json(LYRICS + id);
    JsonObject yrc = reply == null || !reply.has("yrc") || !reply.get("yrc").isJsonObject()
        ? null : reply.getAsJsonObject("yrc");
    String text = yrc == null || !yrc.has("lyric") ? "" : yrc.get("lyric").getAsString();
    return text.isBlank() ? null : text;
  }

  private static long songId(String artist, String title, long durationMs) throws IOException {
    String cleanTitle = clean(title);
    String cleanArtist = clean(artist);
    if (cleanTitle.isEmpty()) {
      return -1L;
    }

    String query = URLEncoder.encode(cleanTitle + " " + cleanArtist, StandardCharsets.UTF_8);
    JsonObject reply = json(SEARCH + query);
    if (reply == null || !reply.has("result") || !reply.get("result").isJsonObject()) {
      return -1L;
    }

    JsonElement songs = reply.getAsJsonObject("result").get("songs");
    if (songs == null || !songs.isJsonArray()) {
      return -1L;
    }

    for (JsonElement element : songs.getAsJsonArray()) {
      JsonObject song = element.getAsJsonObject();
      long length = song.has("duration") ? song.get("duration").getAsLong() : 0L;
      if (durationMs > 0L && length > 0L && Math.abs(length - durationMs) > SAME_LENGTH_MS) {
        continue;
      }

      if (sameName(cleanTitle, clean(song.get("name").getAsString())) && byArtist(song, cleanArtist)) {
        return song.get("id").getAsLong();
      }
    }

    return -1L;
  }

  private static boolean byArtist(JsonObject song, String artist) {
    if (artist.isEmpty()) {
      return true;
    }

    JsonElement artists = song.get("artists");
    if (artists == null || !artists.isJsonArray()) {
      return false;
    }

    for (JsonElement one : (JsonArray) artists) {
      String name = clean(one.getAsJsonObject().get("name").getAsString());
      if (!name.isEmpty() && (artist.contains(name) || sameName(artist, name))) {
        return true;
      }
    }

    return false;
  }

  private static boolean sameName(String a, String b) {
    return a.equals(b) || NameMatch.closeness(a, b) >= SAME_NAME;
  }

  /** Lower case, and without what players add to a title, such as "(feat. ...)" or "- Remastered 2011". */
  private static String clean(String text) {
    if (text == null) {
      return "";
    }

    String lower = text.toLowerCase(Locale.ROOT);
    lower = lower.replaceAll("\\s*[(\\[](feat|ft|with|remaster|from)[^)\\]]*[)\\]]", "");
    lower = lower.replaceAll("\\s+-\\s+.*remaster.*$", "");
    return lower.strip();
  }

  private static JsonObject json(String url) throws IOException {
    Http.Reply reply = Http.getAtMost(url, MOST_BYTES);
    if (reply.status() != 200) {
      throw new IOException("NetEase answered " + reply.status());
    }

    try {
      JsonElement parsed = JsonParser.parseString(reply.body());
      return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Reads NetEase's word-timed lyrics. Rows it cannot read are left out; nothing readable gives no lines. */
  public static TimedLyrics parse(String yrc, long durationMs) {
    List<Row> rows = new ArrayList<>();
    for (String raw : yrc == null ? new String[0] : yrc.split("\r?\n")) {
      Row row = readRow(raw.strip());
      if (row != null) {
        rows.add(row);
      }
    }

    rows.sort((a, b) -> Long.compare(a.startMs, b.startMs));
    List<TimedLine> lines = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      Row row = rows.get(i);
      long next = i + 1 < rows.size() ? rows.get(i + 1).startMs : Long.MAX_VALUE;
      long sung = Math.max(row.lengthMs, row.parts.lastEnd());
      long end = Math.min(next, row.startMs + sung + TimedLine.LINGER_MS);
      if (durationMs > row.startMs) {
        end = Math.min(end, durationMs);
      }

      lines.add(new TimedLine(row.startMs, Math.max(end, row.startMs + 1L), row.text, row.parts));
    }

    return new TimedLyrics(lines);
  }

  private static Row readRow(String raw) {
    Matcher head = LINE.matcher(raw);
    if (!head.find()) {
      return null;
    }

    long start = Long.parseLong(head.group(1));
    long length = Long.parseLong(head.group(2));
    Matcher word = WORD.matcher(raw);
    word.region(head.end(), raw.length());
    List<long[]> times = new ArrayList<>();
    List<Integer> chars = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    while (word.find()) {
      String piece = word.group(3);
      long from = Long.parseLong(word.group(1)) - start;
      times.add(new long[] {Math.max(0L, from), Math.max(0L, from + Long.parseLong(word.group(2)))});
      chars.add(piece.replaceAll("\\s", "").length());
      text.append(piece);
    }

    String line = text.toString().strip();
    if (line.isEmpty() || LrcReader.isOnlyMusicMarks(line)) {
      return null;
    }

    long[] starts = new long[times.size()];
    long[] ends = new long[times.size()];
    int[] counts = new int[times.size()];
    for (int i = 0; i < times.size(); i++) {
      starts[i] = times.get(i)[0];
      ends[i] = times.get(i)[1];
      counts[i] = chars.get(i);
    }

    SungParts parts = SungParts.of(starts, ends, counts);
    return parts == null ? null : new Row(start, length, line, parts);
  }

  private record Row(long startMs, long lengthMs, String text, SungParts parts) {
  }
}
