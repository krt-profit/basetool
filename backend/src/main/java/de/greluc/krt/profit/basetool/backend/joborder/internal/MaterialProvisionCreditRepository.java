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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads durable provision evidence without depending on current Inventory or claim rows. */
public interface MaterialProvisionCreditRepository
    extends JpaRepository<MaterialProvisionCredit, UUID> {
  /**
   * Reads globally processed physical intervals, including deleted orders and corrected facts.
   *
   * @param origins the physical origins of the current candidate stock
   * @return every interval that cannot receive another ordinary provision credit
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment("
          + "f.origin, f.fromQuanta, f.toQuanta) FROM MaterialProvisionCredit c JOIN c.fragments f"
          + " WHERE f.origin IN :origins")
  List<StockQuantityFragment> processedQuantity(@Param("origins") Collection<UUID> origins);

  /**
   * Reads all historical contribution credits of one readable order in one query.
   *
   * @param orderId the concrete order
   * @return its credits with physical evidence fetched
   */
  @EntityGraph(attributePaths = "fragments")
  List<MaterialProvisionCredit> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
