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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kResourceGroupDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kResourceTypeDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code ResourceType} records of one catalogue (REQ-P4K-070, REQ-P4K-073): each with its class
 * name and the one resource group that lists it, found by GUID and by its English name reduced to
 * {@code [a-z0-9]}. A type the {@code resourceTypes} kind does not list, as in a catalogue without
 * that kind, is known by the {@code typeGuid} of the commodity records that carry it as their
 * {@code subtypeGuid}. A placeholder group is a resource group whose English name is the
 * placeholder ({@code name.en} absent) and that no commodity record names as its {@code typeGuid},
 * such as {@code HeatPlaceholder} or {@code CleanAir}; a type it lists stands for no meaningful
 * group.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class P4kResourceTypes {

  /** Every character a reduced name drops. */
  private static final Pattern NOT_REDUCED = Pattern.compile("[^a-z0-9]");

  /** The types by GUID. */
  @NotNull private final Map<UUID, Type> byGuid;

  /** The listed types by reduced English name. */
  @NotNull private final Map<String, List<Type>> byName;

  /**
   * One {@code ResourceType}.
   *
   * @param guid its GUID
   * @param className its Record name, or {@code null} when the catalogue does not list the type
   * @param groupGuid the GUID of the one resource group that lists it, or {@code null} when none or
   *     several do
   * @param placeholder whether that group is a placeholder group, so the type stands for no group a
   *     material may take
   */
  record Type(
      @NotNull UUID guid,
      @Nullable String className,
      @Nullable UUID groupGuid,
      boolean placeholder) {}

  /**
   * Indexes a catalogue's resource types, and the types its commodity records name that the kind
   * does not list.
   *
   * @param catalogue the catalogue
   * @return the index
   */
  @NotNull
  static P4kResourceTypes of(@NotNull P4kCatalogue catalogue) {
    Set<UUID> placeholders = placeholderGroups(catalogue);
    Map<UUID, Type> byGuid = new HashMap<>();
    Map<String, List<Type>> byName = new HashMap<>();
    for (P4kResourceTypeDto record : catalogue.resourceTypes()) {
      UUID guid = record.parsedGuid();
      if (guid == null || byGuid.containsKey(guid)) {
        continue;
      }
      List<String> groups = record.groupGuids();
      UUID group = groups != null && groups.size() == 1 ? P4kGuids.parse(groups.getFirst()) : null;
      Type type =
          new Type(guid, record.className(), group, group != null && placeholders.contains(group));
      byGuid.put(guid, type);
      String name = reduce(record.nameEn());
      if (name != null) {
        byName.computeIfAbsent(name, _ -> new ArrayList<>()).add(type);
      }
    }
    Map<UUID, Type> named = new HashMap<>();
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      UUID subtype = P4kGuids.parse(commodity.subtypeGuid());
      if (subtype != null && !byGuid.containsKey(subtype)) {
        UUID group = P4kGuids.parse(commodity.typeGuid());
        named.merge(
            subtype,
            new Type(subtype, null, group, false),
            (first, next) ->
                Objects.equals(first.groupGuid(), next.groupGuid())
                    ? first
                    : new Type(subtype, null, null, false));
      }
    }
    byGuid.putAll(named);
    return new P4kResourceTypes(byGuid, byName);
  }

  /**
   * Collects the placeholder groups of a catalogue: no English name, and no commodity record names
   * them as {@code typeGuid}.
   *
   * @param catalogue the catalogue
   * @return their GUIDs
   */
  @NotNull
  private static Set<UUID> placeholderGroups(@NotNull P4kCatalogue catalogue) {
    Set<UUID> used = new HashSet<>();
    for (P4kCommodityDto commodity : catalogue.commodities()) {
      UUID group = P4kGuids.parse(commodity.typeGuid());
      if (group != null) {
        used.add(group);
      }
    }
    Set<UUID> placeholders = new HashSet<>();
    for (P4kResourceGroupDto group : catalogue.resourceGroups()) {
      UUID guid = group.parsedGuid();
      String name = group.nameEn();
      if (guid != null && (name == null || name.isBlank()) && !used.contains(guid)) {
        placeholders.add(guid);
      }
    }
    return placeholders;
  }

  /**
   * Reduces a name to its lower-case letters and digits, the key a material's name meets a type's
   * English name by.
   *
   * @param name the name, or {@code null}
   * @return the reduced name, or {@code null} when nothing is left
   */
  @Nullable
  static String reduce(@Nullable String name) {
    if (name == null) {
      return null;
    }
    String reduced = NOT_REDUCED.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
    return reduced.isEmpty() ? null : reduced;
  }

  /**
   * Returns a type by GUID.
   *
   * @param guid the GUID, or {@code null}
   * @return the type, or {@code null} when the catalogue knows none of that GUID
   */
  @Nullable
  Type type(@Nullable UUID guid) {
    return guid == null ? null : byGuid.get(guid);
  }

  /**
   * Returns the type a material stands for: the type of its SC Wiki UUID, else of its stored {@code
   * subtypeGuid} (G1), else the only listed type whose reduced English name equals the material's
   * reduced name (G2).
   *
   * @param material the material
   * @return the type, or {@code null} when the catalogue knows none for it
   */
  @Nullable
  Type typeOf(@NotNull Material material) {
    Type byIdentity = type(material.getScwikiUuid());
    if (byIdentity == null) {
      byIdentity = type(material.getP4kSubtypeUuid());
    }
    if (byIdentity != null) {
      return byIdentity;
    }
    String name = reduce(material.getName());
    List<Type> named = name == null ? List.of() : byName.getOrDefault(name, List.of());
    return named.size() == 1 ? named.getFirst() : null;
  }
}
