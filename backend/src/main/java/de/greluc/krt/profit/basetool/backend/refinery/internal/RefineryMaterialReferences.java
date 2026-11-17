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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

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

/** Reports the materials refinery orders put in or take out (REQ-P4K-075). */
@Component
@RequiredArgsConstructor
public class RefineryMaterialReferences implements MaterialReferenceParticipant {

  /** The materials among {@code :ids} a refinery good names as its input or its output. */
  private static final String REFERENCED =
      "SELECT g.inputMaterial.id FROM RefineryGood g WHERE g.inputMaterial.id IN :ids"
          + " UNION SELECT g.outputMaterial.id FROM RefineryGood g"
          + " WHERE g.outputMaterial.id IN :ids";

  private final EntityManager entityManager;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedMaterials(@NotNull Collection<UUID> materialIds) {
    return MaterialReferenceParticipant.referenced(entityManager, REFERENCED, materialIds);
  }
}
