package net.lyrica.v1_21_10.menu;

import net.labymod.api.Laby;
import net.minecraft.client.Minecraft;
import net.lyrica.api.LyricsBridge.CardLook;
import net.lyrica.v1_21_10.VersionedLyricsBridge;
import net.lyrica.v1_21_10.look.StaffRoster;
import net.lyrica.v1_21_10.menu.controls.Chips;
import net.lyrica.v1_21_10.menu.controls.Fader;
import net.lyrica.v1_21_10.menu.controls.Lamp;
import net.lyrica.v1_21_10.menu.controls.Note;
import net.lyrica.v1_21_10.menu.controls.Swatches;

/** The song over the head: whether it shows, what happens in a pause, who else gets to see it, and how it looks. */
final class HeadPage extends Page {

  private static final String ADDON_ID = "lyrical";
  private static final String[] PAUSED = {"SHOW", "HIDE"};
  private static final String[] PLACES = {"OVER", "UNDER"};
  private static final String[] SOURCES = {"ALL", "SPOTIFY"};
  private static final String[] ACCENT_FROM = {"STATIC", "ALBUM"};

  /** Holds while the game runs, so that coming back to the page finds it as it was left. */
  private static boolean seeItOnMe;

  private final FrontCamera camera = new FrontCamera();
  private boolean onScreen;

  HeadPage() {
    super("head");
  }

  @Override
  protected void build() {
    this.columns.group("lyrical.menu.head.mine")
        .add(new Chips("lyrical.menu.head.sources", SOURCES, () -> this.look.mediaSources,
            value -> this.look.mediaSources = value, () -> base.mediaSources))
        .add(new Lamp("lyrical.menu.head.own", () -> this.look.ownCard, value -> this.look.ownCard = value,
            () -> base.ownCard))
        .add(new Chips("lyrical.menu.head.paused", PAUSED, () -> this.look.pausedCard,
            value -> this.look.pausedCard = value, () -> base.pausedCard))
        .add(new Lamp("lyrical.menu.head.preview", () -> seeItOnMe, this::seeItOnMe, () -> seeItOnMe)
            .when(() -> this.look.ownCard));

    this.columns.group("lyrical.menu.head.sharing")
        .add(new Lamp("lyrical.menu.head.share", () -> this.look.shareSong, value -> this.look.shareSong = value,
            () -> base.shareSong))
        .add(new Lamp("lyrical.menu.head.share_cover", () -> this.look.shareCover,
            value -> this.look.shareCover = value, () -> base.shareCover).when(() -> this.look.shareSong))
        .add(new Lamp("lyrical.menu.head.show_shared", () -> this.look.showShared,
            value -> this.look.showShared = value, () -> base.showShared))
        .add(new Lamp("lyrical.menu.head.show_shared_covers", () -> this.look.showSharedCovers,
            value -> this.look.showSharedCovers = value, () -> base.showSharedCovers)
            .when(() -> this.look.showShared))
        .add(new Note("lyrical.menu.head.sharing_note"));

    this.columns.group("lyrical.menu.head.shows")
        .add(new Chips("lyrical.menu.head.place", PLACES, () -> this.look.headPlace,
            value -> this.look.headPlace = value, () -> base.headPlace))
        .add(new Lamp("lyrical.menu.head.cover", () -> this.look.headCover, value -> this.look.headCover = value,
            () -> base.headCover))
        .add(new Lamp("lyrical.menu.head.wave", () -> this.look.headWave, value -> this.look.headWave = value,
            () -> base.headWave))
        .add(new Lamp("lyrical.menu.head.time", () -> this.look.headTime, value -> this.look.headTime = value,
            () -> base.headTime))
        .add(new Chips("lyrical.menu.head.accent_from", ACCENT_FROM, () -> this.look.headAccentMode,
            value -> this.look.headAccentMode = value, () -> base.headAccentMode))
        .add(new Swatches("lyrical.menu.head.accent", () -> this.look.headAccent,
            value -> this.look.headAccent = value, () -> base.headAccent)
            .when(() -> !"ALBUM".equals(this.look.headAccentMode)));

    Columns.Group roles = this.columns.group("lyrical.menu.head.roles")
        .add(new Lamp("lyrical.menu.staff.badges", () -> this.look.staffBadges,
            value -> this.look.staffBadges = value, () -> base.staffBadges));
    if (StaffRoster.member(Minecraft.getInstance().getUser().getProfileId()) != null) {
      roles.add(new Lamp("lyrical.menu.head.own_role", () -> this.look.showOwnRole,
          value -> this.look.showOwnRole = value, () -> base.showOwnRole).when(() -> this.look.staffBadges));
    }

    this.columns.group("lyrical.menu.head.panel_group")
        .add(new Fader("lyrical.menu.head.opacity", () -> this.look.headOpacity,
            value -> this.look.headOpacity = value, () -> base.headOpacity, 0.0F, 1.0F, 0.05F, Fader.PERCENT))
        .add(new Swatches("lyrical.menu.head.panel_color", () -> this.look.headPanel,
            value -> this.look.headPanel = value, () -> base.headPanel).when(() -> this.look.headOpacity > 0.0F))
        .add(new Lamp("lyrical.menu.head.rim", () -> this.look.headRim, value -> this.look.headRim = value,
            () -> base.headRim).when(() -> this.look.headOpacity > 0.0F))
        .add(new Fader("lyrical.menu.head.corners", () -> this.look.headCorners,
            value -> this.look.headCorners = value, () -> base.headCorners, 0.0F, CardLook.MOST_CORNERS, 1.0F,
            Fader.WHOLE))
        .add(new Fader("lyrical.menu.head.size", () -> this.look.headSize, value -> this.look.headSize = value,
            () -> base.headSize, CardLook.LEAST_SIZE, CardLook.MOST_SIZE, 0.05F, Fader.PERCENT));
  }

  @Override
  void shown() {
    this.onScreen = true;
    this.follow();
  }

  @Override
  void tick() {
    this.follow();
  }

  @Override
  void hidden() {
    this.onScreen = false;
    this.follow();
  }

  private void seeItOnMe(boolean on) {
    seeItOnMe = on;
    this.follow();
  }

  /** Disable can come while the page is open, so it is watched here rather than leaving the camera turned for nothing. */
  private void follow() {
    boolean wanted = this.onScreen && seeItOnMe && this.look.ownCard
        && Laby.labyAPI().addonService().isEnabled(ADDON_ID);
    VersionedLyricsBridge.previewOwnCard(wanted);
    if (wanted) {
      this.camera.turn();
    } else {
      this.camera.back();
    }
  }
}
