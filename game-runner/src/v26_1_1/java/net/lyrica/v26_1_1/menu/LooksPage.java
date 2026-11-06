package net.lyrica.v26_1_1.menu;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.labymod.api.util.I18n;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.lyrica.v26_1_1.look.Look;
import net.lyrica.v26_1_1.look.Presets;
import net.lyrica.v26_1_1.look.SavedLook;
import net.lyrica.v26_1_1.menu.controls.Action;
import net.lyrica.v26_1_1.menu.controls.Field;
import net.lyrica.v26_1_1.menu.controls.Ink;
import net.lyrica.v26_1_1.menu.controls.Note;

/** Ready-made looks in a moving row, and below it the player's own saved ones. */
final class LooksPage extends Page {

  private static final int LEAST_ROW = 34;
  private static final int LEAST_COLUMNS = 40;
  private static final int WORN_CHECK_TICKS = 10;

  private final Gson gson = new Gson();
  private final List<String> presetPrints = new ArrayList<>();
  private final PresetTiles tiles = new PresetTiles();
  private final Field saveField = new Field("lyrica.menu.looks.name", "lyrica.menu.looks.save", this::saveAs, false);
  private final String wearWord = I18n.translate("lyrica.menu.looks.put_on");
  private final String removeWord = I18n.translate("lyrica.menu.looks.remove");
  private final String savedHint = I18n.translate("lyrica.menu.looks.saved_row.desc");

  private String wornId;
  private int ticks;
  private boolean draggingRow;

  LooksPage() {
    super("looks");
    List<Presets.Preset> all = Presets.all();
    for (int i = 0; i < all.size(); i++) {
      this.presetPrints.add(this.gson.toJson(all.get(i).look()));
    }

    this.findWorn();
    if (this.wornId != null) {
      base = Presets.byId(this.wornId).look();
    }
  }

  @Override
  protected void build() {
    this.columns.group("lyrica.menu.looks.mine").add(this.saveField);
    Columns.Group saved = this.columns.group("lyrica.menu.looks.saved");
    if (this.look.saved.isEmpty()) {
      saved.add(new Note("lyrica.menu.looks.none_saved"));
    }

    for (int i = 0; i < this.look.saved.size(); i++) {
      SavedLook entry = this.look.saved.get(i);
      saved.add(Action.row(entry.name, this.savedHint, this.wearWord, () -> {
        entry.apply(this.look);
        base = entry.look;
      }).second(this.removeWord, () -> {
        this.look.saved.remove(entry);
        this.rebuild();
      }));
    }
  }

  private boolean saveAs(String name) {
    if (name.isEmpty()) {
      return false;
    }

    for (int i = this.look.saved.size() - 1; i >= 0; i--) {
      if (this.look.saved.get(i).name.equalsIgnoreCase(name)) {
        this.look.saved.remove(i);
      }
    }

    this.look.saved.add(new SavedLook(name, this.look));
    this.rebuild();
    return true;
  }

  private int rowHeight() {
    return Math.max(LEAST_ROW, Math.min(this.height - LEAST_COLUMNS, this.height / 2));
  }

  @Override
  protected void layOutColumns() {
    int row = this.rowHeight();
    this.tiles.layout(this.left, this.top, this.width, row);
    this.columns.layout(this.font, this.left, this.top + row + 2, this.width, this.height - row - 2);
  }

  @Override
  void render(GuiGraphicsExtractor g, int mx, int my, float frameMs) {
    super.render(g, mx, my, frameMs);
    this.tiles.render(g, this.font, mx, my, frameMs, this.wornId);
    Ink.hair(g, this.left, this.top + this.rowHeight() + 1, this.width, Ink.HAIR);
  }

  @Override
  String hint(double mx, double my) {
    if (this.tiles.contains(mx, my)) {
      Presets.Preset preset = this.tiles.presetAt(mx, my);
      return preset == null ? null : preset.name() + ": " + preset.description();
    }

    return super.hint(mx, my);
  }

  @Override
  boolean press(double mx, double my, int button) {
    if (!this.tiles.contains(mx, my)) {
      return super.press(mx, my, button);
    }

    this.blur();
    if (this.tiles.pressBar(mx, my)) {
      this.draggingRow = true;
      return true;
    }

    Presets.Preset preset = this.tiles.presetAt(mx, my);
    if (preset != null) {
      preset.apply(this.look);
      base = preset.look();
      this.wornId = preset.id();
      Ink.click(1.4F);
    }

    return true;
  }

  @Override
  void drag(double mx, double my) {
    if (this.draggingRow) {
      this.tiles.drag(mx);
    } else {
      super.drag(mx, my);
    }
  }

  @Override
  void release(double mx, double my) {
    this.draggingRow = false;
    this.tiles.release();
    super.release(mx, my);
  }

  @Override
  boolean wheel(double mx, double my, double amount) {
    if (this.tiles.contains(mx, my)) {
      this.tiles.wheel(amount);
      return true;
    }

    return super.wheel(mx, my, amount);
  }

  @Override
  void tick() {
    if (++this.ticks % WORN_CHECK_TICKS == 0) {
      this.findWorn();
    }
  }

  /** The mark follows the look itself: move one fader and it is no longer that preset. */
  private void findWorn() {
    Look part = new Look();
    part.copyLookFrom(this.look);
    String print = this.gson.toJson(part);
    this.wornId = null;
    for (int i = 0; i < this.presetPrints.size(); i++) {
      if (this.presetPrints.get(i).equals(print)) {
        this.wornId = Presets.all().get(i).id();
        return;
      }
    }
  }
}
