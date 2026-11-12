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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Job Order Material. */
@Repository
public interface JobOrderMaterialRepository extends JpaRepository<JobOrderMaterial, UUID> {
  /**
   * Reads current scalar demand facts independently of managed collection snapshots.
   *
   * @param orderId the material order
   * @return requirements in stable identity order
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialHandoverRequirement(
          m.id, m.version, m.material.id, q.id, q.code, q.minQuality, m.amount)
      FROM JobOrderMaterial m JOIN m.qualityTier q
      WHERE m.jobOrder.id = :orderId ORDER BY m.id
      """)
  List<MaterialHandoverRequirement> handoverRequirements(@Param("orderId") @NotNull UUID orderId);
}
