package net.lyrica.v26_1_2.words;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.lyrica.v26_1_2.Env;
import net.lyrica.v26_1_2.look.AtomicFile;
import net.lyrica.v26_1_2.song.NowPlaying;
import net.lyrica.v26_1_2.words.LrclibSearch.Match;

/**
 * The lyrics of the current song: from the disk cache, else from lrclib.net, all on a worker thread.
 */
public final class LyricsLibrary {

  public enum State {
    NONE, SEARCHING, READY, NOT_FOUND, OFFLINE
  }

  private static final LyricsLibrary INSTANCE = new LyricsLibrary();
  private static final long NOT_FOUND_VALID_MS = TimeUnit.DAYS.toMillis(1L);
  private static final long FIRST_RETRY_MS = 20_000L;
  private static final long LAST_RETRY_MS = TimeUnit.MINUTES.toMillis(10L);
  // A player can take its time to say how long a new song is, and the length decides between versions of a song
  private static final long LENGTH_WAIT_MS = 400L;
  private static final long LONGEST_LENGTH_WAIT_MS = 6_000L;
  // A record can gain word timings after it was kept here, so now and then a song played again looks.
  private static final long LOOK_FOR_WORDS_AFTER_MS = TimeUnit.DAYS.toMillis(30L);

  private final ScheduledExecutorService workers = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "Lyrica lyrics");
    thread.setDaemon(true);
    return thread;
  });
  private final AtomicLong loadToken = new AtomicLong();

  private volatile NowPlaying song;
  private volatile String songKey;
  private volatile TimedLyrics current;
  private volatile State state = State.NONE;

  private LyricsLibrary() {
  }

  public static LyricsLibrary get() {
    return INSTANCE;
  }

  /** Tell it what plays; cheap to call often. Null means nothing plays. */
  public void onSong(NowPlaying now) {
    this.song = now;
    String key = now == null ? null : now.key();
    if (key == null ? this.songKey == null : key.equals(this.songKey)) {
      return;
    }

    this.songKey = key;
    this.current = null;
    long token = this.loadToken.incrementAndGet();
    if (now == null) {
      this.state = State.NONE;
      return;
    }

    this.state = State.SEARCHING;
    long askedAt = System.currentTimeMillis();
    this.workers.execute(() -> this.load(token, key, askedAt, FIRST_RETRY_MS));
  }

  /** The current song's lyrics, or null when there are none. */
  public TimedLyrics current() {
    return this.current;
  }

  public State state() {
    return this.state;
  }

  /** For the development test: puts these lyrics on the song it has just set, whatever a lookup would find. */
  public void force(TimedLyrics lyrics) {
    this.loadToken.incrementAndGet();
    this.current = lyrics;
    this.state = State.READY;
  }

  /** The word timings if they read, and the plain synced lyrics in every other case, so that no song gets worse. */
  private static TimedLyrics lyricsOf(Match match, long durationMs) {
    if (match.wordTimed()) {
      TimedLyrics words = LyricsfileReader.parse(match.lyricsfile(), durationMs);
      if (words.wordTimed()) {
        return words;
      }

      Env.LOG.info("Could not read the word timings of lyrics {}", match.id());
    }

    return LrcReader.parse(match.lrc(), durationMs);
  }

  private void load(long token, String key, long askedAt, long retryMs) {
    NowPlaying now = this.song;
    if (token != this.loadToken.get() || now == null) {
      return;
    }

    try {
      Match cached = readCache(key);
      if (cached != null) {
        this.publish(token, now, cached);
        this.lookForWords(key, cached);
        return;
      }

      if (isMarkedNotFound(key)) {
        this.finishEmpty(token);
        return;
      }

      if (now.durationMs() <= 0L && System.currentTimeMillis() - askedAt < LONGEST_LENGTH_WAIT_MS) {
        this.workers.schedule(() -> this.load(token, key, askedAt, retryMs), LENGTH_WAIT_MS, TimeUnit.MILLISECONDS);
        return;
      }

      Match found = LrclibSearch.best(now.artist(), now.title(), now.album(), now.durationMs());
      if (found == null) {
        markNotFound(key);
        this.finishEmpty(token);
        return;
      }

      this.remember(key, found);
      this.publish(token, now, found);
    } catch (IOException e) {
      Env.LOG.debug("Lyrics lookup failed: {}", e.toString());
      if (token == this.loadToken.get()) {
        this.state = State.OFFLINE;
        long nextRetry = Math.min(LAST_RETRY_MS, retryMs * 2L);
        long wait = e instanceof LrclibSearch.RateLimited limited ? Math.max(retryMs, limited.waitMs()) : retryMs;
        this.workers.schedule(() -> this.load(token, key, askedAt, nextRetry), wait, TimeUnit.MILLISECONDS);
      }
    } catch (RuntimeException e) {
      Env.LOG.warn("Lyrics lookup failed", e);
      this.finishEmpty(token);
    }
  }

  /**
   * Looks whether the record kept for this song has word timings by now, and keeps them for the next time it plays:
   * changing lyrics in the middle of a song would only pull the words out from under the player. It stays with the same
   * record, as this may be one the player chose. Whether that worked or not, it asks again a month later at the soonest.
   */
  private void lookForWords(String key, Match cached) {
    Path file = cacheFile(key, ".json");
    try {
      long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
      if (cached.wordTimed() || cached.id() < 0L || age < LOOK_FOR_WORDS_AFTER_MS) {
        return;
      }

      Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
      Match fresh = LrclibSearch.byId(cached.id());
      if (fresh != null && fresh.wordTimed() && !LyricsfileReader.parse(fresh.lyricsfile(), 0L).isEmpty()) {
        this.remember(key, fresh);
      }
    } catch (IOException | RuntimeException e) {
      Env.LOG.debug("Could not look for word timings: {}", e.toString());
    }
  }

  private void publish(long token, NowPlaying now, Match match) {
    TimedLyrics lyrics = lyricsOf(match, now.durationMs());
    if (token != this.loadToken.get()) {
      return;
    }

    if (lyrics.isEmpty()) {
      this.state = State.NOT_FOUND;
      return;
    }

    this.current = lyrics;
    this.state = State.READY;
  }

  private void finishEmpty(long token) {
    if (token == this.loadToken.get()) {
      this.state = State.NOT_FOUND;
    }
  }

  private void remember(String key, Match match) {
    JsonObject json = new JsonObject();
    json.addProperty("id", match.id());
    json.addProperty("title", match.title());
    json.addProperty("artist", match.artist());
    json.addProperty("album", match.album());
    json.addProperty("durationMs", match.durationMs());
    json.addProperty("lrc", match.lrc());
    if (match.lyricsfile() != null) {
      json.addProperty("lyricsfile", match.lyricsfile());
    }

    try {
      AtomicFile.write(cacheFile(key, ".json"), json.toString());
      Files.deleteIfExists(cacheFile(key, ".none"));
    } catch (IOException e) {
      Env.LOG.warn("Could not save lyrics: {}", e.toString());
    }
  }

  private static Match readCache(String key) {
    Path file = cacheFile(key, ".json");
    if (!Files.isRegularFile(file)) {
      return null;
    }

    try {
      JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
      return new Match(
          json.get("id").getAsLong(),
          json.get("title").getAsString(),
          json.get("artist").getAsString(),
          json.get("album").getAsString(),
          json.get("durationMs").getAsLong(),
          json.get("lrc").getAsString(),
          json.has("lyricsfile") ? json.get("lyricsfile").getAsString() : null);
    } catch (IOException | RuntimeException e) {
      Env.LOG.info("Ignoring a bad lyrics cache file: {}", e.toString());
      return null;
    }
  }

  private static boolean isMarkedNotFound(String key) {
    Path file = cacheFile(key, ".none");
    try {
      return Files.isRegularFile(file)
          && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < NOT_FOUND_VALID_MS;
    } catch (IOException e) {
      return false;
    }
  }

  private static void markNotFound(String key) {
    try {
      AtomicFile.write(cacheFile(key, ".none"), key);
    } catch (IOException e) {
      Env.LOG.debug("Could not save the not-found marker: {}", e.toString());
    }
  }

  private static Path cacheFile(String key, String extension) {
    return Env.dir().resolve("cache").resolve(hash(key) + extension);
  }

  private static String hash(String key) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(40);
      for (int i = 0; i < 20; i++) {
        hex.append(Character.forDigit((digest[i] >> 4) & 15, 16)).append(Character.forDigit(digest[i] & 15, 16));
      }

      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
