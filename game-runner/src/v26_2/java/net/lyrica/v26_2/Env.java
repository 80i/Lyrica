package net.lyrica.v26_2;

import java.nio.file.Path;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What every package needs: the log, the addon's folder, the game language and the menu key from the core. */
public final class Env {

  public static final Logger LOG = LoggerFactory.getLogger("Lyrica");
  public static final String VERSION = "1.0.0";

  private static volatile Path dir;
  private static volatile Supplier<String> keyName;
  private static volatile IntPredicate keyTest;
  private static volatile IntConsumer keyBind;

  private Env() {
  }

  /** The addon's folder inside the game directory. It may not exist yet; writers create what they need. */
  public static Path dir() {
    Path known = dir;
    if (known == null) {
      Minecraft client = Minecraft.getInstance();
      if (client == null) {
        return Path.of("lyrica");
      }

      known = client.gameDirectory.toPath().resolve("lyrica");
      dir = known;
    }

    return known;
  }

  /** The game's language code, like "en_us". */
  public static String language() {
    Minecraft client = Minecraft.getInstance();
    if (client == null || client.getLanguageManager() == null) {
      return "en_us";
    }

    String selected = client.getLanguageManager().getSelected();
    return selected == null || selected.isEmpty() ? "en_us" : selected;
  }

  public static void menuKey(Supplier<String> name, IntPredicate isKey, IntConsumer bind) {
    keyName = name;
    keyTest = isKey;
    keyBind = bind;
  }

  public static String menuKeyName() {
    Supplier<String> name = keyName;
    return name == null ? "" : name.get();
  }

  public static boolean isMenuKey(int keyCode) {
    IntPredicate test = keyTest;
    return test != null && test.test(keyCode);
  }

  public static void bindMenuKey(int keyCode) {
    IntConsumer bind = keyBind;
    if (bind != null) {
      bind.accept(keyCode);
    }
  }
}
