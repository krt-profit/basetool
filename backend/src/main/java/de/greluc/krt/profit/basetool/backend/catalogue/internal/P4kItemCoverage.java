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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which stored item a class-name or name step may give a catalogue item of another GUID
 * (REQ-P4K-054): a row holding the GUID of another listed item is never a candidate, and it covers
 * the item when both records carry the same name, type, sub-type and size, as a copy of an item
 * does ({@code _tow}, {@code _LowPoly}); such an item needs neither a row of its own nor a case.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class P4kItemCoverage implements P4kMatchRules<GameItem> {

  /** The catalogue's items by GUID; a repeated GUID keeps its first record. */
  @NotNull private final Map<UUID, P4kItemDto> records;

  /**
   * Indexes the catalogue's items.
   *
   * @param items the catalogue's items
   * @return the coverage rule over them
   */
  @NotNull
  static P4kItemCoverage of(@NotNull List<P4kItemDto> items) {
    Map<UUID, P4kItemDto> records = new HashMap<>();
    for (P4kItemDto item : items) {
      UUID guid = item.parsedGuid();
      if (guid != null) {
        records.putIfAbsent(guid, item);
      }
    }
    return new P4kItemCoverage(records);
  }

  /**
   * Tells whether a found row may stand for the item of a GUID: it holds no GUID, this GUID, or a
   * GUID the catalogue no longer lists.
   *
   * @param guid the item's GUID
   * @param row the stored item a class name or name found
   * @return {@code false} when the row holds the GUID of another listed item
   */
  @Override
  public boolean isCandidate(@NotNull UUID guid, @NotNull GameItem row) {
    return heldRecord(guid, row) == null;
  }

  /**
   * Tells whether a found row already stands for the item of a GUID it does not hold.
   *
   * @param guid the item's GUID
   * @param row the stored item a class name or name found
   * @return {@code true} when the row holds another listed item of the same name, type, sub-type
   *     and size
   */
  @Override
  public boolean covers(@NotNull UUID guid, @NotNull GameItem row) {
    P4kItemDto held = heldRecord(guid, row);
    P4kItemDto item = records.get(guid);
    return held != null
        && item != null
        && Objects.equals(P4kMatchKeys.fold(held.nameEn()), P4kMatchKeys.fold(item.nameEn()))
        && Objects.equals(P4kMatchKeys.fold(held.type()), P4kMatchKeys.fold(item.type()))
        && Objects.equals(P4kMatchKeys.fold(held.subType()), P4kMatchKeys.fold(item.subType()))
        && Objects.equals(held.size(), item.size());
  }

  /**
   * Returns the listed item whose GUID a row holds instead of the given one.
   *
   * @param guid the item's GUID
   * @param row the stored item
   * @return the other item, or {@code null} when the row holds no other listed GUID
   */
  @Nullable
  private P4kItemDto heldRecord(@NotNull UUID guid, @NotNull GameItem row) {
    UUID held = row.getP4kUuid();
    return held == null || held.equals(guid) ? null : records.get(held);
  }
}
