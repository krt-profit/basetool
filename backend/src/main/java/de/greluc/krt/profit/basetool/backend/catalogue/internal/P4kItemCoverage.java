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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The item rules of the P4K match. A stored item belongs to the listed record its own facts name —
 * its SC Wiki UUID, else its class name — when that record is free and the facts do not also name
 * the GUID it holds; otherwise to the listed GUID it holds (REQ-P4K-055). A row belonging to
 * another record is never a class-name or name candidate, and it covers a record of the same name,
 * type, sub-type and size, as a copy of an item ({@code _tow}, {@code _LowPoly}) is (REQ-P4K-054).
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class P4kItemCoverage implements P4kMatchRules<GameItem> {

  /** The catalogue's items by GUID; a repeated GUID keeps its first record. */
  @NotNull private final Map<UUID, P4kItemDto> records;

  /** The catalogue's items by folded class name. */
  @NotNull private final Map<String, List<P4kItemDto>> byClass;

  /** The P4K GUIDs the stored items hold when the run starts. */
  @NotNull private final Set<UUID> held;

  /**
   * Indexes the catalogue's items and the GUIDs the stored items hold.
   *
   * @param items the catalogue's items
   * @param rows the stored items
   * @return the item rules over them
   */
  @NotNull
  static P4kItemCoverage of(@NotNull List<P4kItemDto> items, @NotNull Collection<GameItem> rows) {
    Map<UUID, P4kItemDto> records = new HashMap<>();
    Map<String, List<P4kItemDto>> byClass = new HashMap<>();
    for (P4kItemDto item : items) {
      UUID guid = item.parsedGuid();
      if (guid != null) {
        records.putIfAbsent(guid, item);
      }
      String className = P4kMatchKeys.fold(item.className());
      if (className != null && !className.isEmpty()) {
        byClass.computeIfAbsent(className, _ -> new ArrayList<>()).add(item);
      }
    }
    Set<UUID> held = new HashSet<>();
    for (GameItem row : rows) {
      if (row.getP4kUuid() != null) {
        held.add(row.getP4kUuid());
      }
    }
    return new P4kItemCoverage(records, byClass, held);
  }

  /**
   * Tells whether a found row may stand for the item of a GUID: it belongs to no listed item or to
   * this one.
   *
   * @param guid the item's GUID
   * @param row the stored item a class name or name found
   * @return {@code false} when the row belongs to another listed item
   */
  @Override
  public boolean isCandidate(@NotNull UUID guid, @NotNull GameItem row) {
    UUID owner = owner(row);
    return owner == null || owner.equals(guid);
  }

  /**
   * Tells whether a found row already stands for the item of a GUID it does not belong to.
   *
   * @param guid the item's GUID
   * @param row the stored item a class name or name found
   * @return {@code true} when the row belongs to another listed item of the same name, type,
   *     sub-type and size
   */
  @Override
  public boolean covers(@NotNull UUID guid, @NotNull GameItem row) {
    UUID owner = owner(row);
    if (owner == null || owner.equals(guid)) {
      return false;
    }
    P4kItemDto ownItem = records.get(owner);
    P4kItemDto item = records.get(guid);
    return ownItem != null
        && item != null
        && Objects.equals(P4kMatchKeys.fold(ownItem.nameEn()), P4kMatchKeys.fold(item.nameEn()))
        && Objects.equals(P4kMatchKeys.fold(ownItem.type()), P4kMatchKeys.fold(item.type()))
        && Objects.equals(P4kMatchKeys.fold(ownItem.subType()), P4kMatchKeys.fold(item.subType()))
        && Objects.equals(ownItem.size(), item.size());
  }

  /**
   * Returns the GUID of the record a row's SC Wiki UUID or class name names in place of the GUID it
   * holds: that record is listed and held by no stored item, and the row's facts do not name the
   * listed record of the GUID it holds.
   *
   * @param row a stored item
   * @return the GUID the row follows, or {@code null} when it keeps the one it holds
   */
  @Override
  @Nullable
  public UUID followedGuid(@NotNull GameItem row) {
    UUID heldGuid = row.getP4kUuid();
    P4kItemDto named = heldGuid == null ? null : namedRecord(row);
    UUID guid = named == null ? null : named.parsedGuid();
    if (guid == null || guid.equals(heldGuid) || held.contains(guid)) {
      return null;
    }
    P4kItemDto heldRecord = records.get(heldGuid);
    return heldRecord != null && names(row, heldRecord) ? null : guid;
  }

  /**
   * Returns the listed item a row belongs to.
   *
   * @param row a stored item
   * @return the GUID it follows, else the listed GUID it holds, else {@code null}
   */
  @Nullable
  private UUID owner(@NotNull GameItem row) {
    UUID followed = followedGuid(row);
    if (followed != null) {
      return followed;
    }
    UUID heldGuid = row.getP4kUuid();
    return heldGuid != null && records.containsKey(heldGuid) ? heldGuid : null;
  }

  /**
   * Returns the listed record a row's own facts name: the record of its SC Wiki UUID, else the only
   * record of its class name.
   *
   * @param row a stored item
   * @return the record, or {@code null} when the facts name none or several
   */
  @Nullable
  private P4kItemDto namedRecord(@NotNull GameItem row) {
    UUID wikiUuid = row.getExternalUuid();
    P4kItemDto byWiki = wikiUuid == null ? null : records.get(wikiUuid);
    if (byWiki != null) {
      return byWiki;
    }
    String className = P4kMatchKeys.fold(row.getClassName());
    List<P4kItemDto> sameClass =
        className == null ? List.of() : byClass.getOrDefault(className, List.of());
    return sameClass.size() == 1 ? sameClass.getFirst() : null;
  }

  /**
   * Tells whether a row's SC Wiki UUID or class name names a record.
   *
   * @param row a stored item
   * @param record a listed record
   * @return {@code true} when either fact is the record's
   */
  private static boolean names(@NotNull GameItem row, @NotNull P4kItemDto record) {
    String className = P4kMatchKeys.fold(row.getClassName());
    return Objects.equals(row.getExternalUuid(), record.parsedGuid())
        || (className != null
            && !className.isEmpty()
            && className.equals(P4kMatchKeys.fold(record.className())));
  }
}
