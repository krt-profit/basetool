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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The match rule of a kind whose rows hold a stronger key than a class name or a name: a stored row
 * holding the listed GUID of another record of the run is no class-name or name candidate
 * (REQ-P4K-072), since it already stands for that record.
 *
 * @param <T> the row type
 */
@RequiredArgsConstructor
final class P4kListedGuidRules<T extends P4kKeyedRow> implements P4kMatchRules<T> {

  /** The GUIDs the catalogue lists for the kind. */
  @NotNull private final Set<UUID> listed;

  /**
   * Collects the GUIDs of a kind's records.
   *
   * @param records the kind's records
   * @param <T> the row type
   * @return the rules over those GUIDs
   */
  @NotNull
  static <T extends P4kKeyedRow> P4kListedGuidRules<T> of(
      @NotNull List<? extends P4kRecord> records) {
    return new P4kListedGuidRules<>(
        records.stream()
            .map(P4kRecord::parsedGuid)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet()));
  }

  /**
   * Tells whether a found row may stand for the record of a GUID.
   *
   * @param guid the record's GUID
   * @param row the row a class name or name found
   * @return {@code false} when the row holds the listed GUID of another record
   */
  @Override
  public boolean isCandidate(@NotNull UUID guid, @NotNull T row) {
    UUID held = row.getP4kUuid();
    return held == null || held.equals(guid) || !listed.contains(held);
  }
}
