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

/** Reports the materials supply needs and donations reference (REQ-P4K-075). */
@Component
@RequiredArgsConstructor
public class SupplyMaterialReferences implements MaterialReferenceParticipant {

  /** The materials among {@code :ids} a need or a donation references. */
  private static final String REFERENCED =
      "SELECT n.material.id FROM SupplyNeed n WHERE n.material.id IN :ids"
          + " UNION SELECT d.material.id FROM SupplyDonation d WHERE d.material.id IN :ids";

  private final EntityManager entityManager;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedMaterials(@NotNull Collection<UUID> materialIds) {
    return MaterialReferenceParticipant.referenced(entityManager, REFERENCED, materialIds);
  }
}
