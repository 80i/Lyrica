package net.lyrica.v26_1_1.look;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.lyrica.v26_1_1.Env;

/** The one {@link Look}, kept in {@code look.json}. Change its fields directly, then call {@link #save()}. */
public final class LookStore {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static volatile Look look;
  private static volatile boolean savesHeld;

  private LookStore() {
  }

  public static Look get() {
    Look known = look;
    if (known == null) {
      synchronized (LookStore.class) {
        known = look;
        if (known == null) {
          known = load();
          look = known;
        }
      }
    }

    return known;
  }

  /** Saves soon on a worker thread; safe to call from the render thread and on every change. */
  public static void save() {
    if (savesHeld) {
      return;
    }

    AtomicFile.later(file(), GSON.toJson(get()));
  }

  /** For the development test: it changes the look freely and must leave the player's file as it was. */
  public static void holdSaves() {
    savesHeld = true;
  }

  /** Writes a waiting save now, for when the addon stops. */
  public static void flush() {
    AtomicFile.flush();
  }

  private static Path file() {
    return Env.dir().resolve("look.json");
  }

  private static Look load() {
    Path file = file();
    if (!Files.isRegularFile(file)) {
      return fresh();
    }

    try {
      Look loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Look.class);
      if (loaded != null) {
        loaded.repair();
        return loaded;
      }
    } catch (IOException | RuntimeException e) {
      Env.LOG.warn("Could not read look.json, starting from the default look: {}", e.toString());
      keepBadFile(file);
    }

    return fresh();
  }

  private static Look fresh() {
    Look look = new Look();
    look.repair();
    return look;
  }

  private static void keepBadFile(Path file) {
    try {
      Files.move(file, file.resolveSibling("look.json.bad"), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Env.LOG.debug("Could not set the bad look file aside: {}", e.toString());
    }
  }
}
