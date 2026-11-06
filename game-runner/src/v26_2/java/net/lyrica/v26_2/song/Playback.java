package net.lyrica.v26_2.song;

/** What the rest of the addon reads about the music. */
public final class Playback {

  private static final Playback INSTANCE = new Playback();

  private final MediaReader reader = new MediaReader();
  private volatile boolean stopped;
  private volatile NowPlaying forced;

  private Playback() {
  }

  public static Playback get() {
    return INSTANCE;
  }

  /** The current song, or null when nothing plays. A paused song is still current, with {@code playing} false. */
  public NowPlaying current() {
    NowPlaying forced = this.forced;
    return forced != null ? forced : this.reader.current();
  }

  /** For the development test: this plays, whatever the PC says, until it is given null. */
  public void force(NowPlaying song) {
    this.forced = song;
  }

  public boolean supported() {
    return MediaReader.supported();
  }

  public void start() {
    this.stopped = false;
    this.reader.start();
  }

  public void stop() {
    this.stopped = true;
    this.reader.stop();
  }

  /** Keeps the reader alive; starts it if nobody has yet. */
  public void tick() {
    if (this.stopped) {
      return;
    }

    this.reader.start();
    this.reader.keepAlive();
  }
}
