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

/** Reads and purges the trigger-written exchange change feed (REQ-XCH-013, ADR-0224). */
public interface ExchangeChangeRepository extends JpaRepository<ExchangeChange, Long> {

  /**
   * Returns the oldest transaction id still running; every entry of a lower one is final.
   *
   * @return the watermark
   */
  @Query(
      value = "SELECT CAST(CAST(pg_snapshot_xmin(pg_current_snapshot()) AS text) AS bigint)",
      nativeQuery = true)
  long watermark();

  /**
   * Returns the feed position of the last entry older than a cutoff.
   *
   * @param cutoff the oldest change still kept
   * @return the position, empty when no entry is that old
   */
  @Query(
      value =
          """
          SELECT tx, seq FROM exchange_change WHERE changed_at < :cutoff
          ORDER BY tx DESC, seq DESC LIMIT 1
          """,
      nativeQuery = true)
  Optional<Position> lastPositionBefore(@Param("cutoff") Instant cutoff);

  /**
   * Deletes every entry up to and including a feed position.
   *
   * @param tx the position's transaction id
   * @param seq the position's sequence number
   * @return the number of entries deleted
   */
  @Modifying
  @Query(value = "DELETE FROM exchange_change WHERE (tx, seq) <= (:tx, :seq)", nativeQuery = true)
  int deleteThrough(@Param("tx") long tx, @Param("seq") long seq);

  /**
   * Lists a member's entries in sequence order.
   *
   * @param userId the member
   * @return the entries
   */
  List<ExchangeChange> findAllByUserIdOrderBySeqAsc(UUID userId);

  /**
   * Returns the keys of one resource that changed after a feed position and below the watermark,
   * each once with its latest entry, in the order of those entries.
   *
   * @param userId the member
   * @param resource the resource name
   * @param tx the position's transaction id
   * @param seq the position's sequence number
   * @param watermark the oldest transaction id still running
   * @param limit the most keys to return
   * @return the changed keys
   */
  @Query(
      value =
          """
          SELECT latest.entity_key AS entityKey, latest.tx AS tx, latest.seq AS seq
          FROM (SELECT DISTINCT ON (c.entity_key) c.entity_key, c.tx, c.seq
                FROM exchange_change c
                WHERE c.user_id = :userId AND c.resource = :resource
                  AND (c.tx, c.seq) > (:tx, :seq) AND c.tx < :watermark
                ORDER BY c.entity_key, c.tx DESC, c.seq DESC) latest
          ORDER BY latest.tx, latest.seq
          LIMIT :limit
          """,
      nativeQuery = true)
  List<ChangedKey> findChangedKeys(
      @Param("userId") UUID userId,
      @Param("resource") String resource,
      @Param("tx") long tx,
      @Param("seq") long seq,
      @Param("watermark") long watermark,
      @Param("limit") int limit);

  /**
   * Returns the latest entry of one key, the one that tells who changed it last.
   *
   * @param userId the member
   * @param resource the resource name
   * @param entityKey the key
   * @return the latest entry, or empty when the key has none left
   */
  @Query(
      value =
          """
          SELECT * FROM exchange_change
          WHERE user_id = :userId AND resource = :resource AND entity_key = :entityKey
          ORDER BY tx DESC, seq DESC LIMIT 1
          """,
      nativeQuery = true)
  Optional<ExchangeChange> findLatestForKey(
      @Param("userId") UUID userId,
      @Param("resource") String resource,
      @Param("entityKey") String entityKey);

  /** A feed position as a native query returns it. */
  interface Position {

    /**
     * The writing transaction's id.
     *
     * @return the id
     */
    long getTx();

    /**
     * The sequence number.
     *
     * @return the number
     */
    long getSeq();
  }

  /** A changed key with the position of its latest entry. */
  interface ChangedKey extends Position {

    /**
     * The entity's key within the resource.
     *
     * @return the key
     */
    String getEntityKey();
  }
}
