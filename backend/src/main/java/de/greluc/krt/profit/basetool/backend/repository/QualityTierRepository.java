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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.QualityTier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for the {@link QualityTier} catalogue. */
@Repository
public interface QualityTierRepository extends JpaRepository<QualityTier, UUID> {

  /**
   * Returns the whole catalogue, active and inactive, by ascending floor.
   *
   * @return every tier, never {@code null}
   */
  List<QualityTier> findAllByOrderByMinQualityAsc();

  /**
   * Looks a tier up by its code.
   *
   * @param code the upper-case code
   * @return the tier, or empty
   */
  Optional<QualityTier> findByCode(String code);

  /**
   * Looks a tier up by its floor.
   *
   * @param minQuality the floor
   * @return the tier, or empty
   */
  Optional<QualityTier> findByMinQuality(int minQuality);

  /**
   * Counts every requirement line, item material and claim that references the tier.
   *
   * @param tierId the tier
   * @return the number of references; {@code 0} means the tier may be deleted
   */
  @Query(
      """
      SELECT (SELECT COUNT(m) FROM JobOrderMaterial m WHERE m.qualityTier.id = :tierId)
           + (SELECT COUNT(im) FROM JobOrderItemMaterial im WHERE im.qualityTier.id = :tierId)
           + (SELECT COUNT(c) FROM MaterialClaim c WHERE c.qualityTier.id = :tierId)
      FROM QualityTier t WHERE t.id = :tierId
      """)
  Long countReferences(@Param("tierId") UUID tierId);
}
