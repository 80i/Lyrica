package net.lyrica.v1_21_11.look;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.RemoteChatSession;

/**
 * Whether this game has more than the server's word that a player really is an account. A card asks this; the Staff page
 * and "Copy for release" read the list directly. The local account needs to be the game's own player and to have the
 * account service's confirmation of this session. Anybody else needs a live chat session for exactly that account: the
 * game sets one in a single place, after checking Mojang's signature over the account id, the key's expiry and the key.
 * That signature proves who the key belongs to and not who sent it, and it says nothing about the entity. A session's
 * data is public, so whoever has seen it can have it relayed, by a dishonest server or as a client on an honest one, and
 * an entity with the id of an author who is on the server gets that author's own session. So this raises the bar but
 * cannot be absolute. See the design document.
 *
 * <p>Asked for each player on the list on each frame, so it keeps nothing and makes nothing: a card comes as soon as the
 * session does and goes as soon as the session is cleared or runs out.
 */
public final class AccountProof {

  private AccountProof() {
  }

  public static boolean proves(UUID account) {
    Minecraft client = Minecraft.getInstance();
    if (account.equals(client.getUser().getProfileId())) {
      return isOwnPlayer(account, client.player) && StaffRoster.verifiedTeamMember();
    }

    return hasLiveSession(client.getConnection(), account, System.currentTimeMillis());
  }

  /**
   * The local account is the game's own player and no other entity: the level drops a second entity with the id of one it
   * has, but where the own player goes by another id, as on an offline-mode server, the account's id on an entity is only
   * the server's word.
   */
  public static boolean isOwnPlayer(UUID account, LocalPlayer own) {
    return own != null && account.equals(own.getUUID());
  }

  /** Reads the key's expiry itself, because {@link RemoteChatSession#hasExpired()} makes an Instant on every call. */
  public static boolean hasLiveSession(ClientPacketListener connection, UUID account, long nowMs) {
    if (connection == null) {
      return false;
    }

    PlayerInfo info = connection.getPlayerInfo(account);
    if (info == null || !account.equals(info.getProfile().id())) {
      return false;
    }

    RemoteChatSession session = info.getChatSession();
    return session != null && session.profilePublicKey().data().expiresAt().toEpochMilli() >= nowMs;
  }
}
