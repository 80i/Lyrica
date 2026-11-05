package net.splyrics.core.staff;

import java.util.UUID;
import net.labymod.api.laby3d.renderer.snapshot.AbstractLabySnapshot;
import net.labymod.api.laby3d.renderer.snapshot.ExtraKey;
import net.labymod.api.laby3d.renderer.snapshot.Extras;

/** What the staff tags need from a player when they are drawn: the account id. */
public final class StaffSnapshot extends AbstractLabySnapshot {

  public static final ExtraKey<StaffSnapshot> KEY = ExtraKey.of("splyrics_staff", StaffSnapshot.class);

  private final UUID uuid;

  public StaffSnapshot(UUID uuid, Extras extras) {
    super(extras);
    this.uuid = uuid;
  }

  public UUID uuid() {
    return this.uuid;
  }
}
