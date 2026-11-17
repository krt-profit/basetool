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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The commodity rules of the P4K match. The SC Wiki keys a commodity by its {@code ResourceType},
 * so the Wiki key of a record is its {@code subtypeGuid} when that type joins this record alone
 * (REQ-P4K-070). A stored material belongs to the listed record whose GUID it holds, else to the
 * record whose Wiki key its SC Wiki UUID is, and is no class-name or name candidate of another
 * record (REQ-P4K-072). A material holding the GUID of a packaging another record leads follows the
 * leading record while no material holds that one (REQ-P4K-071).
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class P4kCommodityRules implements P4kMatchRules<Material> {

  /** The join, which decides the leading records and the single join of a type. */
  @NotNull private final P4kCommodityJoin join;

  /** The catalogue's commodity records by GUID; a repeated GUID keeps its first record. */
  @NotNull private final Map<UUID, P4kCommodityDto> records;

  /** The leading record's GUID per Wiki key that differs from the GUID. */
  @NotNull private final Map<UUID, UUID> keyed;

  /** The P4K GUIDs the stored materials hold when the run starts. */
  @NotNull private final Set<UUID> held;

  /**
   * Indexes the catalogue's commodity records and the GUIDs the stored materials hold.
   *
   * @param commodities the catalogue's commodity records
   * @param join the run's join
   * @param rows the stored materials
   * @return the commodity rules over them
   */
  @NotNull
  static P4kCommodityRules of(
      @NotNull List<P4kCommodityDto> commodities,
      @NotNull P4kCommodityJoin join,
      @NotNull Collection<Material> rows) {
    Map<UUID, P4kCommodityDto> records = new HashMap<>();
    for (P4kCommodityDto commodity : commodities) {
      UUID guid = commodity.parsedGuid();
      if (guid != null) {
        records.putIfAbsent(guid, commodity);
      }
    }
    Map<UUID, UUID> keyed = new HashMap<>();
    for (P4kCommodityDto commodity : join.commodities()) {
      UUID guid = commodity.parsedGuid();
      UUID key = guid == null ? null : subtypeKey(join, commodity);
      if (key != null) {
        keyed.putIfAbsent(key, guid);
      }
    }
    Set<UUID> held = new HashSet<>();
    for (Material row : rows) {
      if (row.getP4kUuid() != null) {
        held.add(row.getP4kUuid());
      }
    }
    return new P4kCommodityRules(join, records, keyed, held);
  }

  /**
   * Returns the {@code subtypeGuid} of a record whose type joins it alone.
   *
   * @param join the join
   * @param commodity the record
   * @return the {@code subtypeGuid}, or {@code null} when the record has none or its type joins
   *     another commodity too
   */
  @Nullable
  private static UUID subtypeKey(
      @NotNull P4kCommodityJoin join, @NotNull P4kCommodityDto commodity) {
    return commodity.equals(join.single(commodity.subtypeGuid()))
        ? P4kGuids.parse(commodity.subtypeGuid())
        : null;
  }

  /**
   * Returns the Wiki key of a commodity: its {@code subtypeGuid} when that type joins it alone.
   *
   * @param guid the record's GUID
   * @return the {@code subtypeGuid}, else the GUID
   */
  @Override
  @NotNull
  public UUID wikiKey(@NotNull UUID guid) {
    P4kCommodityDto record = records.get(guid);
    UUID key = record == null ? null : subtypeKey(join, record);
    return key == null ? guid : key;
  }

  /**
   * Tells whether a found material may stand for the record of a GUID: it belongs to no listed
   * record or to this one.
   *
   * @param guid the record's GUID
   * @param row the material a name found
   * @return {@code false} when the material belongs to another listed record
   */
  @Override
  public boolean isCandidate(@NotNull UUID guid, @NotNull Material row) {
    UUID owner = owner(row);
    return owner == null || owner.equals(guid);
  }

  /**
   * Returns the leading record of the packaging whose GUID a material holds, when no material holds
   * the leading record's GUID.
   *
   * @param row a stored material
   * @return the leading record's GUID, or {@code null} when the material keeps the GUID it holds
   */
  @Override
  @Nullable
  public UUID followedGuid(@NotNull Material row) {
    P4kCommodityDto record = row.getP4kUuid() == null ? null : records.get(row.getP4kUuid());
    if (record == null || join.leads(record)) {
      return null;
    }
    UUID leader = join.leader(record).parsedGuid();
    return leader == null || held.contains(leader) ? null : leader;
  }

  /**
   * Returns the listed record a material belongs to.
   *
   * @param row a stored material
   * @return the GUID it follows, else the listed GUID it holds, else the record whose Wiki key its
   *     SC Wiki UUID is, else {@code null}
   */
  @Nullable
  private UUID owner(@NotNull Material row) {
    UUID followed = followedGuid(row);
    if (followed != null) {
      return followed;
    }
    UUID heldGuid = row.getP4kUuid();
    if (heldGuid != null && records.containsKey(heldGuid)) {
      return heldGuid;
    }
    UUID wikiUuid = row.getScwikiUuid();
    return wikiUuid == null ? null : keyed.get(wikiUuid);
  }
}
