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

import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence of the deals (REQ-PUR-007); the list is filtered by specification. */
@Repository
public interface PurchasingDealRepository
    extends JpaRepository<PurchasingDeal, UUID>, JpaSpecificationExecutor<PurchasingDeal> {

  /**
   * Reads a deal under a pessimistic write lock, so concurrent state changes of one deal run one
   * after the other (REQ-PUR-026).
   *
   * @param id the deal
   * @return the locked deal, or empty
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT d FROM PurchasingDeal d WHERE d.id = :id")
  Optional<PurchasingDeal> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Lists the deals of a need that are in one of the given states.
   *
   * @param needId the need
   * @param statuses the states
   * @return the matching deals
   */
  List<PurchasingDeal> findByNeedIdAndStatusIn(UUID needId, Collection<DealStatus> statuses);

  /**
   * Counts the deals of a partner that settled without any loss.
   *
   * @param partnerId the partner
   * @return the number of clean settled deals
   */
  @Query(
      "SELECT COUNT(d) FROM PurchasingDeal d WHERE d.partnerId = :partnerId AND d.status ="
          + " de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus.SETTLED AND EXISTS"
          + " (SELECT 1 FROM PurchasingDealSettlement s WHERE s.dealId = d.id AND s.lossAmount ="
          + " 0)")
  long countCleanSettled(@Param("partnerId") UUID partnerId);

  /**
   * Counts the deals per partner for a set of partners, in one statement.
   *
   * @param partnerIds the partners
   * @return one row per partner that has deals
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.purchasing.internal.PartnerCountRow("
          + "d.partnerId, COUNT(d)) FROM PurchasingDeal d WHERE d.partnerId IN :partnerIds"
          + " GROUP BY d.partnerId")
  List<PartnerCountRow> countByPartners(@Param("partnerIds") Collection<UUID> partnerIds);

  /**
   * Counts the clean settled deals per partner for a set of partners, in one statement.
   *
   * @param partnerIds the partners
   * @return one row per partner that has clean settled deals
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.purchasing.internal.PartnerCountRow("
          + "d.partnerId, COUNT(d)) FROM PurchasingDeal d WHERE d.partnerId IN :partnerIds"
          + " AND d.status ="
          + " de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus.SETTLED"
          + " AND EXISTS (SELECT 1 FROM PurchasingDealSettlement s"
          + " WHERE s.dealId = d.id AND s.lossAmount = 0) GROUP BY d.partnerId")
  List<PartnerCountRow> countCleanSettledByPartners(
      @Param("partnerIds") Collection<UUID> partnerIds);

  /**
   * Counts the deals in one state, for the gauges.
   *
   * @param status the state
   * @return the number of deals in it
   */
  long countByStatus(DealStatus status);

  /**
   * Counts the deals whose settlement is overdue at a moment.
   *
   * @param now the moment
   * @return the number of deals past their due date without a recorded settlement
   */
  @Query(
      "SELECT COUNT(d) FROM PurchasingDeal d WHERE d.status IN"
          + " (de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus.DELIVERED,"
          + " de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus.FAILED)"
          + " AND d.settlementDueAt < :now")
  long countOverdue(@Param("now") Instant now);

  /**
   * Lists the ids of the deals that are overdue and have not been announced yet.
   *
   * @param now the moment
   * @param limit the most ids to return
   * @return the overdue deals without an overdue marker
   */
  @Query(
      "SELECT d.id FROM PurchasingDeal d WHERE d.status IN"
          + " (de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus.DELIVERED,"
          + " de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus.FAILED)"
          + " AND d.settlementDueAt < :now AND d.overdueNotifiedAt IS NULL ORDER BY"
          + " d.settlementDueAt")
  List<UUID> findOverdueNotYetAnnouncedIds(@Param("now") Instant now, Limit limit);

  /**
   * Marks a deal as announced, atomically, without touching its version: the one caller whose
   * update changes a row is the one that announces it.
   *
   * @param id the deal
   * @param now the announcement time
   * @return {@code 1} when this call marked the deal, {@code 0} when it was marked already
   */
  @Modifying
  @Query(
      "UPDATE PurchasingDeal d SET d.overdueNotifiedAt = :now"
          + " WHERE d.id = :id AND d.overdueNotifiedAt IS NULL")
  int markOverdueNotified(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Lists the deals of one delegate.
   *
   * @param delegateUserId the delegate
   * @return their deals
   */
  List<PurchasingDeal> findByDelegateUserId(UUID delegateUserId);
}
