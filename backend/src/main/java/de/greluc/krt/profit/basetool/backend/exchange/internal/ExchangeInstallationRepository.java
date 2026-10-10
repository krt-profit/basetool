/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to the exchange installations and their deny-list entries (REQ-XCH-007, -008). */
public interface ExchangeInstallationRepository extends JpaRepository<ExchangeInstallation, UUID> {

  /**
   * Records that an installation was seen: creates it on first sight, otherwise moves its last-seen
   * time forward when it is older than the given threshold.
   *
   * @param clientId the Keycloak client id
   * @param userId the member
   * @param keyThumbprint the DPoP key thumbprint
   * @param now the current time
   * @param touchBefore last-seen times before this are moved to {@code now}
   * @return one row per written installation, holding its id and whether this call inserted it;
   *     empty when nothing was written
   */
  @Query(
      value =
          """
          INSERT INTO exchange_installation (id, exchange_client_id, user_id, key_thumbprint,
              first_seen_at, last_seen_at, created_at, version)
          SELECT gen_random_uuid(), c.id, :userId, :keyThumbprint, :now, :now, :now, 0
          FROM exchange_client c WHERE c.client_id = :clientId
          ON CONFLICT (exchange_client_id, user_id, key_thumbprint)
          DO UPDATE SET last_seen_at = :now
          WHERE exchange_installation.last_seen_at < :touchBefore
          RETURNING id, (xmax = 0) AS inserted
          """,
      nativeQuery = true)
  List<Touched> touch(
      @Param("clientId") String clientId,
      @Param("userId") UUID userId,
      @Param("keyThumbprint") String keyThumbprint,
      @Param("now") Instant now,
      @Param("touchBefore") Instant touchBefore);

  /** One installation {@link #touch} wrote. */
  interface Touched {

    /**
     * Returns the installation's id.
     *
     * @return the id
     */
    UUID getId();

    /**
     * Tells whether the touch created the installation.
     *
     * @return {@code true} on first sight
     */
    boolean getInserted();
  }

  /**
   * Loads one member's installation of a client by its key.
   *
   * @param clientId the Keycloak client id
   * @param userId the member
   * @param keyThumbprint the DPoP key thumbprint
   * @return the installation, or empty
   */
  @Query(
      """
      SELECT i FROM ExchangeInstallation i
      WHERE i.client.clientId = :clientId AND i.user.id = :userId
        AND i.keyThumbprint = :keyThumbprint
      """)
  Optional<ExchangeInstallation> findByKey(
      @Param("clientId") String clientId,
      @Param("userId") UUID userId,
      @Param("keyThumbprint") String keyThumbprint);

  /**
   * Lists a member's installations with their clients, newest first.
   *
   * @param userId the member
   * @return the installations
   */
  @EntityGraph(attributePaths = "client")
  @Query(
      "SELECT i FROM ExchangeInstallation i WHERE i.user.id = :userId ORDER BY i.firstSeenAt DESC")
  List<ExchangeInstallation> findAllByUserId(@Param("userId") UUID userId);

  /**
   * Lists every installation of one client with its member, for the admin's bulk undo.
   *
   * @param exchangeClientId the registry id of the client
   * @return the installations
   */
  @EntityGraph(attributePaths = "user")
  @Query("SELECT i FROM ExchangeInstallation i WHERE i.client.id = :exchangeClientId")
  List<ExchangeInstallation> findAllOfClientWithUser(
      @Param("exchangeClientId") UUID exchangeClientId);

  /**
   * Lists the installations revoked after a point in time, the live deny list.
   *
   * @param since the oldest revocation still denied
   * @return the revoked installations
   */
  @Query("SELECT i FROM ExchangeInstallation i WHERE i.revokedAt > :since")
  List<ExchangeInstallation> findRevokedSince(@Param("since") Instant since);

  /**
   * Deletes every installation the member disconnected before the cutoff, its deny-list entry
   * included (REQ-XCH-035).
   *
   * @param cutoff the oldest disconnect still kept
   * @return the number of installations deleted
   */
  @Modifying
  @Query(value = "DELETE FROM exchange_installation WHERE revoked_at < :cutoff", nativeQuery = true)
  int deleteRevokedBefore(@Param("cutoff") Instant cutoff);

  /**
   * Deletes every installation not revoked on its own that a whole-client disconnect before the
   * cutoff ended, that is one last seen at or before that disconnect (REQ-XCH-035).
   *
   * @param cutoff the oldest client revocation still kept
   * @return the number of installations deleted
   */
  @Modifying
  @Query(
      value =
          """
          DELETE FROM exchange_installation i
          WHERE i.revoked_at IS NULL
            AND EXISTS (SELECT 1 FROM exchange_client_revocation r
                        WHERE r.exchange_client_id = i.exchange_client_id
                          AND r.user_id = i.user_id
                          AND r.revoked_at < :cutoff
                          AND i.last_seen_at <= r.revoked_at)
          """,
      nativeQuery = true)
  int deleteEndedByRevocationsBefore(@Param("cutoff") Instant cutoff);

  /**
   * Loads a member's installations by their key thumbprints, with their clients.
   *
   * @param userId the member
   * @param keyThumbprints the thumbprints
   * @return the installations
   */
  @EntityGraph(attributePaths = "client")
  @Query(
      """
      SELECT i FROM ExchangeInstallation i WHERE i.user.id = :userId
      AND i.keyThumbprint IN :keyThumbprints
      """)
  List<ExchangeInstallation> findAllByUserAndKeys(
      @Param("userId") UUID userId, @Param("keyThumbprints") Collection<String> keyThumbprints);

  /**
   * Counts each client's live installations by member: neither revoked on their own nor seen last
   * before the member disconnected the whole client.
   *
   * @return one row per client with at least one live installation
   */
  @Query(
      """
      SELECT i.client.id AS id, COUNT(DISTINCT i.user.id) AS connectedMembers,
             MAX(i.lastSeenAt) AS lastSeenAt
      FROM ExchangeInstallation i
      LEFT JOIN ExchangeClientRevocation r
        ON r.key.exchangeClientId = i.client.id AND r.key.userId = i.user.id
      WHERE i.revokedAt IS NULL AND (r.revokedAt IS NULL OR i.lastSeenAt > r.revokedAt)
      GROUP BY i.client.id
      """)
  List<ClientUsage> countLiveByClient();

  /** One client's live use, from {@link #countLiveByClient}. */
  interface ClientUsage {

    /**
     * Returns the client's registry id.
     *
     * @return the id
     */
    UUID getId();

    /**
     * Returns how many members have a live installation of the client.
     *
     * @return the member count
     */
    long getConnectedMembers();

    /**
     * Returns when a live installation of the client was last seen.
     *
     * @return the time
     */
    Instant getLastSeenAt();
  }

  /**
   * Returns the members holding a connected, non-revoked installation of one client.
   *
   * @param clientId the registry client's id
   * @return the holders' user ids; never {@code null}, possibly empty
   */
  @Query(
      "SELECT DISTINCT i.user.id FROM ExchangeInstallation i WHERE i.client.id = :clientId"
          + " AND i.revokedAt IS NULL")
  Set<UUID> findHolderUserIdsByClient(@Param("clientId") UUID clientId);

  /**
   * Returns the members holding a connected, non-revoked installation of any client.
   *
   * @return the holders' user ids; never {@code null}, possibly empty
   */
  @Query("SELECT DISTINCT i.user.id FROM ExchangeInstallation i WHERE i.revokedAt IS NULL")
  Set<UUID> findAllHolderUserIds();
}
