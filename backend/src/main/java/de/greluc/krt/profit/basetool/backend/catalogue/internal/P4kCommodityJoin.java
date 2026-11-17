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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The n:1 join of a {@code ResourceType} to the commodities that carry it as their {@code
 * subtypeGuid} (REQ-P4K-024, REQ-P4K-026), for one catalogue. Records sharing the {@code
 * subtypeGuid}, the English name and the {@code typeGuid} are packagings of one commodity
 * (REQ-P4K-071): the record whose class name is the type's, ignoring case, else the lowest class
 * name, leads it, and the join yields only the leading record. A blueprint ingredient's {@code
 * resourceGuid} and a commodity's {@code refinedSubtypeGuid} reference a {@code ResourceType}; the
 * join yields zero, one or several commodities, and only exactly one resolves.
 */
final class P4kCommodityJoin {

  /** Orders the packagings of one commodity: the lowest class name, ignoring case, first. */
  private static final Comparator<P4kCommodityDto> BY_CLASS_NAME =
      Comparator.comparing(
          (P4kCommodityDto commodity) -> Objects.requireNonNullElse(commodity.className(), ""),
          String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

  /** The leading records per {@code subtypeGuid}. */
  @NotNull private final Map<UUID, List<P4kCommodityDto>> bySubtype;

  /** The leading record of every record, the record itself when it leads. */
  @NotNull private final Map<P4kCommodityDto, P4kCommodityDto> leaders;

  /** The leading records, in catalogue order. */
  @NotNull private final List<P4kCommodityDto> commodities;

  /** The first record that references each {@code ResourceType}, in catalogue order. */
  @NotNull private final Map<UUID, P4kRecord> referencedBy;

  /**
   * Creates the join.
   *
   * @param bySubtype the leading records per {@code subtypeGuid}
   * @param leaders the leading record of every record
   * @param commodities the leading records in catalogue order
   * @param referencedBy the first referencing record per {@code ResourceType}
   */
  private P4kCommodityJoin(
      @NotNull Map<UUID, List<P4kCommodityDto>> bySubtype,
      @NotNull Map<P4kCommodityDto, P4kCommodityDto> leaders,
      @NotNull List<P4kCommodityDto> commodities,
      @NotNull Map<UUID, P4kRecord> referencedBy) {
    this.bySubtype = bySubtype;
    this.leaders = leaders;
    this.commodities = commodities;
    this.referencedBy = referencedBy;
  }

  /**
   * Builds the join of a catalogue: every commodity's packagings, every leading record by its
   * {@code subtypeGuid}, and every {@code ResourceType} a blueprint ingredient or a {@code
   * refinedSubtypeGuid} references.
   *
   * @param catalogue the catalogue
   * @param types the catalogue's resource types, whose class names pick a commodity's leading
   *     record
   * @return the join
   */
  @NotNull
  static P4kCommodityJoin of(@NotNull P4kCatalogue catalogue, @NotNull P4kResourceTypes types) {
    Map<P4kCommodityDto, P4kCommodityDto> leaders = leaders(catalogue.commodities(), types);
    Map<UUID, List<P4kCommodityDto>> bySubtype = new HashMap<>();
    List<P4kCommodityDto> commodities = new ArrayList<>();
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      if (!commodity.equals(leaders.get(commodity))) {
        continue;
      }
      commodities.add(commodity);
      UUID subtype = P4kGuids.parse(commodity.subtypeGuid());
      if (subtype != null) {
        bySubtype.computeIfAbsent(subtype, _ -> new ArrayList<>()).add(commodity);
      }
    }
    Map<UUID, P4kRecord> referencedBy = new LinkedHashMap<>();
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
    return new P4kCommodityJoin(bySubtype, leaders, List.copyOf(commodities), referencedBy);
  }

  /**
   * Finds the leading record of every commodity record: the records of one {@code subtypeGuid},
   * folded English name and {@code typeGuid} are one commodity, led by the record whose class name
   * is the type's, else by the lowest class name; any other record leads itself.
   *
   * @param records the commodity records
   * @param types the resource types
   * @return the leading record per record
   */
  @NotNull
  private static Map<P4kCommodityDto, P4kCommodityDto> leaders(
      @NotNull List<P4kCommodityDto> records, @NotNull P4kResourceTypes types) {
    Map<PackagingKey, List<P4kCommodityDto>> packagings = new LinkedHashMap<>();
    Map<P4kCommodityDto, P4kCommodityDto> leaders = new HashMap<>();
    for (P4kCommodityDto record : records) {
      PackagingKey key = PackagingKey.of(record);
      if (key == null) {
        leaders.put(record, record);
      } else {
        packagings.computeIfAbsent(key, _ -> new ArrayList<>()).add(record);
      }
    }
    packagings.forEach((key, members) -> {
      P4kResourceTypes.Type type = types.type(key.subtype());
      String typeClass = type == null ? null : type.className();
      P4kCommodityDto leader =
          members.stream()
              .filter(member -> typeClass != null && typeClass.equalsIgnoreCase(member.className()))
              .findFirst()
              .orElseGet(() -> members.stream().min(BY_CLASS_NAME).orElseThrow());
      members.forEach(member -> leaders.put(member, leader));
    });
    return leaders;
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
   * Returns the leading records, one per commodity, in catalogue order.
   *
   * @return the leading records
   */
  @NotNull
  @Unmodifiable
  List<P4kCommodityDto> commodities() {
    return commodities;
  }

  /**
   * Whether a record leads its commodity; a record of the catalogue that is no packaging of another
   * always does.
   *
   * @param commodity a record of the catalogue
   * @return {@code false} for a packaging another record leads
   */
  boolean leads(@NotNull P4kCommodityDto commodity) {
    return commodity.equals(leaders.getOrDefault(commodity, commodity));
  }

  /**
   * Returns the record leading a record's commodity.
   *
   * @param commodity a record of the catalogue
   * @return the leading record, the record itself when it leads
   */
  @NotNull
  P4kCommodityDto leader(@NotNull P4kCommodityDto commodity) {
    return leaders.getOrDefault(commodity, commodity);
  }

  /**
   * Returns the one commodity a {@code ResourceType} joins.
   *
   * @param resourceGuid the {@code ResourceType} GUID text, or {@code null}
   * @return its leading record, or {@code null} when the join yields none or several commodities
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
   * commodity or to several (REQ-P4K-024).
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

  /**
   * What makes records packagings of one commodity.
   *
   * @param subtype the shared {@code subtypeGuid}
   * @param name the shared English name, folded
   * @param group the shared {@code typeGuid}, or {@code null}
   */
  private record PackagingKey(@NotNull UUID subtype, @NotNull String name, @Nullable UUID group) {

    /**
     * Returns the packaging key of a record.
     *
     * @param record the record
     * @return the key, or {@code null} when the record has no {@code subtypeGuid} or no English
     *     name
     */
    @Nullable
    static PackagingKey of(@NotNull P4kCommodityDto record) {
      UUID subtype = P4kGuids.parse(record.subtypeGuid());
      String name = P4kMatchKeys.fold(record.nameEn());
      if (subtype == null || name == null || name.isEmpty()) {
        return null;
      }
      return new PackagingKey(subtype, name, P4kGuids.parse(record.typeGuid()));
    }
  }
}
