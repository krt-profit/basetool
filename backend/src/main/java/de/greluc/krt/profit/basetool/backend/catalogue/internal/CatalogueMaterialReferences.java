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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

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
 * Reports the catalogue's own material references (REQ-P4K-075, REQ-P4K-076): recipe ingredients
 * and dismantle returns, UEX refinery yields and terminal prices, external name aliases, a material
 * that refines into it, and the id of a material merged into it (REQ-DATA-046). Each of them would
 * refuse the material's deletion.
 */
@Component
@RequiredArgsConstructor
class CatalogueMaterialReferences {

  /** The materials among {@code :ids} a catalogue row references. */
  private static final String REFERENCED =
      "SELECT i.material.id FROM BlueprintIngredient i WHERE i.material.id IN :ids"
          + " UNION SELECT r.material.id FROM BlueprintDismantleReturn r"
          + " WHERE r.material.id IN :ids"
          + " UNION SELECT y.material.id FROM RefineryYield y WHERE y.material.id IN :ids"
          + " UNION SELECT p.material.id FROM MaterialPrice p WHERE p.material.id IN :ids"
          + " UNION SELECT a.material.id FROM MaterialExternalAlias a WHERE a.material.id IN :ids"
          + " UNION SELECT m.refinedMaterial.id FROM Material m"
          + " WHERE m.refinedMaterial.id IN :ids"
          + " UNION SELECT r.canonicalMaterial.id FROM MaterialRedirect r"
          + " WHERE r.canonicalMaterial.id IN :ids";

  private final EntityManager entityManager;

  /**
   * Tells which of the given materials a catalogue row references.
   *
   * @param materialIds the materials asked about
   * @return the referenced ones among them, empty when none is
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  Set<UUID> referencedMaterials(@NotNull Collection<UUID> materialIds) {
    return MaterialReferenceParticipant.referenced(entityManager, REFERENCED, materialIds);
  }
}
