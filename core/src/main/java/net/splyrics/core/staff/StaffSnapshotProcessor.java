package net.splyrics.core.staff;

import net.labymod.api.client.entity.Entity;
import net.labymod.api.client.entity.player.Player;
import net.labymod.api.client.render.state.entity.EntitySnapshotProcessor;
import net.labymod.api.client.render.state.entity.EntitySnapshotRegistry;
import net.labymod.api.laby3d.renderer.snapshot.ExtrasWriter;
import net.labymod.api.service.annotation.AutoService;

/** Every player's snapshot gets the staff extra (the account id), so the badge can look the player up. */
@AutoService(EntitySnapshotProcessor.class)
public class StaffSnapshotProcessor extends EntitySnapshotProcessor<Player> {

  public StaffSnapshotProcessor(EntitySnapshotRegistry registry) {
    super(registry);
  }

  @Override
  public boolean supports(Entity entity) {
    return entity instanceof Player;
  }

  @Override
  public void process(Player player, float partialTicks, ExtrasWriter writer) {
    this.registry().captureSnapshot(writer, StaffSnapshot.KEY, player);
  }
}
