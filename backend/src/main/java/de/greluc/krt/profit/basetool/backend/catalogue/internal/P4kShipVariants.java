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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code ship_type_p4k_variant} in step with the catalogue's variant groups (REQ-P4K-032): an
 * APPLY writes every variant with a GUID, a class name the catalogue does not repeat and a {@code
 * variantOf}, and a complete ships kind deletes the variants it no longer lists.
 */
@Component
@RequiredArgsConstructor
class P4kShipVariants {

  private final ShipTypeP4kVariantRepository variantRepository;

  /**
   * Writes the variant groups of an APPLY; a preview writes nothing.
   *
   * @param ships the catalogue's ships
   * @param complete whether the ships kind is complete
   * @param context the run
   */
  void write(@NotNull List<P4kShipDto> ships, boolean complete, @NotNull P4kImportContext context) {
    if (!context.isApply()) {
      return;
    }
    Set<UUID> written = new HashSet<>();
    for (P4kShipDto ship : ships) {
      UUID guid = ship.parsedGuid();
      UUID base = P4kGuids.parse(ship.variantOf());
      String className = StringNormalization.blankToNull(ship.className());
      if (guid == null
          || base == null
          || base.equals(guid)
          || className == null
          || context.isRepeated(P4kKind.SHIP, className)) {
        continue;
      }
      variantRepository.deleteClassNameHeldByOther(className.strip(), guid);
      variantRepository.save(new ShipTypeP4kVariant(guid, className.strip(), base));
      written.add(guid);
    }
    if (!complete) {
      return;
    }
    if (written.isEmpty()) {
      variantRepository.deleteAllInBatch();
    } else {
      variantRepository.deleteAllExcept(written);
    }
  }
}
