package net.lyrica.v26_3.look;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.lyrica.api.LyricsBridge;
import net.lyrica.api.LyricsBridge.CardLook;
import net.lyrica.v26_3.DevSmoke;
import net.lyrica.v26_3.Env;
import net.lyrica.v26_3.words.Http;

/**
 * Who may get a badge above the name. Identity is the account id, never the name, because a cracked client can use any
 * name. Being on this list is not enough for a card, because the id of another player is what the server says it is: the
 * card also asks {@link AccountProof}, while the Staff page and {@link #exportJson()} read the list as it is.
 *
 * <p>The published list is what everybody sees. It starts as the file in the jar and is replaced by the team's file at
 * {@link #PUBLISHED_URL}, read now and then on a worker thread, with a copy kept on this PC for when that cannot be
 * reached. So a change to the team needs no new jar. Only a whole list that can be read replaces the one in use: a bad
 * download, or one with no roles, leaves everything as it was. The copy on this PC can be edited by whoever owns the PC,
 * so it never gives anybody the Staff page; only the jar and a real download do.
 *
 * <p>The local file adds to the published list and can take away from it on this PC, so somebody with the page can prepare
 * a change with {@link #exportJson()}; it is neither read nor written for anybody the account service has not confirmed,
 * or who has not been given the page. What it says the same as the published list goes from it as soon as the two meet, so
 * that it cannot go on covering later changes. Whatever comes from any of these is checked as it is read, one entry at a
 * time, so that a bad entry costs only itself.
 */
public final class StaffRoster {

  private static final String BUNDLED = "/assets/lyrica/staff.json";
  private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
  private static final String NAME_LOOKUP_URL = "https://api.mojang.com/users/profiles/minecraft/";
  private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
  private static final int DEFAULT_COLOR = 0xFFFFFF;
  private static final long CHECK_RETRY_MS = 60_000L;
  private static final int MOST_TEXT = 64;
  private static final int MOST_ID = 32;
  // Room for an account id, which stands in for the name on a list that gives none
  private static final int MOST_NAME = 36;
  private static final int MOST_ENTRIES = 1024;
  private static final long MOST_FILE_BYTES = 4_000_000L;

  /**
   * Where the team publishes the list, in the format of the file in the jar. HTTPS, and the same address for everybody:
   * nothing from a server or another player can change it. Asking sends no account or token. Empty means no such list.
   */
  private static final String PUBLISHED_URL = "https://raw.githubusercontent.com/80i/lyrica-staff/main/staff.json";
  private static final String PUBLISHED_COPY = "staff_published.json";
  private static final int MOST_PUBLISHED_BYTES = 512_000;
  private static final long PUBLISHED_EVERY_MS = 600_000L;
  private static final long PUBLISHED_RETRY_MS = 120_000L;

  private static final Map<String, Role> publishedRoles = new LinkedHashMap<>();
  private static final Map<UUID, Member> publishedPlayers = new LinkedHashMap<>();
  private static final Map<String, Role> localRoles = new LinkedHashMap<>();
  private static final Map<UUID, Member> localPlayers = new LinkedHashMap<>();
  private static final Set<String> removedRoles = new LinkedHashSet<>();
  private static final Set<UUID> removedPlayers = new LinkedHashSet<>();
  private static final Map<String, Role> roles = new LinkedHashMap<>();
  private static final Map<UUID, Member> players = new LinkedHashMap<>();
  // What the card asks for each frame, worked out once whenever the list changes
  private static final Map<UUID, Role> shownRoles = new LinkedHashMap<>();
  private static final Map<UUID, CardLook> shownLooks = new LinkedHashMap<>();
  private static final Map<String, CardLook> roleLooks = new LinkedHashMap<>();
  private static boolean loaded;

  // Who may open the page, worked out only from the jar's list and from a real download
  private static final Set<UUID> panelHolders = new LinkedHashSet<>();
  private static String publishedText;
  private static boolean publishedLive;
  private static boolean copyTried;
  private static boolean fetching;
  private static long nextFetchMs;
  private static boolean localRead;

  // On the Staff page: the session's own card shows somebody else's while it is being edited
  private static UUID previewOwner;
  private static UUID previewPlayer;
  private static String previewRoleId;
  private static Role previewShownRole;
  private static String previewTitle;
  private static CardLook previewLook;
  private static boolean previewHidden;

  private static UUID sessionId;
  private static boolean verified;
  private static boolean refused;
  private static boolean checking;
  private static long nextCheckMs;

  private static boolean lookingUp;
  private static AddStatus addStatus;
  private static int revision;

  private StaffRoster() {
  }

  /**
   * On the list in the jar the label is a translation key; see {@code LyricsBridge.StaffRole.shown}.
   *
   * @param style what this role sets of its players' titles
   * @param panel whether its players may open the Staff page
   */
  public record Role(String id, String label, int color, CardStyle style, boolean panel) {

  }

  /**
   * The name is for showing only.
   *
   * @param title   optional, null when the player has none; kept while it is switched off
   * @param titleOn whether the card shows the title
   * @param label   the player's own words instead of the role's label, or null
   * @param style   what this player sets over the role's style
   * @param panel   whether this player may open the Staff page whatever their role says; null to go by the role
   */
  public record Member(UUID id, String name, String roleId, String title, boolean titleOn, String label,
      CardStyle style, Boolean panel) {

  }

  /** How the last adding of a player went: a text key and the name it is about. */
  public record AddStatus(String key, String name) {

  }

  private record LocalList(Map<String, Role> roles, Map<UUID, Member> players, Set<String> removedRoles,
      Set<UUID> removedPlayers) {

  }

  /** The role of an account as its card shows it, with the player's own label if they have one, or null. */
  public static synchronized Role roleOf(UUID id) {
    load();
    if (id == null) {
      return null;
    }

    return previewing(id) ? previewShownRole : shownRoles.get(id);
  }

  /** The optional title of an account under its role, or null, also when it is switched off. */
  public static synchronized String titleOf(UUID id) {
    load();
    if (id == null) {
      return null;
    }

    if (previewing(id)) {
      return previewTitle;
    }

    Member member = players.get(id);
    return member == null ? null : shownTitle(member);
  }

  /** How the card of an account looks, or null for anybody not on the list. The same object until the list changes. */
  public static synchronized CardLook cardLookOf(UUID id) {
    load();
    if (id == null) {
      return null;
    }

    return previewing(id) ? previewLook : shownLooks.get(id);
  }

  /** Whether an account's role stands on the song card rather than under the name. */
  public static synchronized boolean onCardOf(UUID id) {
    load();
    if (id == null) {
      return false;
    }

    if (previewing(id)) {
      return previewPlayer != null ? onCardOfMember(previewPlayer) : onCardOfRole(previewRoleId);
    }

    return onCardOfMember(id);
  }

  /** Where a role sets its players' words: one of {@link CardStyle}'s places. */
  public static synchronized String placeOfRole(String id) {
    load();
    Role role = id == null ? null : roles.get(id);
    return role == null || role.style.place() == null ? CardStyle.UNDER : role.style.place();
  }

  /** Where a player's words stand, whoever is looking: one of {@link CardStyle}'s places. */
  public static synchronized String placeOfMember(UUID id) {
    load();
    Member member = id == null ? null : players.get(id);
    if (member == null || !roles.containsKey(member.roleId)) {
      return CardStyle.UNDER;
    }

    String own = member.style.place();
    return own != null ? own : placeOfRole(member.roleId);
  }

  private static boolean onCardOfRole(String id) {
    return CardStyle.CARD.equals(placeOfRole(id));
  }

  private static boolean onCardOfMember(UUID id) {
    return CardStyle.CARD.equals(placeOfMember(id));
  }

  private static boolean previewing(UUID id) {
    return previewing() && id.equals(previewOwner);
  }

  /** Whether the session's own card is showing what is being edited on the Staff page. */
  public static synchronized boolean previewing() {
    return previewShownRole != null || previewHidden;
  }

  private static String shownTitle(Member member) {
    return member.titleOn ? member.title : null;
  }

  /**
   * Whether this session really is an account of the published list. False until a background check has confirmed it
   * with the account service, so ask again later. The session token goes to that service only, and only for an account
   * on the list. A refusal holds for the session; a service that could not be reached is asked again after a while.
   */
  public static synchronized boolean verifiedTeamMember() {
    load();
    User user = Minecraft.getInstance().getUser();
    UUID id = user.getProfileId();
    if (!Objects.equals(id, sessionId)) {
      sessionId = id;
      forgetSession();
    }

    if (verified) {
      return true;
    }

    if (!refused && !checking && id != null && publishedPlayers.containsKey(id)
        && System.currentTimeMillis() >= nextCheckMs) {
      checking = true;
      String token = user.getAccessToken();
      worker("Lyrica account check", () -> checkSession(id, token));
    }

    return false;
  }

  /**
   * Whether this session may open the Staff page and change the list: a confirmed account that the jar or a download says
   * may do so. Neither the local file nor the copy on this PC counts here, so that neither can give anybody the right to
   * edit. What is published therefore decides who has the page.
   */
  public static synchronized boolean mayOpenPanel() {
    return verifiedTeamMember() && panelGrantedTo(sessionId);
  }

  /** Whether the jar or a download gives this account the page. */
  public static synchronized boolean panelGrantedTo(UUID id) {
    load();
    return id != null && panelHolders.contains(id);
  }

  private static void trustPanels() {
    panelHolders.clear();
    for (Member member : publishedPlayers.values()) {
      Role role = publishedRoles.get(member.roleId);
      if (member.panel != null ? member.panel : role != null && role.panel) {
        panelHolders.add(member.id);
      }
    }
  }

  /** Whether the list as it stands now gives this player the page, for showing on it. */
  public static synchronized boolean hasPanel(UUID id) {
    Member member = member(id);
    if (member == null) {
      return false;
    }

    Role role = roles.get(member.roleId);
    return member.panel != null ? member.panel : role != null && role.panel;
  }

  /** How many changes on this PC are not in the published list yet. */
  public static synchronized int localChanges() {
    load();
    return localRoles.size() + localPlayers.size() + removedRoles.size() + removedPlayers.size();
  }

  /** Forgets every change on this PC, which leaves the list as it is published. */
  public static synchronized void discardLocal() {
    load();
    if (editable()) {
      localRoles.clear();
      localPlayers.clear();
      removedRoles.clear();
      removedPlayers.clear();
      changed();
    }
  }

  /** Goes up whenever what the Staff page shows may have changed. */
  public static synchronized int revision() {
    return revision;
  }

  public static synchronized AddStatus addStatus() {
    return addStatus;
  }

  /** Those with the Staff page first, otherwise in the order of the list. */
  public static synchronized List<Role> roles() {
    load();
    List<Role> ordered = new ArrayList<>(roles.size());
    for (Role role : roles.values()) {
      if (role.panel) {
        ordered.add(role);
      }
    }

    for (Role role : roles.values()) {
      if (!role.panel) {
        ordered.add(role);
      }
    }

    return ordered;
  }

  /** In the order they were added. */
  public static synchronized List<Member> members() {
    load();
    return new ArrayList<>(players.values());
  }

  public static synchronized Role role(String id) {
    load();
    return id == null ? null : roles.get(id);
  }

  public static synchronized Member member(UUID id) {
    load();
    return id == null ? null : players.get(id);
  }

  /** What the jar says of a role, or null for one it does not know. */
  public static synchronized Role publishedRole(String id) {
    load();
    return id == null ? null : publishedRoles.get(id);
  }

  /** What the jar says of a player, or null for one it does not know. */
  public static synchronized Member publishedMember(UUID id) {
    load();
    return id == null ? null : publishedPlayers.get(id);
  }

  /** How a role's cards look before any player's own style. */
  public static synchronized CardLook lookOfRole(String id) {
    load();
    CardLook look = id == null ? null : roleLooks.get(id);
    return look == null ? CardLook.BADGE : look;
  }

  /** How a player's card looks, whoever is looking at it. */
  public static synchronized CardLook lookOfMember(UUID id) {
    load();
    CardLook look = id == null ? null : shownLooks.get(id);
    return look == null ? CardLook.BADGE : look;
  }

  public static synchronized void addRole(String id, String label, int color) {
    load();
    String key = roleKey(id);
    if (!editable() || key.isEmpty()) {
      return;
    }

    // Adding a role that is already there sets its words and colour, and leaves the rest of it as it was
    Role existing = roles.get(key);
    Role published = publishedRoles.get(key);
    String text = label == null ? "" : CardStyle.text(label, MOST_TEXT);
    String words = !text.isEmpty() && !text.equals(key) ? text : existing == null ? key : existing.label;
    localRoles.put(key, new Role(key, published == null ? words : keepingKey(words, published.label), color & 0xFFFFFF,
        existing == null ? CardStyle.NONE : existing.style, existing != null && existing.panel));
    removedRoles.remove(key);
    changed();
  }

  /**
   * A new short name for a role, whose players go with it. It changes neither the words the role shows nor anything else.
   *
   * @return false when the name is empty or another role already has it
   */
  public static synchronized boolean renameRole(String id, String newId) {
    load();
    String wanted = newId == null ? "" : roleKey(newId);
    Role role = role(id);
    if (!editable() || role == null || wanted.isEmpty() || wanted.indexOf(' ') >= 0) {
      return false;
    }

    if (wanted.equals(id)) {
      return true;
    }

    if (roles.containsKey(wanted)) {
      return false;
    }

    List<Member> holders = new ArrayList<>();
    for (Member member : players.values()) {
      if (member.roleId.equals(id)) {
        holders.add(member);
      }
    }

    localRoles.remove(id);
    if (publishedRoles.containsKey(id)) {
      removedRoles.add(id);
    }

    localRoles.put(wanted, new Role(wanted, role.label, role.color, role.style, role.panel));
    for (int i = 0; i < holders.size(); i++) {
      Member member = holders.get(i);
      localPlayers.put(member.id, new Member(member.id, member.name, wanted, member.title, member.titleOn, member.label,
          member.style, member.panel));
    }

    changed();
    return true;
  }

  public static synchronized void setRolePanel(String id, boolean on) {
    Role role = role(id);
    if (role != null) {
      putRole(new Role(role.id, role.label, role.color, role.style, on));
    }
  }

  /** @param on null to go by the role */
  public static synchronized void setMemberPanel(UUID id, Boolean on) {
    Member member = member(id);
    if (member != null) {
      putMember(new Member(member.id, member.name, member.roleId, member.title, member.titleOn, member.label, member.style,
          on));
    }
  }

  public static synchronized void setMemberRole(UUID id, String roleId) {
    Member member = member(id);
    if (member != null && roles.containsKey(roleId)) {
      putMember(new Member(member.id, member.name, roleId, member.title, member.titleOn, member.label, member.style,
          member.panel));
    }
  }

  /** New words for a role. A role always has some, so empty text changes nothing. */
  public static synchronized void setRoleLabel(String id, String text) {
    Role role = role(id);
    String clean = text == null ? "" : CardStyle.text(text, MOST_TEXT);
    if (role == null || clean.isEmpty()) {
      return;
    }

    Role published = publishedRoles.get(id);
    String stored = published == null ? clean : keepingKey(clean, published.label);
    putRole(new Role(role.id, stored, role.color, role.style, role.panel));
  }

  public static synchronized void setRoleColor(String id, int rgb) {
    Role role = role(id);
    if (role != null) {
      putRole(new Role(role.id, role.label, rgb & 0xFFFFFF, role.style, role.panel));
    }
  }

  public static synchronized void restyleRole(String id, UnaryOperator<CardStyle> change) {
    Role role = role(id);
    if (role != null) {
      putRole(new Role(role.id, role.label, role.color, change.apply(role.style).apartFrom(CardLook.BADGE), role.panel));
    }
  }

  public static synchronized void setTitleOn(UUID id, boolean on) {
    Member member = member(id);
    if (member != null) {
      putMember(new Member(member.id, member.name, member.roleId, member.title, on, member.label, member.style,
          member.panel));
    }
  }

  /** The player's title; empty text means none. */
  public static synchronized void setTitle(UUID id, String text) {
    Member member = member(id);
    if (member == null) {
      return;
    }

    String clean = text == null ? "" : CardStyle.text(text, MOST_TEXT);
    Member published = publishedPlayers.get(id);
    String stored = published == null || published.title == null ? clean : keepingKey(clean, published.title);
    putMember(new Member(member.id, member.name, member.roleId, stored.isEmpty() ? null : stored, member.titleOn,
        member.label, member.style, member.panel));
  }

  /** The player's own label instead of the role's; empty text gives them the role's again. */
  public static synchronized void setOwnLabel(UUID id, String text) {
    Member member = member(id);
    if (member == null) {
      return;
    }

    String clean = text == null ? "" : CardStyle.text(text, MOST_TEXT);
    Member published = publishedPlayers.get(id);
    String stored = published == null || published.label == null ? clean : keepingKey(clean, published.label);
    // The role's own words are no label of their own, and would stop following the role and its translation
    Role role = roles.get(member.roleId);
    boolean roleWords = role != null && clean.equals(LyricsBridge.StaffRole.shown(role.label));
    putMember(new Member(member.id, member.name, member.roleId, member.title, member.titleOn,
        stored.isEmpty() || roleWords ? null : stored, member.style, member.panel));
  }

  public static synchronized void restyleMember(UUID id, UnaryOperator<CardStyle> change) {
    Member member = member(id);
    if (member != null) {
      CardStyle style = change.apply(member.style).apartFrom(lookOfRole(member.roleId))
          .placeApartFrom(placeOfRole(member.roleId));
      putMember(new Member(member.id, member.name, member.roleId, member.title, member.titleOn, member.label, style,
          member.panel));
    }
  }

  /** Shows this player's card on the session's own character, until {@link #stopPreview()}. On this game only. */
  public static synchronized void previewMember(UUID id) {
    load();
    if (editable()) {
      previewOwner = Minecraft.getInstance().getUser().getProfileId();
      previewPlayer = id;
      previewRoleId = null;
      merge();
    }
  }

  /** Shows a card of this role on the session's own character, until {@link #stopPreview()}. On this game only. */
  public static synchronized void previewRole(String id) {
    load();
    if (editable()) {
      previewOwner = Minecraft.getInstance().getUser().getProfileId();
      previewPlayer = null;
      previewRoleId = id;
      merge();
    }
  }

  public static synchronized void stopPreview() {
    previewOwner = null;
    previewPlayer = null;
    previewRoleId = null;
    previewShownRole = null;
    previewTitle = null;
    previewLook = null;
    previewHidden = false;
  }

  private static void putRole(Role role) {
    if (editable() && !role.equals(roles.get(role.id))) {
      localRoles.put(role.id, role);
      removedRoles.remove(role.id);
      changed();
    }
  }

  private static void putMember(Member member) {
    if (editable() && !member.equals(players.get(member.id))) {
      localPlayers.put(member.id, member);
      removedPlayers.remove(member.id);
      changed();
    }
  }

  /** Words typed that read just like the published translation stay that translation, so the list keeps its key. */
  private static String keepingKey(String typed, String published) {
    return typed.equals(LyricsBridge.StaffRole.shown(published)) ? published : typed;
  }

  private static boolean editable() {
    return verified && panelHolders.contains(sessionId) || DevSmoke.staffPageTest();
  }

  /** Takes a role away, with the players who held it. */
  public static synchronized void removeRole(String id) {
    load();
    if (!editable()) {
      return;
    }

    String key = roleKey(id);
    localRoles.remove(key);
    if (publishedRoles.containsKey(key)) {
      removedRoles.add(key);
    }

    List<UUID> holders = new ArrayList<>();
    for (Member member : players.values()) {
      if (member.roleId.equals(key)) {
        holders.add(member.id);
      }
    }

    for (int i = 0; i < holders.size(); i++) {
      dropPlayer(holders.get(i));
    }

    changed();
  }

  /**
   * Starts adding a player: the name is turned into its account id on a background thread, and a name without an
   * account is refused. {@link #addStatus()} tells how it went.
   *
   * @return false when it cannot even start: not a possible name, no such role, or another lookup still running
   */
  public static synchronized boolean addPlayer(String name, String roleId) {
    load();
    String wanted = name == null ? "" : name.strip();
    String role = roleId == null ? "" : roleKey(roleId);
    if (!editable() || lookingUp || !ACCOUNT_NAME.matcher(wanted).matches() || !roles.containsKey(role)) {
      return false;
    }

    lookingUp = true;
    setAddStatus("busy", wanted);
    worker("Lyrica name lookup", () -> lookUp(wanted, role));
    return true;
  }

  public static synchronized void removePlayer(UUID id) {
    load();
    if (editable() && id != null) {
      dropPlayer(id);
      changed();
    }
  }

  /** The whole list as it stands, in the format of the published file. */
  public static synchronized String exportJson() {
    load();
    return new GsonBuilder().setPrettyPrinting().create().toJson(listJson(roles, players));
  }

  private static void checkSession(UUID id, String token) {
    boolean confirmed = false;
    boolean answered = false;
    try {
      Http.Reply reply = Http.getWithToken(PROFILE_URL, token);
      int status = reply.status();
      answered = status == 200 || status == 401 || status == 403 || status == 404;
      if (status == 200) {
        JsonObject profile = JsonParser.parseString(reply.body()).getAsJsonObject();
        confirmed = id.equals(parseId(profile.get("id").getAsString()));
      }
    } catch (IOException | RuntimeException e) {
      Env.LOG.debug("Could not check the account: {}", e.toString());
    }

    boolean mayEdit;
    synchronized (StaffRoster.class) {
      mayEdit = confirmed && panelHolders.contains(id);
    }

    LocalList local = mayEdit ? readLocalFile() : null;
    synchronized (StaffRoster.class) {
      if (!id.equals(sessionId)) {
        return;
      }

      checking = false;
      if (confirmed) {
        verified = true;
        if (local != null) {
          adopt(local);
        } else {
          revision++;
        }
      } else if (answered) {
        refused = true;
      } else {
        nextCheckMs = System.currentTimeMillis() + CHECK_RETRY_MS;
      }
    }
  }

  private static void adopt(LocalList local) {
    localRead = true;
    localRoles.putAll(local.roles);
    localPlayers.putAll(local.players);
    removedRoles.addAll(local.removedRoles);
    removedPlayers.addAll(local.removedPlayers);
    merge();
    if (dropWhatIsPublished()) {
      merge();
      writeLocal();
    }

    revision++;
  }

  /**
   * Now and then from the game's tick: asks for the published list on a worker thread. Whatever is in use stays in use
   * until a whole new list has come and been read.
   */
  public static synchronized void refreshPublished() {
    load();
    if (PUBLISHED_URL.isEmpty() || fetching || System.currentTimeMillis() < nextFetchMs) {
      return;
    }

    fetching = true;
    worker("Lyrica team list", () -> fetchPublished(PUBLISHED_URL));
  }

  private static void fetchPublished(String url) {
    boolean readCopy;
    synchronized (StaffRoster.class) {
      readCopy = !copyTried;
      copyTried = true;
    }

    if (readCopy) {
      String copy = readPublishedCopy();
      if (copy != null) {
        takePublished(copy, false);
      }
    }

    boolean taken = false;
    try {
      Http.Reply reply = Http.getAtMost(url, MOST_PUBLISHED_BYTES);
      if (reply.status() == 200) {
        taken = takePublished(reply.body(), true);
      } else {
        Env.LOG.debug("The published team list answered {}", reply.status());
      }
    } catch (IOException | RuntimeException e) {
      Env.LOG.debug("Could not read the published team list: {}", e.toString());
    }

    synchronized (StaffRoster.class) {
      fetching = false;
      nextFetchMs = System.currentTimeMillis() + (taken ? PUBLISHED_EVERY_MS : PUBLISHED_RETRY_MS);
    }
  }

  private static String readPublishedCopy() {
    Path file = publishedCopy();
    try {
      return Files.isRegularFile(file) && Files.size(file) <= MOST_PUBLISHED_BYTES
          ? Files.readString(file, StandardCharsets.UTF_8) : null;
    } catch (IOException | RuntimeException e) {
      Env.LOG.debug("Could not read the saved team list: {}", e.toString());
      return null;
    }
  }

  private static Path publishedCopy() {
    return Env.dir().resolve(PUBLISHED_COPY);
  }

  /**
   * A published list comes into use only as a whole. Nothing here can mistake an error page or an emptied file for a team
   * of nobody.
   *
   * @param live whether it has just come from the published address, rather than from the copy on this PC
   * @return whether it could be used
   */
  private static boolean takePublished(String text, boolean live) {
    Map<String, Role> readRoles = new LinkedHashMap<>();
    Map<UUID, Member> readPlayers = new LinkedHashMap<>();
    try {
      readList(JsonParser.parseString(text).getAsJsonObject(), readRoles, readPlayers);
    } catch (RuntimeException e) {
      Env.LOG.warn("Could not read the published team list: {}", e.toString());
      return false;
    }

    if (readRoles.isEmpty()) {
      Env.LOG.warn("The published team list has no roles, so it is not used");
      return false;
    }

    boolean changedNow;
    synchronized (StaffRoster.class) {
      load();
      changedNow = !text.equals(publishedText);
      if (changedNow) {
        publishedText = text;
        publishedRoles.clear();
        publishedRoles.putAll(readRoles);
        publishedPlayers.clear();
        publishedPlayers.putAll(readPlayers);
      }

      if (live) {
        trustPanels();
        publishedLive = true;
      }

      if (changedNow) {
        merge();
        if (localRead && dropWhatIsPublished()) {
          merge();
          writeLocal();
        }

        revision++;
      }

      followPanel();
    }

    if (live && changedNow) {
      AtomicFile.later(publishedCopy(), text);
    }

    return true;
  }

  /**
   * Local changes that have since been published are done with, and would otherwise cover what is published after them.
   *
   * @return whether any went
   */
  private static boolean dropWhatIsPublished() {
    boolean any = false;
    Iterator<Map.Entry<String, Role>> localRole = localRoles.entrySet().iterator();
    while (localRole.hasNext()) {
      Map.Entry<String, Role> entry = localRole.next();
      Role published = publishedRoles.get(entry.getKey());
      if (published != null && roleJson(entry.getValue()).equals(roleJson(published))) {
        localRole.remove();
        any = true;
      }
    }

    Iterator<Map.Entry<UUID, Member>> localPlayer = localPlayers.entrySet().iterator();
    while (localPlayer.hasNext()) {
      Map.Entry<UUID, Member> entry = localPlayer.next();
      Member published = publishedPlayers.get(entry.getKey());
      if (published != null && memberJson(entry.getValue()).equals(memberJson(published))) {
        localPlayer.remove();
        any = true;
      }
    }

    any |= removedRoles.retainAll(publishedRoles.keySet());
    any |= removedPlayers.retainAll(publishedPlayers.keySet());
    return any;
  }

  /** The page can come and go with a download, and the local file with it. Taking it away here leaves the file alone. */
  private static void followPanel() {
    boolean may = verified && panelHolders.contains(sessionId);
    if (may && !localRead) {
      localRead = true;
      worker("Lyrica local team list", () -> {
        LocalList local = readLocalFile();
        synchronized (StaffRoster.class) {
          if (verified && panelHolders.contains(sessionId)) {
            adopt(local);
          }
        }
      });
    } else if (!may && localRead && !DevSmoke.staffPageTest()) {
      localRead = false;
      localRoles.clear();
      localPlayers.clear();
      removedRoles.clear();
      removedPlayers.clear();
      stopPreview();
      merge();
      revision++;
    }
  }

  /** How the published list stands, as the end of a text key: live, saved or jar. */
  public static synchronized String publishedState() {
    load();
    return publishedLive ? "live" : publishedText != null ? "saved" : "jar";
  }

  /** Development only: reads a published list from this address now. Does nothing anywhere else. */
  public static synchronized void fetchPublishedForTest(String url) {
    load();
    if (DevSmoke.staffPageTest() && !fetching) {
      fetching = true;
      worker("Lyrica team list test", () -> fetchPublished(url));
    }
  }

  /** Development only: takes this as the copy on this PC would be taken. Does nothing anywhere else. */
  public static void takeCopyForTest(String text) {
    if (DevSmoke.staffPageTest()) {
      takePublished(text, false);
    }
  }

  /** Development only: reads the local file as a confirmed member's game would. Does nothing anywhere else. */
  public static synchronized void reloadLocalForTest() {
    load();
    if (DevSmoke.staffPageTest()) {
      localRoles.clear();
      localPlayers.clear();
      removedRoles.clear();
      removedPlayers.clear();
      adopt(readLocalFile());
    }
  }

  private static void lookUp(String name, String role) {
    UUID id = null;
    String shownName = name;
    String outcome = "unreachable";
    try {
      Http.Reply reply = Http.get(NAME_LOOKUP_URL + name);
      if (reply.status() == 200) {
        JsonObject profile = JsonParser.parseString(reply.body()).getAsJsonObject();
        id = parseId(profile.get("id").getAsString());
        shownName = profile.has("name") ? profile.get("name").getAsString() : name;
        outcome = id == null ? "no_account" : "added";
      } else if (reply.status() == 204 || reply.status() == 404) {
        outcome = "no_account";
      }
    } catch (IOException | RuntimeException e) {
      Env.LOG.debug("Could not look up a player: {}", e.toString());
    }

    synchronized (StaffRoster.class) {
      lookingUp = false;
      if (id != null && editable() && roles.containsKey(role)) {
        String kept = CardStyle.text(shownName, MOST_NAME);
        // Somebody already on the list changes role and keeps their title, label and style
        Member was = players.get(id);
        localPlayers.put(id, was == null
            ? new Member(id, kept.isEmpty() ? name : kept, role, null, true, null, CardStyle.NONE, null)
            : new Member(id, kept.isEmpty() ? name : kept, role, was.title, was.titleOn, was.label, was.style, was.panel));
        removedPlayers.remove(id);
        changed();
      }

      setAddStatus(outcome, shownName);
    }
  }

  private static void setAddStatus(String outcome, String name) {
    addStatus = new AddStatus("lyrica.menu.staff.lookup." + outcome, name);
    revision++;
  }

  private static void forgetSession() {
    verified = false;
    refused = false;
    checking = false;
    nextCheckMs = 0L;
    addStatus = null;
    localRead = false;
    localRoles.clear();
    localPlayers.clear();
    removedRoles.clear();
    removedPlayers.clear();
    stopPreview();
    merge();
    revision++;
  }

  private static void dropPlayer(UUID id) {
    localPlayers.remove(id);
    if (publishedPlayers.containsKey(id)) {
      removedPlayers.add(id);
    }
  }

  private static void changed() {
    merge();
    revision++;
    writeLocal();
  }

  private static void writeLocal() {
    JsonObject json = listJson(localRoles, localPlayers);
    json.add("removedRoles", stringArray(removedRoles));
    json.add("removedPlayers", stringArray(removedPlayers));
    AtomicFile.later(localFile(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
  }

  private static void merge() {
    roles.clear();
    for (Role role : publishedRoles.values()) {
      if (!removedRoles.contains(role.id)) {
        roles.put(role.id, role);
      }
    }

    roles.putAll(localRoles);
    players.clear();
    for (Member member : publishedPlayers.values()) {
      if (!removedPlayers.contains(member.id)) {
        players.put(member.id, member);
      }
    }

    players.putAll(localPlayers);

    roleLooks.clear();
    for (Role role : roles.values()) {
      roleLooks.put(role.id, role.style.over(CardLook.BADGE));
    }

    shownRoles.clear();
    shownLooks.clear();
    for (Member member : players.values()) {
      Role role = roles.get(member.roleId);
      if (role != null) {
        // Hidden: no card from anybody's list, while the page can still show and change how it would look
        if (!CardStyle.HIDDEN.equals(placeOfMember(member.id))) {
          shownRoles.put(member.id, shownRole(role, member));
        }

        shownLooks.put(member.id, member.style.over(roleLooks.get(role.id)));
      }
    }

    mergePreview();
  }

  private static Role shownRole(Role role, Member member) {
    return member.label == null ? role : new Role(role.id, member.label, role.color, role.style, role.panel);
  }

  private static void mergePreview() {
    previewShownRole = null;
    previewTitle = null;
    previewLook = null;
    previewHidden = false;
    Member previewed = previewPlayer == null ? null : players.get(previewPlayer);
    if (previewed != null) {
      previewShownRole = shownRoles.get(previewed.id);
      previewHidden = previewShownRole == null && roles.containsKey(previewed.roleId);
      previewTitle = shownTitle(previewed);
      previewLook = shownLooks.get(previewed.id);
    } else if (previewRoleId != null && roles.containsKey(previewRoleId)) {
      // A role's card is seen with a title on it, if any of its players has one
      previewHidden = CardStyle.HIDDEN.equals(placeOfRole(previewRoleId));
      previewShownRole = previewHidden ? null : roles.get(previewRoleId);
      previewLook = roleLooks.get(previewRoleId);
      for (Member member : players.values()) {
        if (previewTitle == null && member.roleId.equals(previewRoleId)) {
          previewTitle = shownTitle(member);
        }
      }
    }
  }

  private static void load() {
    if (loaded) {
      return;
    }

    loaded = true;
    try (InputStream in = StaffRoster.class.getResourceAsStream(BUNDLED)) {
      if (in == null) {
        Env.LOG.warn("The bundled staff list is missing");
      } else {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        readList(JsonParser.parseString(text).getAsJsonObject(), publishedRoles, publishedPlayers);
        trustPanels();
      }
    } catch (IOException | RuntimeException e) {
      Env.LOG.warn("Could not read the bundled staff list: {}", e.toString());
    }

    merge();
  }

  private static LocalList readLocalFile() {
    LocalList local = new LocalList(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>(),
        new LinkedHashSet<>());
    Path file = localFile();
    if (Files.isRegularFile(file)) {
      try {
        if (Files.size(file) > MOST_FILE_BYTES) {
          Env.LOG.warn("The local staff list is too long to read");
          return local;
        }

        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        readList(json, local.roles, local.players);
        JsonElement goneRoles = json.get("removedRoles");
        if (goneRoles != null && goneRoles.isJsonArray()) {
          JsonArray array = goneRoles.getAsJsonArray();
          for (int i = 0; i < array.size(); i++) {
            String id = string(array.get(i));
            if (id != null) {
              local.removedRoles.add(roleKey(id));
            }
          }
        }

        JsonElement gonePlayers = json.get("removedPlayers");
        if (gonePlayers != null && gonePlayers.isJsonArray()) {
          JsonArray array = gonePlayers.getAsJsonArray();
          for (int i = 0; i < array.size(); i++) {
            String text = string(array.get(i));
            UUID id = text == null ? null : parseId(text);
            if (id != null) {
              local.removedPlayers.add(id);
            }
          }
        }
      } catch (IOException | RuntimeException e) {
        Env.LOG.warn("Could not read the local staff list: {}", e.toString());
      }
    }

    return local;
  }

  private static Path localFile() {
    return Env.dir().resolve("staff.json");
  }

  private static void readList(JsonObject json, Map<String, Role> intoRoles, Map<UUID, Member> intoPlayers) {
    JsonElement roleList = json.get("roles");
    if (roleList != null && roleList.isJsonArray()) {
      JsonArray array = roleList.getAsJsonArray();
      for (int i = 0; i < array.size() && intoRoles.size() < MOST_ENTRIES; i++) {
        readRole(array.get(i), intoRoles);
      }
    }

    JsonElement playerList = json.get("players");
    if (playerList != null && playerList.isJsonObject()) {
      for (Map.Entry<String, JsonElement> entry : playerList.getAsJsonObject().entrySet()) {
        if (intoPlayers.size() >= MOST_ENTRIES) {
          break;
        }

        readPlayer(entry.getKey(), entry.getValue(), intoPlayers);
      }
    }
  }

  /** A bad entry is left out, and costs nothing else on the list. */
  private static void readRole(JsonElement element, Map<String, Role> into) {
    if (!element.isJsonObject()) {
      return;
    }

    JsonObject role = element.getAsJsonObject();
    String rawId = string(role.get("id"));
    String id = rawId == null ? "" : roleKey(rawId);
    if (id.isEmpty()) {
      return;
    }

    String label = readText(role.get("label"));
    Integer color = CardStyle.color(role.get("color"));
    Boolean panel = readFlag(role.get("panel"));
    into.put(id, new Role(id, label == null ? id : label, color == null ? DEFAULT_COLOR : color,
        CardStyle.read(role.get("style")), panel != null && panel));
  }

  private static void readPlayer(String key, JsonElement element, Map<UUID, Member> into) {
    UUID id = parseId(key);
    if (id == null || !element.isJsonObject()) {
      return;
    }

    JsonObject player = element.getAsJsonObject();
    String rawRole = string(player.get("role"));
    String role = rawRole == null ? "" : roleKey(rawRole);
    if (role.isEmpty()) {
      return;
    }

    String rawName = string(player.get("name"));
    String name = rawName == null ? "" : CardStyle.text(rawName, MOST_NAME);
    JsonElement show = player.get("showTitle");
    boolean titleOn = show == null || !show.isJsonPrimitive() || !show.getAsJsonPrimitive().isBoolean()
        || show.getAsBoolean();
    into.put(id, new Member(id, name.isEmpty() ? id.toString() : name, role, readText(player.get("title")), titleOn,
        readText(player.get("label")), CardStyle.read(player.get("style")), readFlag(player.get("panel"))));
  }

  /** Only a real true or false counts. */
  private static Boolean readFlag(JsonElement element) {
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
        ? Boolean.valueOf(element.getAsBoolean()) : null;
  }

  /** Text for a card, or null if there is none. */
  private static String readText(JsonElement element) {
    String raw = string(element);
    String text = raw == null ? "" : CardStyle.text(raw, MOST_TEXT);
    return text.isEmpty() ? null : text;
  }

  /** Only a real string counts, not a number or anything else that could be read as one. */
  private static String string(JsonElement element) {
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
        ? element.getAsString() : null;
  }

  private static JsonObject listJson(Map<String, Role> fromRoles, Map<UUID, Member> fromPlayers) {
    JsonArray roleArray = new JsonArray();
    for (Role role : fromRoles.values()) {
      roleArray.add(roleJson(role));
    }

    JsonObject playerObject = new JsonObject();
    for (Member member : fromPlayers.values()) {
      playerObject.add(member.id.toString(), memberJson(member));
    }

    JsonObject json = new JsonObject();
    json.add("roles", roleArray);
    json.add("players", playerObject);
    return json;
  }

  private static JsonObject roleJson(Role role) {
    JsonObject item = new JsonObject();
    item.addProperty("id", role.id);
    item.addProperty("label", role.label);
    item.addProperty("color", Palette.hex(role.color));
    if (role.panel) {
      item.addProperty("panel", true);
    }

    CardStyle style = role.style.apartFrom(CardLook.BADGE);
    if (!style.empty()) {
      item.add("style", style.json());
    }

    return item;
  }

  private static JsonObject memberJson(Member member) {
    JsonObject item = new JsonObject();
    item.addProperty("name", member.name);
    item.addProperty("role", member.roleId);
    if (member.title != null) {
      item.addProperty("title", member.title);
    }

    if (!member.titleOn) {
      item.addProperty("showTitle", false);
    }

    if (member.label != null) {
      item.addProperty("label", member.label);
    }

    if (member.panel != null) {
      item.addProperty("panel", member.panel);
    }

    // A role can have come round to what the player set before, and then the player's says nothing
    CardStyle style = member.style.apartFrom(lookOfRole(member.roleId));
    if (!style.empty()) {
      item.add("style", style.json());
    }

    return item;
  }

  private static JsonArray stringArray(Set<?> values) {
    JsonArray array = new JsonArray();
    for (Object value : values) {
      array.add(value.toString());
    }

    return array;
  }

  /** An account id with or without its dashes, or null; the services answer without them. */
  private static UUID parseId(String text) {
    String id = text.strip();
    if (id.length() == 32) {
      id = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20)
          + "-" + id.substring(20);
    }

    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static void worker(String name, Runnable work) {
    Thread thread = new Thread(work, name);
    thread.setDaemon(true);
    thread.start();
  }

  private static String roleKey(String text) {
    return CardStyle.text(text, MOST_ID).toLowerCase(Locale.ROOT);
  }
}
