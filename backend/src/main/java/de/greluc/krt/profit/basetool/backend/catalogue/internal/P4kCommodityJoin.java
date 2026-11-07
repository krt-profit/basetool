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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBlueprintDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The n:1 join of a {@code ResourceType} to the commodity records that carry it as their {@code
 * subtypeGuid} (REQ-P4K-024, REQ-P4K-026), for one catalogue. A blueprint ingredient's {@code
 * resourceGuid} and a commodity's {@code refinedSubtypeGuid} reference a {@code ResourceType}; the
 * join yields zero, one or several commodity records, and only exactly one resolves.
 */
final class P4kCommodityJoin {

  /** The commodity records per {@code subtypeGuid}. */
  @NotNull private final Map<UUID, List<P4kCommodityDto>> bySubtype;

  /** The first record that references each {@code ResourceType}, in catalogue order. */
  @NotNull private final Map<UUID, P4kRecord> referencedBy;

  /**
   * Creates the join.
   *
   * @param bySubtype the commodity records per {@code subtypeGuid}
   * @param referencedBy the first referencing record per {@code ResourceType}
   */
  private P4kCommodityJoin(
      @NotNull Map<UUID, List<P4kCommodityDto>> bySubtype,
      @NotNull Map<UUID, P4kRecord> referencedBy) {
    this.bySubtype = bySubtype;
    this.referencedBy = referencedBy;
  }

  /**
   * Builds the join of a catalogue: every commodity by its {@code subtypeGuid}, and every {@code
   * ResourceType} a blueprint ingredient or a {@code refinedSubtypeGuid} references.
   *
   * @param catalogue the catalogue
   * @return the join
   */
  @NotNull
  static P4kCommodityJoin of(@NotNull P4kCatalogue catalogue) {
    Map<UUID, List<P4kCommodityDto>> bySubtype = new HashMap<>();
    Map<UUID, P4kRecord> referencedBy = new LinkedHashMap<>();
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      UUID subtype = P4kGuids.parse(commodity.subtypeGuid());
      if (subtype != null) {
        bySubtype.computeIfAbsent(subtype, _ -> new ArrayList<>()).add(commodity);
      }
    }
    for (P4kBlueprintDto blueprint : catalogue.blueprints()) {
      if (blueprint.ingredients() != null) {
        for (P4kBlueprintDto.Ingredient ingredient : blueprint.ingredients()) {
          reference(referencedBy, ingredient == null ? null : ingredient.resourceGuid(), blueprint);
        }
      }
    }
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      reference(referencedBy, commodity.refinedSubtypeGuid(), commodity);
    }
    return new P4kCommodityJoin(bySubtype, referencedBy);
  }

  /**
   * Remembers the first record that references a {@code ResourceType}.
   *
   * @param referencedBy the references so far
   * @param resourceGuid the referenced GUID text, or {@code null}
   * @param record the referencing record
   */
  private static void reference(
      @NotNull Map<UUID, P4kRecord> referencedBy,
      @Nullable String resourceGuid,
      @NotNull P4kRecord record) {
    UUID guid = P4kGuids.parse(resourceGuid);
    if (guid != null) {
      referencedBy.putIfAbsent(guid, record);
    }
  }

  /**
   * Returns the one commodity record a {@code ResourceType} joins.
   *
   * @param resourceGuid the {@code ResourceType} GUID text, or {@code null}
   * @return the record, or {@code null} when the join yields none or several
   */
  @Nullable
  P4kCommodityDto single(@Nullable String resourceGuid) {
    UUID guid = P4kGuids.parse(resourceGuid);
    List<P4kCommodityDto> joined = guid == null ? null : bySubtype.get(guid);
    return joined != null && joined.size() == 1 ? joined.getFirst() : null;
  }

  /**
   * Whether the seeding rule creates a commodity: a record of the run references its {@code
   * ResourceType}, and the join of that reference yields this commodity alone (REQ-P4K-026).
   *
   * @param commodity the commodity record
   * @return {@code true} when an unmatched record is created
   */
  boolean isCreatable(@NotNull P4kCommodityDto commodity) {
    UUID subtype = P4kGuids.parse(commodity.subtypeGuid());
    return subtype != null
        && referencedBy.containsKey(subtype)
        && commodity.equals(single(commodity.subtypeGuid()));
  }

  /**
   * Opens the review case of every referenced {@code ResourceType} the join resolves to no
   * commodity record or to several (REQ-P4K-024).
   *
   * @param context the run
   */
  void reviewUnresolved(@NotNull P4kImportContext context) {
    referencedBy.forEach((guid, record) -> {
      int joined = bySubtype.getOrDefault(guid, List.of()).size();
      if (joined != 1) {
        P4kReviewReason reason =
            joined == 0
                ? P4kReviewReason.RESOURCE_JOIN_NONE
                : P4kReviewReason.RESOURCE_JOIN_SEVERAL;
        context.getReport().review(P4kKind.COMMODITY, record, guid, reason, null);
      }
    });
  }
}
