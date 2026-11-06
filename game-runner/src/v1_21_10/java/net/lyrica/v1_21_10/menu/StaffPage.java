package net.lyrica.v1_21_10.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.labymod.api.Laby;
import net.labymod.api.util.I18n;
import net.minecraft.client.Minecraft;
import net.lyrica.api.LyricsBridge;
import net.lyrica.api.LyricsBridge.CardLook;
import net.lyrica.v1_21_10.look.CardStyle;
import net.lyrica.v1_21_10.look.Palette;
import net.lyrica.v1_21_10.look.StaffRoster;
import net.lyrica.v1_21_10.menu.controls.Action;
import net.lyrica.v1_21_10.menu.controls.Chips;
import net.lyrica.v1_21_10.menu.controls.Control;
import net.lyrica.v1_21_10.menu.controls.Fader;
import net.lyrica.v1_21_10.menu.controls.Field;
import net.lyrica.v1_21_10.menu.controls.Ink;
import net.lyrica.v1_21_10.menu.controls.Lamp;
import net.lyrica.v1_21_10.menu.controls.Note;
import net.lyrica.v1_21_10.menu.controls.Swatches;

/**
 * The team: who is on it, what each of them is called under their name and how that looks, and who may open this page at
 * all. The whole team stands in one list, each player under their role; choosing a row opens that role or that player in
 * the strips beside it. Nothing here is typed in a code: a role has a short name to find it by and separate words it
 * shows, and either can change without the other. Changes are local until the list is published. "See it on me" shows the
 * title being edited under one's own name, in the world, drawn by its own code against whatever is really behind it.
 */
final class StaffPage extends Page {

  private static final String ADDON_ID = "lyrica";
  private static final float OPACITY_STEP = 0.05F;
  private static final float CORNERS_STEP = 1.0F;
  private static final float SIZE_STEP = 0.05F;
  private static final int TITLE_ROOM = 110;
  private static final long SURE_FOR_MS = 3_000L;
  private static final String[] KINDS = {"WORDS", "CARD"};
  private static final String[] PANEL_ACCESS = {"ROLE", "YES", "NO"};
  private static final String[] PLACES = {CardStyle.UNDER, CardStyle.CARD, CardStyle.HIDDEN};
  private static final String DISCARD = "discard";
  private static final float CARD_OPACITY = 0.95F;

  /** Holds while the game runs, so that coming back to the page finds it as it was left. */
  private static boolean seeItOnMe;

  private final String removeWord = I18n.translate("lyrica.menu.staff.remove");
  private final String sureWord = I18n.translate("lyrica.menu.staff.sure");
  private final String editWord = I18n.translate("lyrica.menu.staff.edit");
  private final String editingWord = I18n.translate("lyrica.menu.staff.editing");
  private final String playerHint = I18n.translate("lyrica.menu.staff.player_row.desc");
  // Kept from one rebuild to the next, because the list is rebuilt as a label is typed or a fader dragged
  private final List<Control> editControls = new ArrayList<>();
  private final List<Control> styleControls = new ArrayList<>();
  private final FrontCamera camera = new FrontCamera();

  private int shownRevision;
  private String statusLine = "";
  private UUID editedPlayer;
  private String editedRole;
  private boolean onScreen;

  /** What a press on Remove has made ready to go, until it is pressed again or the moment passes. */
  private String sureOf;
  private long sureUntilMs;

  private String newRoleName = "";
  private String newRoleWords = "";
  private int newRoleColor = Palette.ACCENT;
  private String newPlayerName = "";
  private String newPlayerRole = "";

  StaffPage() {
    super("staff");
  }

  @Override
  protected boolean outdated() {
    return StaffRoster.revision() != this.shownRevision;
  }

  /** Nothing: what stands here is the team's list and not this player's look, and has its own way back. */
  @Override
  void resetAll() {
  }

  @Override
  void shown() {
    this.onScreen = true;
    this.followPreview();
  }

  @Override
  void tick() {
    this.followPreview();
    if (this.sureOf != null && System.currentTimeMillis() >= this.sureUntilMs) {
      this.sureOf = null;
      this.rebuild();
    }
  }

  @Override
  void hidden() {
    this.onScreen = false;
    this.stopPreview();
  }

  @Override
  protected void build() {
    this.shownRevision = StaffRoster.revision();
    StaffRoster.AddStatus status = StaffRoster.addStatus();
    this.statusLine = status == null ? "" : I18n.translate(status.key(), status.name());

    List<StaffRoster.Role> roles = StaffRoster.roles();
    List<StaffRoster.Member> members = StaffRoster.members();
    this.keepEditing(roles, members);
    this.buildTeam(roles, members);
    this.buildEditor();
    this.buildAdding(roles);
    this.buildRelease();
  }

  /** Each role with its players under it. */
  private void buildTeam(List<StaffRoster.Role> roles, List<StaffRoster.Member> members) {
    Columns.Group team = this.columns.group("lyrica.menu.staff.team");
    for (int i = 0; i < roles.size(); i++) {
      StaffRoster.Role role = roles.get(i);
      team.add(this.roleRow(role));
      for (int j = 0; j < members.size(); j++) {
        StaffRoster.Member member = members.get(j);
        if (member.roleId().equals(role.id())) {
          team.add(this.playerRow(member).indent());
        }
      }
    }

    // On a hand-written list somebody can hold a role that is not on it. They get no title, but can still be taken away
    for (int i = 0; i < members.size(); i++) {
      StaffRoster.Member member = members.get(i);
      if (StaffRoster.role(member.roleId()) == null) {
        team.add(this.playerRow(member));
      }
    }
  }

  private Action roleRow(StaffRoster.Role role) {
    String id = role.id();
    boolean editing = id.equals(this.editedRole);
    String sure = "role:" + id;
    return Action.row(id, I18n.translate("lyrica.menu.staff.role_row.desc", shown(role.label())),
        editing ? this.editingWord : this.editWord, editing ? null : () -> this.edit(null, id))
        .second(sure.equals(this.sureOf) ? this.sureWord : this.removeWord, () -> this.removeOnSecondPress(sure, () -> {
          StaffRoster.removeRole(id);
        })).dot(role.color()).mark(role.panel());
  }

  private Action playerRow(StaffRoster.Member member) {
    UUID id = member.id();
    boolean editing = id.equals(this.editedPlayer);
    String sure = "player:" + id;
    return Action.row(member.name(), this.playerHint, editing ? this.editingWord : this.editWord,
        editing ? null : () -> this.edit(id, null))
        .second(sure.equals(this.sureOf) ? this.sureWord : this.removeWord, () -> this.removeOnSecondPress(sure, () -> {
          StaffRoster.removePlayer(id);
        })).mark(StaffRoster.hasPanel(id));
  }

  /** Nothing goes at one stray click: the first press asks, a second one within a few seconds does it. */
  private void removeOnSecondPress(String what, Runnable remove) {
    if (what.equals(this.sureOf)) {
      this.sureOf = null;
      remove.run();
    } else {
      this.sureOf = what;
      this.sureUntilMs = System.currentTimeMillis() + SURE_FOR_MS;
    }

    this.rebuild();
  }

  /** The strips of what is being edited; with nobody on the list, none. */
  private void buildEditor() {
    if (this.editedPlayer == null && this.editedRole == null) {
      return;
    }

    StaffRoster.Member member = StaffRoster.member(this.editedPlayer);
    StaffRoster.Role role = StaffRoster.role(this.editedRole);
    String title = member != null ? member.name() : role != null ? shown(role.label()) : "";
    Columns.Group editing = this.columns.groupOf(Ink.fit(this.font, title, TITLE_ROOM));
    for (int i = 0; i < this.editControls.size(); i++) {
      editing.add(this.editControls.get(i));
    }

    Columns.Group style = this.columns.group("lyrica.menu.staff.style");
    for (int i = 0; i < this.styleControls.size(); i++) {
      style.add(this.styleControls.get(i));
    }
  }

  /** Adding says what it needs in fields of their own, and the role is chosen from the ones there are. */
  private void buildAdding(List<StaffRoster.Role> roles) {
    String[] ids = new String[roles.size()];
    String[] names = new String[roles.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = roles.get(i).id();
      names[i] = ids[i];
    }

    if (ids.length > 0 && StaffRoster.role(this.newPlayerRole) == null) {
      this.newPlayerRole = ids[ids.length - 1];
    }

    Columns.Group role = this.columns.group("lyrica.menu.staff.add_role_group");
    role.add(this.adding("lyrica.menu.staff.new_role_name", () -> this.newRoleName, text -> this.newRoleName = text));
    role.add(this.adding("lyrica.menu.staff.new_role_words", () -> this.newRoleWords, text -> this.newRoleWords = text));
    role.add(new Swatches("lyrica.menu.staff.role_color", () -> this.newRoleColor, value -> this.newRoleColor = value,
        () -> this.newRoleColor));
    role.add(new Action("lyrica.menu.staff.add_role", this::addRole)
        .when(() -> !this.newRoleName.isBlank()));

    Columns.Group player = this.columns.group("lyrica.menu.staff.add_player_group");
    player.add(this.adding("lyrica.menu.staff.new_player_name", () -> this.newPlayerName,
        text -> this.newPlayerName = text));
    if (ids.length > 0) {
      player.add(new Chips("lyrica.menu.staff.new_player_role", ids, names, () -> this.newPlayerRole,
          value -> this.newPlayerRole = value, () -> this.newPlayerRole));
    }

    player.add(new Action("lyrica.menu.staff.add_player", this::addPlayer)
        .when(() -> !this.newPlayerName.isBlank() && !this.newPlayerRole.isEmpty()));
    player.add(new Note(() -> this.statusLine));
  }

  private Control adding(String key, Supplier<String> get, Consumer<String> set) {
    return Field.live(key, get, set, get, () -> "");
  }

  private void buildRelease() {
    int changes = StaffRoster.localChanges();
    this.columns.group("lyrica.menu.staff.list_group")
        .add(new Lamp("lyrica.menu.staff.badges", () -> this.look.staffBadges,
            value -> this.look.staffBadges = value, () -> base.staffBadges))
        .add(new Note(() -> StaffRoster.localChanges() == 0 ? I18n.translate("lyrica.menu.staff.no_changes")
            : I18n.translate("lyrica.menu.staff.changes", StaffRoster.localChanges())))
        .add(new Action("lyrica.menu.staff.copy", this::copyForRelease)
            .flash(I18n.translate("lyrica.menu.staff.copied")))
        .add(Action.row(I18n.translate("lyrica.menu.staff.discard"),
            I18n.translate("lyrica.menu.staff.discard.desc"),
            DISCARD.equals(this.sureOf) ? this.sureWord : I18n.translate("lyrica.menu.staff.discard_button"),
            changes == 0 ? null : () -> this.removeOnSecondPress(DISCARD, StaffRoster::discardLocal)))
        .add(new Note(() -> I18n.translate("lyrica.menu.staff.published." + StaffRoster.publishedState())))
        .add(new Note("lyrica.menu.staff.publish_note"));
  }

  /** What was being edited, if it is still there; otherwise one's own row, and otherwise the first on the list. */
  private void keepEditing(List<StaffRoster.Role> roles, List<StaffRoster.Member> members) {
    if (StaffRoster.member(this.editedPlayer) != null || StaffRoster.role(this.editedRole) != null) {
      return;
    }

    UUID self = Minecraft.getInstance().getUser().getProfileId();
    if (StaffRoster.member(self) != null) {
      this.edit(self, null);
    } else if (!members.isEmpty()) {
      this.edit(members.get(0).id(), null);
    } else {
      this.edit(null, roles.isEmpty() ? null : roles.get(0).id());
    }
  }

  private void edit(UUID player, String role) {
    if (Objects.equals(player, this.editedPlayer) && Objects.equals(role, this.editedRole)) {
      return;
    }

    this.blur();
    this.editedPlayer = player;
    this.editedRole = role;
    this.editControls.clear();
    this.styleControls.clear();
    if (player != null || role != null) {
      this.editControls.add(new Lamp("lyrica.menu.staff.preview", () -> seeItOnMe, this::seeItOnMe, () -> seeItOnMe)
          .when(() -> this.look.staffBadges));
      if (player != null) {
        this.addPlayerControls(player);
      } else {
        this.addRoleControls(role);
      }

      this.addStyleControls();
    }

    if (this.camera.turned()) {
      this.previewEdited();
    }

    this.rebuild();
  }

  private void addPlayerControls(UUID id) {
    List<StaffRoster.Role> roles = StaffRoster.roles();
    String[] ids = new String[roles.size()];
    String[] names = new String[roles.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = roles.get(i).id();
      names[i] = ids[i];
    }

    if (ids.length > 0) {
      this.editControls.add(new Chips("lyrica.menu.staff.player_role", ids, names, () -> this.roleIdOf(id),
          value -> StaffRoster.setMemberRole(id, value), () -> this.roleIdOf(id)));
    }

    this.editControls.add(new Lamp("lyrica.menu.staff.show_title", () -> this.titleOn(id),
        value -> StaffRoster.setTitleOn(id, value), () -> true));
    this.editControls.add(Field.live("lyrica.menu.staff.title_text", () -> this.titleWords(id),
        text -> StaffRoster.setTitle(id, text), () -> this.plainTitleWords(id), () -> ""));
    this.editControls.add(Field.live("lyrica.menu.staff.own_label", () -> this.ownLabel(id),
        text -> StaffRoster.setOwnLabel(id, text), () -> "", () -> this.roleWordsOf(id)));
    this.editControls.add(new Chips("lyrica.menu.staff.panel_access", PANEL_ACCESS, () -> this.panelAccess(id),
        value -> StaffRoster.setMemberPanel(id, "ROLE".equals(value) ? null : Boolean.valueOf("YES".equals(value))),
        () -> "ROLE"));
  }

  private void addRoleControls(String id) {
    this.editControls.add(new Field("lyrica.menu.staff.role_name", "lyrica.menu.staff.rename",
        text -> this.renameRole(id, text), true).text(id));
    this.editControls.add(Field.live("lyrica.menu.staff.role_label", () -> this.roleWords(id),
        text -> StaffRoster.setRoleLabel(id, text), () -> this.plainRoleWords(id), () -> ""));
    this.editControls.add(new Swatches("lyrica.menu.staff.role_color", () -> this.roleColor(id),
        value -> StaffRoster.setRoleColor(id, value), () -> this.plainRoleColor(id)));
    this.editControls.add(new Lamp("lyrica.menu.staff.role_panel", () -> this.rolePanel(id),
        value -> StaffRoster.setRolePanel(id, value), () -> this.plainRolePanel(id)));
  }

  /** Renamed, the role is another entry, so the strips start again from it. */
  private boolean renameRole(String id, String text) {
    if (!StaffRoster.renameRole(id, text)) {
      return false;
    }

    StaffRoster.Role renamed = null;
    List<StaffRoster.Role> roles = StaffRoster.roles();
    for (int i = 0; i < roles.size(); i++) {
      if (roles.get(i).id().equalsIgnoreCase(text.strip())) {
        renamed = roles.get(i);
      }
    }

    if (renamed != null) {
      this.edit(null, renamed.id());
    }

    return true;
  }

  /** Each shows the look as it stands. Its reset goes back to the role's look for a player, and to plain for a role. */
  private void addStyleControls() {
    this.styleControls.add(new Chips("lyrica.menu.staff.place", PLACES, this::place,
        value -> this.restyle(style -> style.withPlace(value)), this::placeUnder));
    this.styleControls.add(new Chips("lyrica.menu.staff.kind", KINDS, () -> this.edited().panelOpacity() > 0.0F
        ? "CARD" : "WORDS", value -> this.restyle(style -> style.withOpacity("CARD".equals(value) ? CARD_OPACITY : 0.0F)),
        () -> this.under().panelOpacity() > 0.0F ? "CARD" : "WORDS"));
    this.styleControls.add(new Fader("lyrica.menu.staff.opacity", () -> this.edited().panelOpacity(),
        value -> this.restyle(style -> style.withOpacity(value)), () -> this.under().panelOpacity(), 0.0F, 1.0F,
        OPACITY_STEP, Fader.PERCENT));
    this.styleControls.add(new Swatches("lyrica.menu.staff.panel_color", () -> this.edited().panelRgb(),
        value -> this.restyle(style -> style.withPanel(value)), () -> this.under().panelRgb())
        .when(() -> this.edited().panelOpacity() > 0.0F));
    this.styleControls.add(new Lamp("lyrica.menu.staff.rim", () -> this.edited().rim(),
        value -> this.restyle(style -> style.withRim(value)), () -> this.under().rim())
        .when(() -> this.edited().panelOpacity() > 0.0F));
    this.styleControls.add(new Fader("lyrica.menu.staff.corners", () -> this.edited().corners(),
        value -> this.restyle(style -> style.withCorners(value)), () -> this.under().corners(), 0.0F,
        CardLook.MOST_CORNERS, CORNERS_STEP, Fader.WHOLE).when(() -> this.edited().panelOpacity() > 0.0F));
    this.styleControls.add(new Fader("lyrica.menu.staff.size", () -> this.edited().size(),
        value -> this.restyle(style -> style.withSize(value)), () -> this.under().size(), CardLook.LEAST_SIZE,
        CardLook.MOST_SIZE, SIZE_STEP, Fader.PERCENT));
  }

  private String place() {
    return this.editedPlayer != null ? StaffRoster.placeOfMember(this.editedPlayer)
        : StaffRoster.placeOfRole(this.editedRole);
  }

  private String placeUnder() {
    StaffRoster.Member member = StaffRoster.member(this.editedPlayer);
    return member != null ? StaffRoster.placeOfRole(member.roleId()) : CardStyle.UNDER;
  }

  private CardLook edited() {
    return this.editedPlayer != null ? StaffRoster.lookOfMember(this.editedPlayer)
        : StaffRoster.lookOfRole(this.editedRole);
  }

  private CardLook under() {
    StaffRoster.Member member = StaffRoster.member(this.editedPlayer);
    return member != null ? StaffRoster.lookOfRole(member.roleId()) : CardLook.BADGE;
  }

  private void restyle(UnaryOperator<CardStyle> change) {
    if (this.editedPlayer != null) {
      StaffRoster.restyleMember(this.editedPlayer, change);
    } else {
      StaffRoster.restyleRole(this.editedRole, change);
    }
  }

  private String roleIdOf(UUID id) {
    StaffRoster.Member member = StaffRoster.member(id);
    return member == null ? "" : member.roleId();
  }

  private String panelAccess(UUID id) {
    StaffRoster.Member member = StaffRoster.member(id);
    if (member == null || member.panel() == null) {
      return "ROLE";
    }

    return member.panel() ? "YES" : "NO";
  }

  private boolean titleOn(UUID id) {
    StaffRoster.Member member = StaffRoster.member(id);
    return member != null && member.titleOn();
  }

  private String titleWords(UUID id) {
    StaffRoster.Member member = StaffRoster.member(id);
    return member == null || member.title() == null ? "" : shown(member.title());
  }

  private String plainTitleWords(UUID id) {
    StaffRoster.Member published = StaffRoster.publishedMember(id);
    return published == null ? this.titleWords(id) : published.title() == null ? "" : shown(published.title());
  }

  private String ownLabel(UUID id) {
    StaffRoster.Member member = StaffRoster.member(id);
    return member == null || member.label() == null ? "" : shown(member.label());
  }

  private String roleWordsOf(UUID id) {
    StaffRoster.Member member = StaffRoster.member(id);
    return member == null ? "" : this.roleWords(member.roleId());
  }

  private String roleWords(String id) {
    StaffRoster.Role role = StaffRoster.role(id);
    return role == null ? "" : shown(role.label());
  }

  private String plainRoleWords(String id) {
    StaffRoster.Role published = StaffRoster.publishedRole(id);
    return published == null ? this.roleWords(id) : shown(published.label());
  }

  private int roleColor(String id) {
    StaffRoster.Role role = StaffRoster.role(id);
    return role == null ? 0 : role.color();
  }

  private int plainRoleColor(String id) {
    StaffRoster.Role published = StaffRoster.publishedRole(id);
    return published == null ? this.roleColor(id) : published.color();
  }

  private boolean rolePanel(String id) {
    StaffRoster.Role role = StaffRoster.role(id);
    return role != null && role.panel();
  }

  private boolean plainRolePanel(String id) {
    StaffRoster.Role published = StaffRoster.publishedRole(id);
    return published == null ? this.rolePanel(id) : published.panel();
  }

  private void seeItOnMe(boolean on) {
    seeItOnMe = on;
    this.followPreview();
  }

  /**
   * The camera turns only while there is a title to see. With badges off the lamp goes deaf, and Disable can come while
   * the page is open, so both are watched here rather than leaving the camera turned with nothing to turn it back.
   */
  private void followPreview() {
    boolean wanted = this.onScreen && seeItOnMe && this.look.staffBadges
        && Laby.labyAPI().addonService().isEnabled(ADDON_ID);
    if (wanted && !this.camera.turned()) {
      this.camera.turn();
      this.previewEdited();
    } else if (!wanted && this.camera.turned()) {
      this.stopPreview();
    }
  }

  private void previewEdited() {
    if (this.editedPlayer != null) {
      StaffRoster.previewMember(this.editedPlayer);
    } else if (this.editedRole != null) {
      StaffRoster.previewRole(this.editedRole);
    }
  }

  private void stopPreview() {
    StaffRoster.stopPreview();
    this.camera.back();
  }

  private static String shown(String text) {
    return LyricsBridge.StaffRole.shown(text);
  }

  private void copyForRelease() {
    Minecraft.getInstance().keyboardHandler.setClipboard(StaffRoster.exportJson());
  }

  private void addRole() {
    String name = this.newRoleName.strip();
    if (name.isEmpty() || name.indexOf(' ') >= 0) {
      return;
    }

    StaffRoster.addRole(name, this.newRoleWords.isBlank() ? name : this.newRoleWords.strip(), this.newRoleColor);
    this.newRoleName = "";
    this.newRoleWords = "";
    this.rebuild();
  }

  /** The lookup of the name reports in the note under it. */
  private void addPlayer() {
    if (StaffRoster.addPlayer(this.newPlayerName.strip(), this.newPlayerRole)) {
      this.newPlayerName = "";
    }

    this.rebuild();
  }
}
