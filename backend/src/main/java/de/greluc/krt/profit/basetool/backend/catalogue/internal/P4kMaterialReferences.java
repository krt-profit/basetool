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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Tells the P4K import which materials are referenced and by whom (REQ-P4K-075…078): a member row
 * of another module, answered through {@link MaterialReferenceParticipant}, or a catalogue row
 * ({@link CatalogueMaterialReferences}).
 */
@Component
@RequiredArgsConstructor
class P4kMaterialReferences {

  private final List<MaterialReferenceParticipant> participants;
  private final CatalogueMaterialReferences catalogue;

  /**
   * Tells which of the given materials a member row of another module references.
   *
   * @param materialIds the materials asked about
   * @return the referenced ones among them
   */
  @NotNull
  Set<UUID> byMembers(@NotNull Collection<UUID> materialIds) {
    Set<UUID> referenced = new HashSet<>();
    if (!materialIds.isEmpty()) {
      participants.forEach(
          participant -> referenced.addAll(participant.referencedMaterials(materialIds)));
    }
    return referenced;
  }

  /**
   * Tells which of the given materials a catalogue row references.
   *
   * @param materialIds the materials asked about
   * @return the referenced ones among them
   */
  @NotNull
  Set<UUID> byCatalogue(@NotNull Collection<UUID> materialIds) {
    return catalogue.referencedMaterials(materialIds);
  }

  /**
   * Tells which of the given materials any row references.
   *
   * @param materialIds the materials asked about
   * @return the materials a member or a catalogue row references
   */
  @NotNull
  Set<UUID> byAnyone(@NotNull Collection<UUID> materialIds) {
    Set<UUID> referenced = byMembers(materialIds);
    referenced.addAll(byCatalogue(materialIds));
    return referenced;
  }
}
