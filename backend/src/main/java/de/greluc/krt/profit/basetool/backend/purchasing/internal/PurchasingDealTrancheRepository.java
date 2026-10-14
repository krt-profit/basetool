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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence of the money tranches (REQ-PUR-012). */
@Repository
public interface PurchasingDealTrancheRepository
    extends JpaRepository<PurchasingDealTranche, UUID> {

  /**
   * Lists the tranches of a deal, oldest first.
   *
   * @param dealId the deal
   * @return the tranches
   */
  List<PurchasingDealTranche> findByDealIdOrderByCreatedAtAsc(UUID dealId);

  /**
   * Lists the tranches of several deals.
   *
   * @param dealIds the deals
   * @return the tranches, in no particular order
   */
  List<PurchasingDealTranche> findByDealIdIn(Collection<UUID> dealIds);

  /**
   * Finds the tranche a bank request pays.
   *
   * @param bankRequestId the bank request
   * @return the tranche, or empty
   */
  Optional<PurchasingDealTranche> findByBankRequestId(UUID bankRequestId);

  /**
   * Sums the requested and confirmed money of one delegate in a period (REQ-PUR-013).
   *
   * @param delegateUserId the delegate
   * @param from the start of the period, inclusive
   * @param to the end of the period, exclusive
   * @return the sum in whole aUEC
   */
  @Query(
      "SELECT COALESCE(SUM(t.amount), 0) FROM PurchasingDealTranche t, PurchasingDeal d WHERE"
          + " t.dealId = d.id AND d.delegateUserId = :delegate AND t.state IN"
          + " (de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState.REQUESTED,"
          + " de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState.CONFIRMED) AND"
          + " t.createdAt >= :from AND t.createdAt < :to")
  long sumLiveByDelegate(
      @Param("delegate") UUID delegateUserId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Sums the requested and confirmed money of everybody in a period (REQ-PUR-013).
   *
   * @param from the start of the period, inclusive
   * @param to the end of the period, exclusive
   * @return the sum in whole aUEC
   */
  @Query(
      "SELECT COALESCE(SUM(t.amount), 0) FROM PurchasingDealTranche t WHERE t.state IN"
          + " (de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState.REQUESTED,"
          + " de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState.CONFIRMED) AND"
          + " t.createdAt >= :from AND t.createdAt < :to")
  long sumLive(@Param("from") Instant from, @Param("to") Instant to);
}
