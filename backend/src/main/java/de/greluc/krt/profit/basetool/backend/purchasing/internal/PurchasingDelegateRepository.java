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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence of the delegate appointments (REQ-PUR-003). */
@Repository
public interface PurchasingDelegateRepository extends JpaRepository<PurchasingDelegate, UUID> {

  /**
   * Finds the member's appointment that is not revoked, whether or not it has expired.
   *
   * @param userId the member
   * @return the open appointment, or empty
   */
  Optional<PurchasingDelegate> findFirstByUserIdAndRevokedAtIsNull(UUID userId);

  /**
   * Lists every appointment, newest first.
   *
   * @return all appointments
   */
  List<PurchasingDelegate> findAllByOrderByAppointedAtDesc();

  /**
   * Lists the appointments of one member, newest first.
   *
   * @param userId the member
   * @return their appointments
   */
  List<PurchasingDelegate> findByUserIdOrderByAppointedAtDesc(UUID userId);

  /**
   * Lists the members whose appointment confers the capability at a moment.
   *
   * @param now the moment to judge
   * @return the ids of the active delegates
   */
  @Query(
      "SELECT d.userId FROM PurchasingDelegate d WHERE d.revokedAt IS NULL AND d.userId IS NOT NULL"
          + " AND (d.validUntil IS NULL OR d.validUntil > :now)")
  List<UUID> findActiveUserIds(@Param("now") Instant now);

  /**
   * Counts the appointments that confer the capability at a moment.
   *
   * @param now the moment to judge
   * @return the number of active delegates
   */
  @Query(
      "SELECT COUNT(d) FROM PurchasingDelegate d WHERE d.revokedAt IS NULL AND d.userId IS NOT NULL"
          + " AND (d.validUntil IS NULL OR d.validUntil > :now)")
  long countActive(@Param("now") Instant now);
}
