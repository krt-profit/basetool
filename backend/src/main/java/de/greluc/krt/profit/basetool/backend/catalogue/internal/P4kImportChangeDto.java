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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One row of a P4K change report as the admin page reads it (REQ-P4K-014, REQ-P4K-015), for an
 * applied run and for a preview alike.
 *
 * @param kind the kind of the record
 * @param changeType the type of the change
 * @param guid the record's P4K GUID, {@code null} for a skipped sweep
 * @param fieldName the column, the review reason or the sweep outcome, or {@code null}
 * @param oldValue the value before the run, or {@code null}
 * @param newValue the value the run read, or {@code null}
 * @param className the record's class name, or {@code null}
 * @param nameEn the record's English short name, or {@code null}
 * @param recordPath the record's path in the archive, or {@code null}
 * @param stillLiveElsewhere whether a vanished row is still live in UEX or the SC Wiki
 */
public record P4kImportChangeDto(
    @NotNull P4kKind kind,
    @NotNull P4kChangeType changeType,
    @Nullable UUID guid,
    @Nullable String fieldName,
    @Nullable String oldValue,
    @Nullable String newValue,
    @Nullable String className,
    @Nullable String nameEn,
    @Nullable String recordPath,
    boolean stillLiveElsewhere) {

  /**
   * Maps a report row.
   *
   * @param change the row
   * @return its DTO
   */
  @NotNull
  public static P4kImportChangeDto of(@NotNull P4kImportChange change) {
    return new P4kImportChangeDto(
        change.getKind(),
        change.getChangeType(),
        change.getGuid(),
        change.getFieldName(),
        change.getOldValue(),
        change.getNewValue(),
        change.getClassName(),
        change.getNameEn(),
        change.getRecordPath(),
        change.isStillLiveElsewhere());
  }

  /**
   * Whether the row passes the report's filters.
   *
   * @param kindFilter the kind to show, or {@code null} for all
   * @param typeFilter the change type to show, or {@code null} for all
   * @return {@code true} when both filters accept the row
   */
  public boolean matches(@Nullable P4kKind kindFilter, @Nullable P4kChangeType typeFilter) {
    return (kindFilter == null || kindFilter == kind)
        && (typeFilter == null || typeFilter == changeType);
  }
}
