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
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every column of a {@code game_item} row the P4K import owns (REQ-P4K-005, REQ-P4K-007,
 * REQ-P4K-016, REQ-P4K-017, REQ-P4K-023), taken as one unit, so a merge moves the row's P4K
 * identity onto the survivor whole.
 *
 * @param p4kUuid the P4K GUID
 * @param nameKey the {@code global.ini} name key
 * @param nameDe the German name the P4K wrote
 * @param syncedAt the last P4K touch
 * @param deletedAt the sweep's stamp
 * @param hiddenAt an admin's hide through a review case
 * @param seenRunId the run that last matched the row
 * @param values the P4K's values of the attributes it leads
 */
record P4kItemParticipation(
    @NotNull UUID p4kUuid,
    @Nullable String nameKey,
    @Nullable String nameDe,
    @Nullable Instant syncedAt,
    @Nullable Instant deletedAt,
    @Nullable Instant hiddenAt,
    @Nullable UUID seenRunId,
    @NotNull P4kItemValues values) {

  /**
   * Takes the P4K columns off a row; the GUID is unique, so it leaves the row before it lands on
   * another.
   *
   * @param row the row that holds a P4K GUID
   * @param guid the row's P4K GUID
   * @return the removed columns
   */
  @NotNull
  static P4kItemParticipation detach(@NotNull GameItem row, @NotNull UUID guid) {
    final P4kItemParticipation taken =
        new P4kItemParticipation(
            guid,
            row.getNameKey(),
            row.getNameDe(),
            row.getP4kSyncedAt(),
            row.getP4kDeletedAt(),
            row.getP4kHiddenAt(),
            row.getP4kSeenRunId(),
            P4kItemValues.of(row));
    row.setP4kUuid(null);
    row.setNameKey(null);
    row.setNameDe(null);
    row.setP4kSyncedAt(null);
    row.setP4kDeletedAt(null);
    row.setP4kHiddenAt(null);
    row.setP4kSeenRunId(null);
    P4kItemValues.NONE.storeOn(row);
    return taken;
  }

  /**
   * Writes the columns onto a row; the P4K's values lead the row's displayed attributes.
   *
   * @param row the survivor
   */
  void attachTo(@NotNull GameItem row) {
    row.setP4kUuid(p4kUuid);
    row.setNameKey(nameKey);
    row.setNameDe(nameDe);
    row.setP4kSyncedAt(syncedAt);
    row.setP4kDeletedAt(deletedAt);
    row.setP4kHiddenAt(hiddenAt);
    row.setP4kSeenRunId(seenRunId);
    values.storeOn(row);
    values.leadOnto(row);
  }
}
