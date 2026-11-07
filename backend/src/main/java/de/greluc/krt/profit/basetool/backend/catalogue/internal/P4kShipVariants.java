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
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code ship_type_p4k_variant} in step with the catalogue's variant groups (REQ-P4K-032): an
 * APPLY writes every variant with a GUID, a class name the catalogue does not repeat and a {@code
 * variantOf}, and a complete ships kind deletes the variants it no longer lists. The stored rows
 * are read once and the rows to delete go in one statement.
 */
@Component
@RequiredArgsConstructor
class P4kShipVariants {

  private final ShipTypeP4kVariantRepository variantRepository;
  private final EntityManager entityManager;

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
    Map<UUID, ShipTypeP4kVariant> wanted = wanted(ships, context);
    Map<String, UUID> claimedBy = new HashMap<>();
    wanted.values().forEach(v -> claimedBy.put(folded(v.getClassName()), v.getVariantUuid()));
    Map<UUID, ShipTypeP4kVariant> stored = new HashMap<>();
    List<UUID> doomed = new ArrayList<>();
    for (ShipTypeP4kVariant row : variantRepository.findAll()) {
      UUID claimant = claimedBy.get(folded(row.getClassName()));
      boolean nameTaken = claimant != null && !claimant.equals(row.getVariantUuid());
      if (nameTaken || (complete && !wanted.containsKey(row.getVariantUuid()))) {
        entityManager.detach(row);
        doomed.add(row.getVariantUuid());
      } else {
        stored.put(row.getVariantUuid(), row);
      }
    }
    if (!doomed.isEmpty()) {
      variantRepository.deleteAllByIdInBatch(doomed);
    }
    wanted.forEach((guid, variant) -> {
      ShipTypeP4kVariant row = stored.get(guid);
      if (row == null) {
        entityManager.persist(variant);
      } else {
        row.setClassName(variant.getClassName());
        row.setBaseUuid(variant.getBaseUuid());
      }
    });
  }

  /**
   * Reads the variants the catalogue lists: a GUID, a class name it does not repeat and a {@code
   * variantOf} other than itself.
   *
   * @param ships the catalogue's ships
   * @param context the run
   * @return the variants by GUID, unsaved
   */
  @NotNull
  private static Map<UUID, ShipTypeP4kVariant> wanted(
      @NotNull List<P4kShipDto> ships, @NotNull P4kImportContext context) {
    Map<UUID, ShipTypeP4kVariant> wanted = new LinkedHashMap<>();
    for (P4kShipDto ship : ships) {
      UUID guid = ship.parsedGuid();
      UUID base = P4kGuids.parse(ship.variantOf());
      String className = StringNormalization.blankToNull(ship.className());
      if (guid != null
          && base != null
          && !base.equals(guid)
          && className != null
          && !context.isRepeatedClassName(P4kKind.SHIP, className)) {
        wanted.put(guid, new ShipTypeP4kVariant(guid, className.strip(), base));
      }
    }
    return wanted;
  }

  /**
   * Folds a class name for a comparison ignoring case, as the table's unique index does.
   *
   * @param className the class name
   * @return the lower-cased class name
   */
  @NotNull
  private static String folded(@NotNull String className) {
    return className.toLowerCase(Locale.ROOT);
  }
}
