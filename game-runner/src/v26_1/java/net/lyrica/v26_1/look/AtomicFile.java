package net.lyrica.v26_1.look;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.lyrica.v26_1.Env;

/** Writes text files so that a crash never leaves half a file, now or a moment later on a worker thread. */
public final class AtomicFile {

  private static final long LATER_MS = 400L;
  private static final Object LOCK = new Object();
  private static final Map<Path, String> WAITING = new LinkedHashMap<>();
  private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "Lyrica files");
    thread.setDaemon(true);
    return thread;
  });
  private static boolean scheduled;

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(AtomicFile::flush, "Lyrica files on exit"));
  }

  private AtomicFile() {
  }

  public static void write(Path file, String text) throws IOException {
    Path folder = file.toAbsolutePath().getParent();
    if (folder != null) {
      Files.createDirectories(folder);
    }

    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(temporary, text, StandardCharsets.UTF_8);
    try {
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Writes soon, on a worker thread. Calls in quick succession, like a dragged fader's, write once. */
  public static void later(Path file, String text) {
    synchronized (LOCK) {
      WAITING.put(file, text);
      if (!scheduled) {
        scheduled = true;
        WORKER.schedule(AtomicFile::flush, LATER_MS, TimeUnit.MILLISECONDS);
      }
    }
  }

  /** Writes everything still waiting, before returning. */
  public static void flush() {
    synchronized (LOCK) {
      scheduled = false;
      Iterator<Map.Entry<Path, String>> waiting = WAITING.entrySet().iterator();
      while (waiting.hasNext()) {
        Map.Entry<Path, String> entry = waiting.next();
        waiting.remove();
        try {
          write(entry.getKey(), entry.getValue());
        } catch (IOException e) {
          Env.LOG.warn("Could not write {}: {}", entry.getKey(), e.toString());
        }
      }
    }
  }
}
