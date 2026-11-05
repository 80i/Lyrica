package net.splyrics.core.share;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.labymod.api.Laby;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.network.server.ServerJoinEvent;
import net.labymod.api.event.client.network.server.SubServerSwitchEvent;
import net.labymod.api.event.labymod.labyconnect.session.LabyConnectBroadcastEvent;
import net.labymod.api.labyconnect.LabyConnect;
import net.labymod.api.labyconnect.LabyConnectSession;
import net.splyrics.api.LyricsBridge;

/**
 * Song sharing between players of this addon, over LabyConnect: what one chose to share reaches those who chose to see
 * it, and shows on the card over that player's head. It sends the song's name, where it is and whether it plays, and a
 * cover so small that it fits in the message. Nothing goes out unless the player switched it on. What comes in is
 * somebody else's words, so it is checked piece by piece, kept short, and thrown away when its sender goes quiet.
 */
public final class SongShare {

  private static final String KEY = "splyrics_song";
  private static final int VERSION = 1;

  // Sending. Often enough that somebody who just arrived soon sees it, and rarely enough to be nobody's burden.
  private static final long LEAST_BETWEEN_MS = 2_000L;
  private static final long AGAIN_AFTER_MS = 45_000L;
  private static final long MOVED_MS = 1_500L;

  // Receiving
  private static final long QUIET_FOR_MS = 150_000L;
  private static final long LEAST_FROM_ONE_MS = 400L;
  private static final int MOST_PLAYERS = 512;
  private static final int MOST_TEXT = 96;
  private static final long MOST_LENGTH_MS = 86_400_000L;
  private static final int COLOUR_CODE = 0xA7;

  private final Map<UUID, Shared> others = new ConcurrentHashMap<>();

  private String sentKey;
  private long sentAtMs;
  private long sentPositionMs;
  private boolean sentPlaying;
  private byte[] sentCover;
  private boolean sentAnything;
  private boolean sendSoon;

  /** About once a second: sends what has changed. */
  public void tick(LyricsBridge bridge) {
    long now = System.currentTimeMillis();
    this.forgetQuiet(now);
    LabyConnect connect = Laby.labyAPI().labyConnect();
    LabyConnectSession session = connect == null ? null : connect.getSession();
    if (session == null || !connect.isAuthenticated()) {
      // Whatever went out before the connection dropped is forgotten over there, so it all goes again when it is back
      this.sentKey = null;
      this.sentAnything = false;
      return;
    }

    LyricsBridge.LocalSong song = bridge.songToShare();
    if (song == null) {
      if (this.sentAnything) {
        JsonObject off = new JsonObject();
        off.addProperty("v", VERSION);
        off.addProperty("off", true);
        session.sendBroadcastPayload(KEY, off);
        this.sentAnything = false;
        this.sentKey = null;
        this.sentAtMs = now;
      }

      return;
    }

    if (now - this.sentAtMs < LEAST_BETWEEN_MS) {
      return;
    }

    byte[] cover = bridge.coverToShare();
    String key = song.artist() + "\t" + song.title();
    long position = song.positionAt(now);
    long expected = this.sentPositionMs + (this.sentPlaying ? now - this.sentAtMs : 0L);
    boolean changed = this.sendSoon || !key.equals(this.sentKey) || song.playing() != this.sentPlaying
        || cover != this.sentCover || Math.abs(position - expected) > MOVED_MS || now - this.sentAtMs >= AGAIN_AFTER_MS;
    if (!changed) {
      return;
    }

    JsonObject message = new JsonObject();
    message.addProperty("v", VERSION);
    message.addProperty("t", song.title());
    message.addProperty("a", song.artist());
    message.addProperty("p", position);
    message.addProperty("d", song.durationMs());
    message.addProperty("s", song.playing());
    if (cover != null) {
      message.addProperty("c", Base64.getEncoder().encodeToString(cover));
    }

    session.sendBroadcastPayload(KEY, message);
    this.sendSoon = false;
    this.sentAnything = true;
    this.sentKey = key;
    this.sentAtMs = now;
    this.sentPositionMs = position;
    this.sentPlaying = song.playing();
    this.sentCover = cover;
  }

  /** Somebody arriving on the same server has not heard anything yet. */
  @Subscribe
  public void onServerJoin(ServerJoinEvent event) {
    this.sendSoon = true;
  }

  @Subscribe
  public void onSubServerSwitch(SubServerSwitchEvent event) {
    this.sendSoon = true;
  }

  @Subscribe
  public void onBroadcast(LabyConnectBroadcastEvent event) {
    if (event.action() != LabyConnectBroadcastEvent.Action.RECEIVE || !KEY.equals(event.getKey())) {
      return;
    }

    UUID sender = event.getSender();
    if (sender == null || sender.equals(Laby.labyAPI().getUniqueId())) {
      return;
    }

    long now = System.currentTimeMillis();
    Shared known = this.others.get(sender);
    if (known != null && now - known.heardAtMs < LEAST_FROM_ONE_MS) {
      return;
    }

    Shared read = read(event.getPayload(), now, known);
    if (read == null) {
      this.others.remove(sender);
    } else if (known != null || this.others.size() < MOST_PLAYERS) {
      this.others.put(sender, read);
    }
  }

  /** The song over this player's head right now, or null: one's own, or what they share. On the render thread. */
  public LyricsBridge.LocalSong cardSong(UUID player, LyricsBridge bridge) {
    return player.equals(Laby.labyAPI().getUniqueId()) ? bridge.ownCardSong() : this.songOf(player, bridge);
  }

  /** What this player shares right now, or null. On the render thread, which is where a cover can become a texture. */
  public LyricsBridge.LocalSong songOf(UUID player, LyricsBridge bridge) {
    if (!bridge.showSharedSongs()) {
      return null;
    }

    Shared shared = this.others.get(player);
    if (shared == null) {
      return null;
    }

    LyricsBridge.LocalSong.Cover cover = shared.thumb == null || !bridge.showSharedCovers() ? null
        : bridge.sharedCover(player, shared.thumb);
    if (shared.shown == null || shared.shown.cover() != cover) {
      shared.shown = new LyricsBridge.LocalSong(shared.title, shared.artist, shared.positionMs, shared.durationMs,
          shared.heardAtMs, shared.playing, cover);
    }

    return shared.shown;
  }

  /** The cover this player sends, or null; the same array until it changes. */
  public byte[] thumbOf(UUID player) {
    Shared shared = this.others.get(player);
    return shared == null ? null : shared.thumb;
  }

  private void forgetQuiet(long now) {
    Iterator<Shared> all = this.others.values().iterator();
    while (all.hasNext()) {
      Shared shared = all.next();
      boolean songOver = shared.playing && shared.durationMs > 0L
          && now - shared.heardAtMs > shared.durationMs - shared.positionMs + AGAIN_AFTER_MS;
      if (now - shared.heardAtMs > QUIET_FOR_MS || songOver) {
        all.remove();
      }
    }
  }

  /** Null when the message says the song is off, or is nothing that can be read. */
  private static Shared read(JsonElement payload, long now, Shared before) {
    if (payload == null || !payload.isJsonObject()) {
      return null;
    }

    JsonObject json = payload.getAsJsonObject();
    if (flag(json, "off")) {
      return null;
    }

    String title = text(json, "t");
    String artist = text(json, "a");
    if (title.isEmpty() && artist.isEmpty()) {
      return null;
    }

    long duration = Math.max(0L, Math.min(MOST_LENGTH_MS, number(json, "d")));
    long position = Math.max(0L, Math.min(duration > 0L ? duration : MOST_LENGTH_MS, number(json, "p")));
    byte[] thumb = thumb(json, before);
    return new Shared(title, artist, position, duration, flag(json, "s"), thumb, now);
  }

  /** The same cover stays the same object, so that it does not become a new texture with every message. */
  private static byte[] thumb(JsonObject json, Shared before) {
    JsonElement element = json.get("c");
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      return null;
    }

    String text = element.getAsString();
    if (text.length() > LyricsBridge.COVER_THUMB_BYTES * 2) {
      return null;
    }

    byte[] read;
    try {
      read = Base64.getDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      return null;
    }

    if (read.length != LyricsBridge.COVER_THUMB_BYTES) {
      return null;
    }

    return before != null && Arrays.equals(read, before.thumb) ? before.thumb : read;
  }

  private static boolean flag(JsonObject json, String key) {
    JsonElement element = json.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
        && element.getAsBoolean();
  }

  private static long number(JsonObject json, String key) {
    JsonElement element = json.get(key);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      return 0L;
    }

    double value = element.getAsDouble();
    return Double.isFinite(value) ? (long) value : 0L;
  }

  /** Words as they may stand on a card: no control or format characters, no colour codes, and only so long. */
  private static String text(JsonObject json, String key) {
    JsonElement element = json.get(key);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      return "";
    }

    String raw = element.getAsString();
    StringBuilder clean = new StringBuilder(Math.min(raw.length(), MOST_TEXT));
    int i = 0;
    while (i < raw.length() && clean.length() < MOST_TEXT) {
      int point = raw.codePointAt(i);
      i += Character.charCount(point);
      if (point == COLOUR_CODE) {
        i = i < raw.length() ? raw.offsetByCodePoints(i, 1) : i;
        continue;
      }

      int type = Character.getType(point);
      if (type != Character.CONTROL && type != Character.FORMAT && type != Character.SURROGATE
          && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR) {
        clean.appendCodePoint(point);
      }
    }

    return clean.toString().strip();
  }

  private static final class Shared {

    final String title;
    final String artist;
    final long positionMs;
    final long durationMs;
    final boolean playing;
    final byte[] thumb;
    final long heardAtMs;
    volatile LyricsBridge.LocalSong shown;

    Shared(String title, String artist, long positionMs, long durationMs, boolean playing, byte[] thumb, long heardAtMs) {
      this.title = title;
      this.artist = artist;
      this.positionMs = positionMs;
      this.durationMs = durationMs;
      this.playing = playing;
      this.thumb = thumb;
      this.heardAtMs = heardAtMs;
    }
  }
}
