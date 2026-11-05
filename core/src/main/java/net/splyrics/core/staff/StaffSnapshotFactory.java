package net.splyrics.core.staff;

import net.labymod.api.client.entity.player.Player;
import net.labymod.api.laby3d.renderer.snapshot.Extras;
import net.labymod.api.laby3d.renderer.snapshot.LabySnapshotFactory;
import net.labymod.api.service.annotation.AutoService;

/** Takes the player's account id into the snapshot the staff tags are drawn from. */
@AutoService(LabySnapshotFactory.class)
public class StaffSnapshotFactory extends LabySnapshotFactory<Player, StaffSnapshot> {

  public StaffSnapshotFactory() {
    super(StaffSnapshot.KEY);
  }

  @Override
  protected StaffSnapshot create(Player player, Extras extras) {
    return new StaffSnapshot(player.getUniqueId(), extras);
  }
}
