package net.lyrica.v26_1_1.words;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Finds synced lyrics on lrclib.net. Tidies what the player reports and scores results; only synced results count, and
 * a word-timed one is preferred if it is sure to be this version of the song.
 */
public final class LrclibSearch {

  private static final String API = "https://lrclib.net/api/";
  private static final double SURE_TITLE = 0.7;
  private static final double SURE_SCORE = 0.62;
  private static final long SAME_LENGTH_MS = 2_000L;
  // When the service does not say how long to stay away.
  private static final long USUAL_WAIT_SECONDS = 30L;
  // Its documentation asks for a little more than it says, so that clients told at once do not all come back at once.
  private static final double MOST_EXTRA_WAIT = 0.3;

  private static final Pattern BRACKETS = Pattern.compile("\\([^()]*\\)|\\[[^\\[\\]]*\\]|\\{[^{}]*\\}|\\u3010[^\\u3011]*\\u3011");
  private static final Pattern FEATURING = Pattern.compile("(?i)\\s*\\b(?:feat\\.?|ft\\.?|featuring)(?:\\s.*)?$");
  private static final Pattern VERSION_TAIL = Pattern.compile(
      "(?i)\\s+[-\\u2013\\u2014]\\s+[^-\\u2013\\u2014]*\\b(?:remaster(?:ed)?|radio edit|edit|version|mono|stereo|live|bonus track|deluxe|acoustic|mix)\\b[^-\\u2013\\u2014]*$");
  private static final Pattern PIPE_TAIL = Pattern.compile("\\s+\\|\\s+.*$");
  private static final Pattern NOISE_TAIL = Pattern.compile(
      "(?i)\\s*\\b(?:official\\s+(?:music\\s+|lyrics?\\s+|hd\\s+)?(?:video|audio|visuali[sz]er)|music\\s+video|lyrics?\\s+video|with\\s+lyrics|lyrics|visuali[sz]er|audio|hd|hq|4k)\\s*$");
  private static final Pattern LOOSE_END = Pattern.compile("[\\s\\-\\u2013\\u2014|:,]+$");
  private static final Pattern DASH = Pattern.compile("\\s+[-\\u2013\\u2014]\\s+");
  private static final Pattern TITLE_PARTS = Pattern.compile("\\s*\\|\\s*|\\s+[-\\u2013\\u2014/]\\s+");
  private static final Pattern CHANNEL_TAIL = Pattern.compile("(?i)(?:\\s+-\\s+topic|\\s*vevo|\\s+official(?:\\s+(?:youtube\\s+)?channel)?)$");

  private static volatile long quietUntilMs;

  private LrclibSearch() {
  }

  /** @param lyricsfile the record's Lyricsfile when it holds word timings, otherwise null */
  public record Match(long id, String title, String artist, String album, long durationMs, String lrc,
      String lyricsfile) {

    public boolean wordTimed() {
      return this.lyricsfile != null;
    }
  }

  /** The service asked us to stay away for a while. Nothing goes to it until then. */
  public static final class RateLimited extends IOException {

    private final long waitMs;

    RateLimited(long waitMs) {
      super("Asked to wait " + waitMs + " ms");
      this.waitMs = waitMs;
    }

    public long waitMs() {
      return this.waitMs;
    }
  }

  /** The best sure result for a song, or null when there is none. Throws when the service cannot be reached. */
  public static Match best(String artist, String title, String album, long durationMs) throws IOException {
    List<Form> forms = forms(artist, title);
    Match exactMatch = null;
    Match best = null;
    double bestScore = 0.0;
    Match bestWords = null;
    double bestWordsScore = 0.0;
    for (int i = 0; i < forms.size(); i++) {
      Form form = forms.get(i);
      if (album != null && !album.isBlank() && durationMs > 0L && !form.artist.isEmpty()) {
        Match exact = exact(form, album.strip(), durationMs);
        if (exact != null && isSure(exact, form, durationMs)) {
          if (exact.wordTimed()) {
            return exact;
          }

          exactMatch = exactMatch == null ? exact : exactMatch;
        }
      }

      // With an exact match in hand this only looks for the same song with word timings, so it may fail for free.
      List<Match> found;
      try {
        found = search(form.artist, form.title, null);
        if (found.isEmpty()) {
          found = search(null, null, (form.artist + " " + form.title).strip());
        }
      } catch (IOException failure) {
        if (exactMatch == null) {
          throw failure;
        }

        return exactMatch;
      }

      for (int j = 0; j < found.size(); j++) {
        Match match = found.get(j);
        double score = score(match, form, durationMs);
        if (!isSure(match, form, durationMs)) {
          continue;
        }

        if (score > bestScore) {
          best = match;
          bestScore = score;
        }

        if (match.wordTimed() && score > bestWordsScore && sameLength(match.durationMs(), durationMs)) {
          bestWords = match;
          bestWordsScore = score;
        }
      }
    }

    if (bestWords != null) {
      return bestWords;
    }

    if (exactMatch != null || best != null) {
      return exactMatch != null ? exactMatch : best;
    }

    return bySameArtist(forms, durationMs);
  }

  /**
   * When an Arabic name found nothing, the song may be filed under its name in Latin letters, under the same artist, and
   * then only the artist's name reaches it. Costs one request, and only for songs that would otherwise have nothing.
   */
  private static Match bySameArtist(List<Form> forms, long durationMs) throws IOException {
    Match best = null;
    double bestScore = 0.0;
    for (int i = 0; i < forms.size(); i++) {
      Form form = forms.get(i);
      if (form.artist.isEmpty() || !(NameMatch.hasArabic(form.title) || NameMatch.hasArabic(form.artist))) {
        continue;
      }

      List<Match> found = search(null, null, form.artist);
      for (int j = 0; j < found.size(); j++) {
        Match match = found.get(j);
        double score = score(match, form, durationMs);
        if (score > bestScore && isSure(match, form, durationMs)) {
          best = match;
          bestScore = score;
        }
      }
    }

    return best;
  }

  /** The record as it is now, or null when it is gone. For seeing whether one has gained word timings. */
  public static Match byId(long id) throws IOException {
    Http.Reply reply = request(API + "get/" + id);
    if (reply.status() == 404) {
      return null;
    }

    check(reply);
    try {
      JsonElement root = JsonParser.parseString(reply.body());
      return root.isJsonObject() ? toMatch(root.getAsJsonObject()) : null;
    } catch (RuntimeException e) {
      throw new IOException("Bad reply", e);
    }
  }

  /** Word timings for another version of a song would be out of step all the way through. */
  private static boolean sameLength(long a, long b) {
    return a <= 0L || b <= 0L || Math.abs(a - b) <= SAME_LENGTH_MS;
  }

  private static Http.Reply request(String url) throws IOException {
    long left = quietUntilMs - System.currentTimeMillis();
    if (left > 0L) {
      throw new RateLimited(left);
    }

    Http.Reply reply = Http.get(url);
    if (reply.status() == 429 || reply.status() == 503) {
      long seconds = reply.retryAfterSeconds() > 0L ? reply.retryAfterSeconds() : USUAL_WAIT_SECONDS;
      long wait = Math.round(seconds * 1_000.0 * (1.0 + ThreadLocalRandom.current().nextDouble(MOST_EXTRA_WAIT)));
      quietUntilMs = System.currentTimeMillis() + wait;
      throw new RateLimited(wait);
    }

    return reply;
  }

  private static Match exact(Form form, String album, long durationMs) throws IOException {
    String url = API + "get?artist_name=" + encode(form.artist)
        + "&track_name=" + encode(form.title)
        + "&album_name=" + encode(album)
        + "&duration=" + Math.round(durationMs / 1000.0);
    Http.Reply reply = request(url);
    if (reply.status() == 404) {
      return null;
    }

    check(reply);
    try {
      JsonElement root = JsonParser.parseString(reply.body());
      return root.isJsonObject() ? toMatch(root.getAsJsonObject()) : null;
    } catch (RuntimeException e) {
      throw new IOException("Bad reply", e);
    }
  }

  private static List<Match> search(String artist, String title, String freeText) throws IOException {
    StringBuilder url = new StringBuilder(API).append("search?");
    if (freeText != null) {
      if (freeText.isEmpty()) {
        return List.of();
      }

      url.append("q=").append(encode(freeText));
    } else {
      if (title == null || title.isEmpty()) {
        return List.of();
      }

      url.append("track_name=").append(encode(title));
      if (artist != null && !artist.isEmpty()) {
        url.append("&artist_name=").append(encode(artist));
      }
    }

    Http.Reply reply = request(url.toString());
    if (reply.status() == 404) {
      return List.of();
    }

    check(reply);
    List<Match> result = new ArrayList<>();
    try {
      JsonElement root = JsonParser.parseString(reply.body());
      if (root.isJsonArray()) {
        JsonArray array = root.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
          JsonElement element = array.get(i);
          if (element.isJsonObject()) {
            Match match = toMatch(element.getAsJsonObject());
            if (match != null) {
              result.add(match);
            }
          }
        }
      }
    } catch (RuntimeException e) {
      throw new IOException("Bad reply", e);
    }

    return result;
  }

  private static void check(Http.Reply reply) throws IOException {
    if (reply.status() != 200) {
      throw new IOException("Status " + reply.status());
    }
  }

  private static Match toMatch(JsonObject object) {
    String lrc = text(object, "syncedLyrics");
    boolean words = object.has("hasWordSync") && object.get("hasWordSync").isJsonPrimitive()
        && object.get("hasWordSync").getAsBoolean();
    String lyricsfile = words ? text(object, "lyricsfile") : "";
    if (lrc.isBlank() && lyricsfile.isBlank()) {
      return null;
    }

    long id = object.has("id") && object.get("id").isJsonPrimitive() ? object.get("id").getAsLong() : -1L;
    long duration = object.has("duration") && object.get("duration").isJsonPrimitive()
        ? Math.round(object.get("duration").getAsDouble() * 1000.0)
        : 0L;
    return new Match(id, text(object, "trackName"), text(object, "artistName"), text(object, "albumName"), duration, lrc,
        lyricsfile.isBlank() ? null : lyricsfile);
  }

  private static String text(JsonObject object, String name) {
    JsonElement element = object.get(name);
    return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
  }

  private static String encode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  /** The ways to read what the player reported: video titles often hold "Artist - Title" under a channel's name. */
  private static List<Form> forms(String artist, String title) {
    String cleanArtist = tidyArtist(artist);
    String cleanTitle = tidyTitle(title);
    List<Form> forms = new ArrayList<>(2);
    String[] sides = DASH.split(cleanTitle, 2);
    if (sides.length < 2 || sides[0].isEmpty() || sides[1].isEmpty()) {
      if (!cleanTitle.isEmpty()) {
        forms.add(new Form(cleanArtist, cleanTitle));
      }

      return forms;
    }

    String left = sides[0];
    String right = sides[1];
    if (cleanArtist.isEmpty()) {
      forms.add(new Form(left, right));
    } else if (NameMatch.closeness(cleanArtist, left) >= 0.8) {
      forms.add(new Form(cleanArtist, right));
    } else if (NameMatch.closeness(cleanArtist, right) >= 0.8) {
      forms.add(new Form(cleanArtist, left));
    } else {
      forms.add(new Form(left, right));
      forms.add(new Form(cleanArtist, cleanTitle));
    }

    return forms;
  }

  /** Takes off what players and video sites add to a title, keeping the original when nothing would remain. */
  static String tidyTitle(String title) {
    if (title == null) {
      return "";
    }

    String text = title.strip();
    text = keepIfEmpty(BRACKETS.matcher(text).replaceAll(" ").strip(), text);
    text = keepIfEmpty(FEATURING.matcher(text).replaceFirst("").strip(), text);
    text = keepIfEmpty(VERSION_TAIL.matcher(text).replaceFirst("").strip(), text);
    text = keepIfEmpty(PIPE_TAIL.matcher(text).replaceFirst("").strip(), text);
    for (int i = 0; i < 2; i++) {
      text = keepIfEmpty(NOISE_TAIL.matcher(text).replaceFirst("").strip(), text);
    }

    return collapse(keepIfEmpty(LOOSE_END.matcher(text).replaceFirst("").strip(), text));
  }

  static String tidyArtist(String artist) {
    if (artist == null) {
      return "";
    }

    String text = artist.strip();
    text = keepIfEmpty(CHANNEL_TAIL.matcher(text).replaceFirst("").strip(), text);
    text = keepIfEmpty(FEATURING.matcher(text).replaceFirst("").strip(), text);
    return collapse(text);
  }

  private static String keepIfEmpty(String text, String otherwise) {
    return text.isEmpty() ? otherwise : text;
  }

  private static String collapse(String text) {
    return text.replaceAll("\\s+", " ");
  }

  private static boolean isSure(Match match, Form form, long durationMs) {
    if (titleCloseness(match, form) < SURE_TITLE || score(match, form, durationMs) < SURE_SCORE) {
      return false;
    }

    // Going by how a name sounds is a guess, and the wrong song's words are worse than none. The length has to agree.
    boolean sameLengthKnown = match.durationMs() > 0L && durationMs > 0L
        && Math.abs(match.durationMs() - durationMs) <= SAME_LENGTH_MS;
    return sameLengthKnown || !leansOnSound(match, form);
  }

  private static boolean leansOnSound(Match match, Form form) {
    if (bestTitle(match, form, false) < SURE_TITLE) {
      return true;
    }

    String artist = tidyArtist(match.artist());
    return !form.artist.isEmpty() && NameMatch.sounded(artist, form.artist) > NameMatch.written(artist, form.artist);
  }

  private static double titleCloseness(Match match, Form form) {
    return bestTitle(match, form, true);
  }

  /**
   * A record's title can hold the name twice, as in {@code Sherine - Masha'er | [the same in Arabic]}, so each part of it
   * counts as well as the whole.
   */
  private static double bestTitle(Match match, Form form, boolean bySound) {
    double best = closeness(tidyTitle(match.title()), form.title, bySound);
    String[] parts = TITLE_PARTS.split(BRACKETS.matcher(match.title()).replaceAll(" "));
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i].strip();
      if (!part.isEmpty()) {
        best = Math.max(best, closeness(part, form.title, bySound));
      }
    }

    return best;
  }

  private static double closeness(String a, String b, boolean bySound) {
    return bySound ? NameMatch.closeness(a, b) : NameMatch.written(a, b);
  }

  private static double score(Match match, Form form, long durationMs) {
    double artist = form.artist.isEmpty() ? 0.5 : NameMatch.closeness(tidyArtist(match.artist()), form.artist);
    return titleCloseness(match, form) * 0.5 + artist * 0.3 + durationCloseness(match.durationMs(), durationMs) * 0.2;
  }

  private static double durationCloseness(long a, long b) {
    if (a <= 0L || b <= 0L) {
      return 0.5;
    }

    long apart = Math.abs(a - b);
    return apart <= 2_000L ? 1.0 : Math.max(0.0, 1.0 - (apart - 2_000L) / 30_000.0);
  }

  private record Form(String artist, String title) {

  }
}
