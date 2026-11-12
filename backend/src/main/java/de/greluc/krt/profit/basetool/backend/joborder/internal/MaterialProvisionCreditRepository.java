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

  /**
   * Reads other receipts' immutable once-credit evidence for a truthful historical relabel.
   *
   * @param origins physical origins of this receipt
   * @param excluded the receipt whose own retained evidence may be deliberately corrected
   * @return processed intervals unavailable to a second receipt
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment("
          + "f.origin, f.fromQuanta, f.toQuanta) FROM MaterialProvisionCredit c JOIN c.fragments f"
          + " WHERE f.origin IN :origins AND c.id <> :excluded")
  List<StockQuantityFragment> processedQuantityExcept(
      @Param("origins") Collection<UUID> origins, @Param("excluded") UUID excluded);

  /**
   * Locates every retained receipt intersecting a false physical quantity.
   *
   * @param quantity exact removed physical intervals as JSON
   * @return affected receipt identities without disclosing their facts
   */
  @Query(
      value =
          "SELECT DISTINCT c.id FROM material_provision_credit c CROSS JOIN"
              + " jsonb_array_elements(CAST(:quantity AS jsonb)) wanted WHERE c.quantity_fragments"
              + " @> jsonb_build_array(jsonb_build_object('origin', wanted->>'origin')) AND EXISTS"
              + " (SELECT 1 FROM jsonb_array_elements(c.quantity_fragments) actual WHERE"
              + " actual->>'origin' = wanted->>'origin' AND (actual->>'fromQuanta')::bigint <"
              + " (wanted->>'toQuanta')::bigint AND (wanted->>'fromQuanta')::bigint <"
              + " (actual->>'toQuanta')::bigint)",
      nativeQuery = true)
  List<UUID> findIntersectingReceiptIds(@Param("quantity") String quantity);

  /**
   * Batch-loads the preplanned receipt facts and once-only evidence.
   *
   * @param ids affected receipts
   * @return receipts in deterministic identity order
   */
  @EntityGraph(attributePaths = "fragments")
  List<MaterialProvisionCredit> findByIdInOrderById(Collection<UUID> ids);

  /**
   * Reads the database version independently of a preplanned managed instance.
   *
   * @param id receipt identity
   * @return current version, if retained
   */
  @Query(value = "SELECT version FROM material_provision_credit WHERE id = :id", nativeQuery = true)
  java.util.Optional<Long> currentVersion(@Param("id") UUID id);

  /**
   * Batch-reads current versions without resolving managed entity prereads.
   *
   * @param ids retained fact identities
   * @return current database identities and versions
   */
  @Query(
      value = "SELECT id, version FROM material_provision_credit WHERE id IN :ids",
      nativeQuery = true)
  List<MaterialFactVersion> currentVersions(@Param("ids") Collection<UUID> ids);
}
