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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceParticipant;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports the materials job orders reference (REQ-P4K-075): material lines, item-order material
 * lines, handovers, claims and provision credits.
 */
@Component
@RequiredArgsConstructor
public class JobOrderMaterialReferences implements MaterialReferenceParticipant {

  /** The materials among {@code :ids} a job-order row references. */
  private static final String REFERENCED =
      "SELECT m.material.id FROM JobOrderMaterial m WHERE m.material.id IN :ids"
          + " UNION SELECT im.material.id FROM JobOrderItemMaterial im"
          + " WHERE im.material.id IN :ids"
          + " UNION SELECT h.material.id FROM JobOrderHandoverItem h WHERE h.material.id IN :ids"
          + " UNION SELECT c.material.id FROM MaterialClaim c WHERE c.material.id IN :ids"
          + " UNION SELECT p.materialId FROM MaterialProvisionCredit p WHERE p.materialId IN :ids";

  private final EntityManager entityManager;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedMaterials(@NotNull Collection<UUID> materialIds) {
    return MaterialReferenceParticipant.referenced(entityManager, REFERENCED, materialIds);
  }
}
