package net.lyrica.v26_1.song;

import com.sun.jna.Pointer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.lyrica.v26_1.Env;
import net.lyrica.v26_1.look.LookStore;

/**
 * Reads Windows' System Media Transport Controls in the game's own process, through {@link WinMedia}, on a worker
 * thread that looks four times a second.
 *
 * <p>Some players, Spotify among them, publish a new song's title at once and its timeline only much later. Until then
 * the timeline still describes the last song, so the reader counts from the moment the title changed and reports the
 * length as unknown, so the card can show the time but not yet fill its wave, and goes back to the player's own numbers
 * as soon as it publishes new ones.
 */
public final class MediaReader {

  private static final long SILENCE_LIMIT_MS = 20_000L;
  private static final long STEADY_WITHIN_MS = 400L;
  private static final long MAX_BACKOFF_MS = 60_000L;
  private static final long TICK_MS = 250L;
  private static final long NOTHING_FOR_MS = 2_000L;
  private static final int FAILED_TICKS_LIMIT = 40;
  private static final long YEAR_2000_MS = 946_684_800_000L;

  // A song's cover is read only for a moment after it starts, and a song can go and come back
  private String artKey;
  private byte[] artKept;

  private volatile Thread thread;
  private volatile long lastTickMs;
  private volatile NowPlaying current;
  private long nothingSinceMs;

  // What the reader knows of the song between looks
  private String lastStamp;
  private long stampAtMs;
  private long songAtMs;
  private boolean staleTimeline;
  private long staleEnd;
  private String lastSong = "";
  private byte[] lastArt;
  private int artChecks;

  /** Only Windows has these controls; elsewhere nothing ever starts. */
  public static boolean supported() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
  }

  public NowPlaying current() {
    return this.current;
  }

  public synchronized void start() {
    if (this.thread != null || !supported()) {
      return;
    }

    this.begin();
  }

  private void begin() {
    Thread worker = new Thread(this::run, "Lyrica media");
    worker.setDaemon(true);
    this.thread = worker;
    this.lastTickMs = System.currentTimeMillis();
    worker.start();
  }

  public synchronized void stop() {
    Thread worker = this.thread;
    this.thread = null;
    this.current = null;
    if (worker != null) {
      worker.interrupt();
    }
  }

  /** Whether the player chose to have nothing read but Spotify. */
  private static boolean onlySpotifyWanted() {
    return "SPOTIFY".equals(LookStore.get().mediaSources);
  }

  private static boolean isSpotify(String app) {
    return app != null && app.toLowerCase(Locale.ROOT).contains("spotify");
  }

  /** Leaves a worker stuck in a Windows call behind and starts a new one; the old one ends once the call returns. */
  public synchronized void keepAlive() {
    if (onlySpotifyWanted() && this.current != null && !isSpotify(this.current.sourceApp())) {
      this.current = null;
    }

    Thread worker = this.thread;
    if (worker != null && System.currentTimeMillis() - this.lastTickMs > SILENCE_LIMIT_MS) {
      Env.LOG.warn("The media reader went silent, starting it again");
      worker.interrupt();
      this.begin();
    }
  }

  private void run() {
    Thread self = Thread.currentThread();
    int failures = 0;
    while (this.thread == self) {
      boolean read = false;
      try (WinMedia media = WinMedia.open()) {
        read = this.watch(media, self);
      } catch (IOException | RuntimeException e) {
        if (this.thread == self) {
          Env.LOG.warn("The media reader failed: {}", e.toString());
        }
      } catch (LinkageError e) {
        Env.LOG.warn("The media controls cannot be reached: {}", e.toString());
        return;
      }

      if (this.thread != self) {
        return;
      }

      failures = read ? 0 : failures + 1;
      if (!read) {
        this.current = null;
      }

      try {
        Thread.sleep(Math.min(MAX_BACKOFF_MS, 2_000L << Math.min(failures, 6)));
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  /** Looks until stopped or until Windows keeps failing; says whether any look worked. */
  private boolean watch(WinMedia media, Thread self) {
    boolean read = false;
    int failed = 0;
    this.lastSong = "";
    this.lastStamp = null;
    while (this.thread == self) {
      this.lastTickMs = System.currentTimeMillis();
      try {
        this.look(media);
        read = true;
        failed = 0;
      } catch (IOException | RuntimeException e) {
        if (++failed >= FAILED_TICKS_LIMIT) {
          Env.LOG.warn("The media controls keep failing: {}", e.toString());
          return read;
        }
      }

      try {
        Thread.sleep(TICK_MS);
      } catch (InterruptedException e) {
        return read;
      }
    }

    return read;
  }

  private void look(WinMedia media) throws IOException {
    List<Pointer> held = new ArrayList<>();
    try {
      Pointer session = this.pick(media, held);
      if (session == null) {
        this.nothing();
        return;
      }

      Pointer properties = media.properties(session);
      held.add(properties);
      String title = properties == null ? "" : media.title(properties);
      if (title.isEmpty()) {
        this.nothing();
        return;
      }

      this.read(media, session, properties, title);
    } finally {
      for (Pointer one : held) {
        WinMedia.release(one);
      }
    }
  }

  /** The playing session, else the current one; with only Spotify wanted, any other app is passed over first. */
  private Pointer pick(WinMedia media, List<Pointer> held) throws IOException {
    boolean only = onlySpotifyWanted();
    Pointer spotify = null;
    List<Pointer> sessions = media.sessions();
    held.addAll(sessions);
    for (Pointer candidate : sessions) {
      if (only && !isSpotify(media.appId(candidate))) {
        continue;
      }

      if (media.status(candidate) == WinMedia.PLAYING) {
        return candidate;
      }

      if (only && spotify == null) {
        spotify = candidate;
      }
    }

    Pointer session = media.currentSession();
    held.add(session);
    // Another app being the current one, as a browser is once it plays, does not end a paused Spotify
    if (only && (session == null || !isSpotify(media.appId(session)))) {
      return spotify;
    }

    return session;
  }

  private void read(WinMedia media, Pointer session, Pointer properties, String title) throws IOException {
    String artist = media.artist(properties);
    String album = media.album(properties);
    String app = media.appId(session);
    boolean playing = media.status(session) == WinMedia.PLAYING;
    long[] timeline = media.timeline(session);
    long now = System.currentTimeMillis();
    long end = timeline[0] / 10_000L;
    long reported = timeline[1] / 10_000L;
    long position = reported;
    long updated = WinMedia.unixMillis(timeline[2]);
    if (playing && updated >= YEAR_2000_MS) {
      position += Math.max(0L, now - updated);
    }

    if (end > 0L && position > end) {
      position = end;
    }

    String stamp = timeline[2] + "/" + timeline[1] + "/" + timeline[0];
    if (!stamp.equals(this.lastStamp)) {
      this.lastStamp = stamp;
      this.stampAtMs = now;
      // The player repeats itself every few seconds, and the last song's timeline said again is no news
      if (end != this.staleEnd || reported < now - this.songAtMs + 3_000L) {
        this.staleTimeline = false;
      }
    }

    String song = artist + "\t" + title;
    if (!song.equals(this.lastSong)) {
      // A timeline published just before the title belongs to the new song already
      this.staleTimeline = !this.lastSong.isEmpty() && now - this.stampAtMs > 1_000L;
      this.staleEnd = end;
      this.songAtMs = now;
      this.lastSong = song;
      this.lastArt = null;
      this.artChecks = 16;
    }

    if (this.staleTimeline) {
      end = 0L;
      // Never quite nothing, which is how a player without a timeline reads
      position = playing ? Math.max(1L, now - this.songAtMs) : 0L;
    }

    // Players publish the cover a moment after the title, sometimes first showing the old one
    byte[] art = null;
    if (this.artChecks > 0 && (this.artChecks % 2 == 0 || this.lastArt == null)) {
      byte[] cover = media.art(properties);
      if (cover != null && !Arrays.equals(cover, this.lastArt)) {
        this.lastArt = cover;
        art = cover;
      }
    }

    if (this.artChecks > 0) {
      this.artChecks--;
    }

    this.report(app, playing, position, end, title, artist, album, art);
  }

  private void nothing() {
    long now = System.currentTimeMillis();
    // Players drop their session for a moment between songs; a moment of nothing must not end the song
    if (this.nothingSinceMs == 0L) {
      this.nothingSinceMs = now;
    } else if (now - this.nothingSinceMs >= NOTHING_FOR_MS) {
      this.current = null;
    }
  }

  private void report(String app, boolean playing, long position, long duration, String title, String artist,
      String album, byte[] cover) {
    // Also here, for the moment between the choice changing and the next look
    if (onlySpotifyWanted() && !isSpotify(app)) {
      this.current = null;
      return;
    }

    this.nothingSinceMs = 0L;
    long now = System.currentTimeMillis();
    NowPlaying before = this.current;
    NowPlaying reading = new NowPlaying(title, artist, album, duration, position, now, playing, app, cover);
    byte[] art = this.artFor(reading);
    boolean sameSong = before != null && before.key().equals(reading.key());
    if (!sameSong) {
      this.current = new NowPlaying(title, artist, album, duration, position, now, playing, app, art);
      return;
    }

    long running = before.positionNow(0L);
    long steady = Math.abs(position - running) <= STEADY_WITHIN_MS ? running : position;
    this.current = new NowPlaying(title, artist, album, duration, steady, now, playing, app, art);
  }

  /** This reading's cover, or the one this song last sent, which stays the same array so that it reads as unchanged. */
  private byte[] artFor(NowPlaying reading) {
    if (reading.artPng() != null) {
      this.artKey = reading.key();
      this.artKept = reading.artPng();
      return this.artKept;
    }

    return reading.key().equals(this.artKey) ? this.artKept : null;
  }
}
