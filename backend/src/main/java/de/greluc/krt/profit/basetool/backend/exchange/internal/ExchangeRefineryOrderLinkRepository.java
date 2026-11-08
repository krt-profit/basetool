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

  /**
   * Finds the order a client's key names for a member.
   *
   * @param userId the member
   * @param clientId the client
   * @param clientKey the client's key
   * @return the link, or empty when the client gave no order of the member that key
   */
  Optional<ExchangeRefineryOrderLink> findByUserIdAndClientIdAndClientKey(
      UUID userId, String clientId, String clientKey);

  /**
   * Lists a client's keys of the given orders of a member.
   *
   * @param userId the member
   * @param clientId the client
   * @param orderIds the orders
   * @return the links among them the client made for the member
   */
  List<ExchangeRefineryOrderLink> findByUserIdAndClientIdAndRefineryOrderIdIn(
      UUID userId, String clientId, Collection<UUID> orderIds);

  /**
   * Removes the link of a key whose order no longer exists, freeing the key for a new create.
   *
   * @param userId the member
   * @param clientId the client
   * @param clientKey the client's key
   * @return the number of links removed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "DELETE FROM ExchangeRefineryOrderLink l WHERE l.userId = :userId AND l.clientId = :clientId"
          + " AND l.clientKey = :clientKey")
  int deleteKey(
      @Param("userId") UUID userId,
      @Param("clientId") String clientId,
      @Param("clientKey") String clientKey);
}
