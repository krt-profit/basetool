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
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** The client keys of refinery orders (REQ-XCH-052). */
@Repository
public interface ExchangeRefineryOrderLinkRepository
    extends JpaRepository<ExchangeRefineryOrderLink, UUID> {

  /** The first key of the two-key advisory locks on client keys, {@code RXCH}; theirs alone. */
  int KEY_LOCK_SPACE = 0x52584348;

  /**
   * Takes the lock on one client key of a member, which the transaction holds until it ends, so two
   * creates under one key decide one after the other (REQ-XCH-052). Keys whose text hashes alike
   * only wait for each other.
   *
   * @param userId the member
   * @param clientId the client
   * @param clientKey the client's key
   * @return always one
   */
  @Query(
      value =
          "SELECT 1 FROM pg_advisory_xact_lock("
              + KEY_LOCK_SPACE
              + ", hashtext(CAST(:userId AS text) || '|' || :clientId || '|' || :clientKey))",
      nativeQuery = true)
  int lockKey(
      @Param("userId") UUID userId,
      @Param("clientId") String clientId,
      @Param("clientKey") String clientKey);

  /**
   * Lists the links of a client's keys for a member that still name their order.
   *
   * @param userId the member
   * @param clientId the client
   * @param clientKeys the client's keys
   * @return the links not retired among those keys
   */
  List<ExchangeRefineryOrderLink> findByUserIdAndClientIdAndClientKeyInAndRetiredAtIsNull(
      UUID userId, String clientId, Collection<String> clientKeys);

  /**
   * Inserts the link of a new order under a key no live link holds, without reading first.
   *
   * @param id the link's id
   * @param userId the member
   * @param clientId the client
   * @param clientKey the client's key
   * @param orderId the order
   * @param createdAt when the client created the order
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO exchange_refinery_order_link
              (id, user_id, client_id, client_key, refinery_order_id, created_at)
          VALUES (:id, :userId, :clientId, :clientKey, :orderId, :createdAt)
          """,
      nativeQuery = true)
  void insert(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("clientId") String clientId,
      @Param("clientKey") String clientKey,
      @Param("orderId") UUID orderId,
      @Param("createdAt") Instant createdAt);

  /**
   * Finds the order a client's key names for a member now.
   *
   * @param userId the member
   * @param clientId the client
   * @param clientKey the client's key
   * @return the link not retired, or empty when the client gave no order of the member that key
   */
  Optional<ExchangeRefineryOrderLink> findByUserIdAndClientIdAndClientKeyAndRetiredAtIsNull(
      UUID userId, String clientId, String clientKey);

  /**
   * Lists a client's keys of the given orders of a member.
   *
   * @param userId the member
   * @param clientId the client
   * @param orderIds the orders
   * @return the links among them the client made for the member, retired ones included
   */
  List<ExchangeRefineryOrderLink> findByUserIdAndClientIdAndRefineryOrderIdIn(
      UUID userId, String clientId, Collection<UUID> orderIds);

  /**
   * Retires the link of a key whose order no longer exists, freeing the key for a new create while
   * the old order's tombstone keeps naming it.
   *
   * @param userId the member
   * @param clientId the client
   * @param clientKey the client's key
   * @param at when the new create takes the key over
   * @return the number of links retired
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE ExchangeRefineryOrderLink l SET l.retiredAt = :at WHERE l.userId = :userId"
          + " AND l.clientId = :clientId AND l.clientKey = :clientKey AND l.retiredAt IS NULL")
  int retireKey(
      @Param("userId") UUID userId,
      @Param("clientId") String clientId,
      @Param("clientKey") String clientKey,
      @Param("at") Instant at);

  /**
   * Finds the link of an order while its key still names it.
   *
   * @param orderId the order
   * @return the link not retired, or empty
   */
  Optional<ExchangeRefineryOrderLink> findByRefineryOrderIdAndRetiredAtIsNull(UUID orderId);

  /**
   * Finds the link of an order whose key a later create took over.
   *
   * @param orderId the order
   * @return the retired link, or empty
   */
  Optional<ExchangeRefineryOrderLink> findByRefineryOrderIdAndRetiredAtIsNotNull(UUID orderId);

  /**
   * Lists the retired links of one key, the latest retired first.
   *
   * @param userId the member
   * @param clientId the client
   * @param clientKey the client's key
   * @return the retired links
   */
  List<ExchangeRefineryOrderLink>
      findByUserIdAndClientIdAndClientKeyAndRetiredAtIsNotNullOrderByRetiredAtDesc(
          UUID userId, String clientId, String clientKey);

  /**
   * Lists the links of the given members whose key still names their order.
   *
   * @param userIds the members
   * @return the links not retired
   */
  List<ExchangeRefineryOrderLink> findByUserIdInAndRetiredAtIsNull(Collection<UUID> userIds);

  /**
   * Retires one link.
   *
   * @param id the link
   * @param at when its key stops naming the order
   * @return the number of links retired
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE ExchangeRefineryOrderLink l SET l.retiredAt = :at WHERE l.id = :id"
          + " AND l.retiredAt IS NULL")
  int retire(@Param("id") UUID id, @Param("at") Instant at);

  /**
   * Lets a retired link name its order by its key again; the key must have no other live link.
   *
   * @param id the link
   * @return the number of links revived
   */
  @Modifying(flushAutomatically = true)
  @Query("UPDATE ExchangeRefineryOrderLink l SET l.retiredAt = NULL WHERE l.id = :id")
  int revive(@Param("id") UUID id);

  /**
   * Lists the orders of links that no journal entry and no change-feed entry refers to any more.
   *
   * @return the order ids, each once
   */
  @Query(
      value =
          """
          SELECT DISTINCT l.refinery_order_id FROM exchange_refinery_order_link l
          WHERE NOT EXISTS (SELECT 1 FROM exchange_journal j WHERE j.resource = 'REFINERY_ORDER'
                              AND j.entity_key = CAST(l.refinery_order_id AS text))
            AND NOT EXISTS (SELECT 1 FROM exchange_change c WHERE c.resource = 'REFINERY_ORDER'
                              AND c.entity_key = CAST(l.refinery_order_id AS text))
          """,
      nativeQuery = true)
  List<UUID> findUnreferencedOrderIds();

  /**
   * Deletes the links of the given orders.
   *
   * @param orderIds the orders
   * @return the number of links deleted
   */
  @Modifying
  @Query("DELETE FROM ExchangeRefineryOrderLink l WHERE l.refineryOrderId IN :orderIds")
  int deleteByOrderIds(@Param("orderIds") Collection<UUID> orderIds);
}
