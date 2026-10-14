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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link SupplyDonation}. */
@Repository
public interface SupplyDonationRepository
    extends JpaRepository<SupplyDonation, UUID>, JpaSpecificationExecutor<SupplyDonation> {

  /**
   * Loads a donation under a pessimistic write lock, for a decision that must not race another.
   *
   * @param id the donation
   * @return the locked donation, or empty when unknown
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT d FROM SupplyDonation d WHERE d.id = :id")
  Optional<SupplyDonation> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Counts the donations in a state, for the business gauge.
   *
   * @param status the state
   * @return the number of donations in it
   */
  long countByStatus(DonationStatus status);

  /**
   * Finds every donation of one donor.
   *
   * @param donorUserId the donor
   * @return the donor's donations
   */
  List<SupplyDonation> findByDonorUserId(UUID donorUserId);

  /**
   * Detaches a donor from every donation, as the account is erased; the donations stay.
   *
   * @param donorUserId the departing donor
   * @return the number of donations changed
   */
  @Modifying
  @Query("UPDATE SupplyDonation d SET d.donorUserId = NULL WHERE d.donorUserId = :donorUserId")
  int detachDonor(@Param("donorUserId") UUID donorUserId);

  /**
   * Withdraws every pledge of a departing donor that was never received.
   *
   * @param donorUserId the departing donor
   * @return the number of pledges withdrawn
   */
  @Modifying
  @Query(
      "UPDATE SupplyDonation d SET d.status = de.greluc.krt.profit.basetool.backend.supply.api"
          + ".DonationStatus.WITHDRAWN WHERE d.donorUserId = :donorUserId"
          + " AND d.status = de.greluc.krt.profit.basetool.backend.supply.api"
          + ".DonationStatus.PLEDGED")
  int withdrawOpenPledgesOf(@Param("donorUserId") UUID donorUserId);

  /**
   * Moves the donations of one donor to another, as two accounts merge.
   *
   * @param from the account that disappears
   * @param to the account that stays
   * @return the number of donations moved
   */
  @Modifying
  @Query("UPDATE SupplyDonation d SET d.donorUserId = :to WHERE d.donorUserId = :from")
  int reassignDonor(@Param("from") UUID from, @Param("to") UUID to);

  /**
   * Sums, per receiving unit and calendar month, the received donations of the period.
   *
   * @param statuses the states that count as received
   * @param from the start of the period, inclusive
   * @param to the end of the period, exclusive
   * @return one row per unit and month
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.supply.internal.DonationStatRow(
          d.receivingOrgUnitId,
          CAST(EXTRACT(YEAR FROM d.receivedAt) AS integer),
          CAST(EXTRACT(MONTH FROM d.receivedAt) AS integer),
          COUNT(d),
          COUNT(DISTINCT d.donorUserId))
      FROM SupplyDonation d
      WHERE d.status IN :statuses AND d.receivedAt >= :from AND d.receivedAt < :to
      GROUP BY d.receivingOrgUnitId, EXTRACT(YEAR FROM d.receivedAt), EXTRACT(MONTH FROM d.receivedAt)
      ORDER BY EXTRACT(YEAR FROM d.receivedAt), EXTRACT(MONTH FROM d.receivedAt)
      """)
  List<DonationStatRow> statsByUnitAndMonth(
      @Param("statuses") Collection<DonationStatus> statuses,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * Sums, per receiving unit, month and catalogue entry, the received amounts of the period.
   *
   * @param statuses the states that count as received
   * @param from the start of the period, inclusive
   * @param to the end of the period, exclusive
   * @return one row per unit, month and material or item
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.supply.internal.DonationAmountRow(
          d.receivingOrgUnitId,
          CAST(EXTRACT(YEAR FROM d.receivedAt) AS integer),
          CAST(EXTRACT(MONTH FROM d.receivedAt) AS integer),
          COALESCE(m.name, g.name),
          SUM(COALESCE(d.receivedAmount, 0.0)))
      FROM SupplyDonation d LEFT JOIN d.material m LEFT JOIN d.gameItem g
      WHERE d.status IN :statuses AND d.receivedAt >= :from AND d.receivedAt < :to
      GROUP BY d.receivingOrgUnitId, EXTRACT(YEAR FROM d.receivedAt),
          EXTRACT(MONTH FROM d.receivedAt), COALESCE(m.name, g.name)
      ORDER BY EXTRACT(YEAR FROM d.receivedAt), EXTRACT(MONTH FROM d.receivedAt),
          COALESCE(m.name, g.name)
      """)
  List<DonationAmountRow> amountsByUnitMonthAndEntry(
      @Param("statuses") Collection<DonationStatus> statuses,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * Sums the received donations of one donor in a period, per catalogue entry.
   *
   * @param donorUserId the donor
   * @param statuses the states that count as received
   * @return one row per material or item
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.supply.internal.DonorAmountRow(
          d.donorUserId,
          COALESCE(m.name, g.name),
          COUNT(d),
          SUM(COALESCE(d.receivedAmount, 0.0)))
      FROM SupplyDonation d LEFT JOIN d.material m LEFT JOIN d.gameItem g
      WHERE d.status IN :statuses AND d.donorUserId = :donorUserId
      GROUP BY d.donorUserId, COALESCE(m.name, g.name)
      ORDER BY COALESCE(m.name, g.name)
      """)
  List<DonorAmountRow> amountsByDonor(
      @Param("donorUserId") UUID donorUserId,
      @Param("statuses") Collection<DonationStatus> statuses);

  /**
   * Sums the received donations of every identified donor, per catalogue entry.
   *
   * @param statuses the states that count as received
   * @return one row per donor and material or item
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.supply.internal.DonorAmountRow(
          d.donorUserId,
          COALESCE(m.name, g.name),
          COUNT(d),
          SUM(COALESCE(d.receivedAmount, 0.0)))
      FROM SupplyDonation d LEFT JOIN d.material m LEFT JOIN d.gameItem g
      WHERE d.status IN :statuses AND d.donorUserId IS NOT NULL
      GROUP BY d.donorUserId, COALESCE(m.name, g.name)
      ORDER BY d.donorUserId, COALESCE(m.name, g.name)
      """)
  List<DonorAmountRow> amountsPerDonor(@Param("statuses") Collection<DonationStatus> statuses);
}
