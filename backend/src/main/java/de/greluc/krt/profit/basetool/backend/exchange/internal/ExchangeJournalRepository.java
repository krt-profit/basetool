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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and purges the exchange write journal (REQ-XCH-021, REQ-XCH-022). */
public interface ExchangeJournalRepository extends JpaRepository<ExchangeJournalEntry, UUID> {

  /**
   * Counts a client's removals of a member's entries of one resource since a point in time, leaving
   * out the ones the member undid.
   *
   * @param userId the member
   * @param clientId the client
   * @param resource the resource
   * @param since the start of the window
   * @return the number of removals
   */
  @Query(
      """
      SELECT COUNT(j) FROM ExchangeJournalEntry j
      WHERE j.userId = :userId AND j.clientId = :clientId AND j.resource = :resource
        AND j.removal = true AND j.undoneAt IS NULL AND j.recordedAt >= :since
      """)
  long countRemovals(
      @Param("userId") UUID userId,
      @Param("clientId") String clientId,
      @Param("resource") ExchangeResource resource,
      @Param("since") Instant since);

  /**
   * Lists a client's writes to a member's entries since a point in time that are not undone, newest
   * first, the order an undo walks them.
   *
   * @param userId the member
   * @param clientId the client
   * @param since the point in time
   * @return the writes
   */
  @Query(
      """
      SELECT j FROM ExchangeJournalEntry j
      WHERE j.userId = :userId AND j.clientId = :clientId AND j.undoneAt IS NULL
        AND j.recordedAt >= :since
      ORDER BY j.tx DESC, j.recordedAt DESC, j.id DESC
      """)
  List<ExchangeJournalEntry> findUndoable(
      @Param("userId") UUID userId,
      @Param("clientId") String clientId,
      @Param("since") Instant since);

  /**
   * Deletes every entry recorded before a cutoff.
   *
   * @param cutoff the oldest write still kept
   * @return the number of entries deleted
   */
  @Modifying
  @Query(value = "DELETE FROM exchange_journal WHERE recorded_at < :cutoff", nativeQuery = true)
  int deleteRecordedBefore(@Param("cutoff") Instant cutoff);

  /**
   * Lists a member's entries in the order they were written.
   *
   * @param userId the member
   * @return the entries
   */
  List<ExchangeJournalEntry> findAllByUserIdOrderByRecordedAtAsc(UUID userId);

  /**
   * Lists a client's latest writes to a member's entries.
   *
   * @param userId the member
   * @param clientId the client
   * @return at most ten writes, newest first
   */
  List<ExchangeJournalEntry> findTop10ByUserIdAndClientIdOrderByRecordedAtDescIdDesc(
      UUID userId, String clientId);

  /**
   * Finds a client's first write to one entry of a member since a point in time.
   *
   * @param userId the member
   * @param clientId the client
   * @param resource the resource
   * @param entityKey the entry's key
   * @param since the point in time
   * @return the first write, or empty
   */
  Optional<ExchangeJournalEntry>
      findFirstByUserIdAndClientIdAndResourceAndEntityKeyAndRecordedAtAfterOrderByRecordedAtAsc(
          UUID userId, String clientId, ExchangeResource resource, String entityKey, Instant since);

  /** The scope filter the bulk undo queries share; a {@code null} parameter matches everything. */
  String BULK_SCOPE =
      """
      FROM exchange_journal
      WHERE client_id = :clientId AND undone_at IS NULL AND recorded_at >= :since
        AND (CAST(:installationKey AS VARCHAR) IS NULL OR installation_key = :installationKey)
        AND (CAST(:resource AS VARCHAR) IS NULL OR resource = :resource)
      """;

  /**
   * Lists the members with writes of a client in a bulk undo's scope that are not undone.
   *
   * @param clientId the client
   * @param since the start of the span
   * @param installationKey the one installation, or {@code null} for all
   * @param resource the one resource's name, or {@code null} for all
   * @return the members, ordered by id
   */
  @Query(value = "SELECT DISTINCT user_id " + BULK_SCOPE + " ORDER BY user_id", nativeQuery = true)
  List<UUID> findUndoableMembers(
      @Param("clientId") String clientId,
      @Param("since") Instant since,
      @Param("installationKey") String installationKey,
      @Param("resource") String resource);

  /**
   * Counts a client's writes in a bulk undo's scope that are not undone.
   *
   * @param clientId the client
   * @param since the start of the span
   * @param installationKey the one installation, or {@code null} for all
   * @param resource the one resource's name, or {@code null} for all
   * @return how many there are
   */
  @Query(value = "SELECT COUNT(*) " + BULK_SCOPE, nativeQuery = true)
  long countUndoable(
      @Param("clientId") String clientId,
      @Param("since") Instant since,
      @Param("installationKey") String installationKey,
      @Param("resource") String resource);

  /**
   * Counts a client's writes that are not undone since a point in time per member and installation,
   * for choosing one installation to undo.
   *
   * @param clientId the client
   * @param since the start of the span
   * @return one row per member and installation
   */
  @Query(
      value =
          """
          SELECT user_id AS userId, installation_key AS installationKey, COUNT(*) AS entries
          FROM exchange_journal
          WHERE client_id = :clientId AND undone_at IS NULL AND recorded_at >= :since
          GROUP BY user_id, installation_key
          ORDER BY COUNT(*) DESC, user_id, installation_key
          LIMIT 500
          """,
      nativeQuery = true)
  List<InstallationWrites> countUndoableByInstallation(
      @Param("clientId") String clientId, @Param("since") Instant since);

  /** One member's installation and how many of its writes are not undone. */
  interface InstallationWrites {

    /**
     * Returns the member.
     *
     * @return the member's id
     */
    UUID getUserId();

    /**
     * Returns the installation's key thumbprint.
     *
     * @return the thumbprint
     */
    String getInstallationKey();

    /**
     * Returns how many writes are not undone.
     *
     * @return the count
     */
    long getEntries();
  }
}
