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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Merges the ship types the Wiki or UEX created beside the row of a ship's P4K record into that row
 * (REQ-P4K-053): a row without a P4K GUID whose Wiki UUID is the GUID another, unretired row holds
 * stands for the same game ship.
 */
@Component
@RequiredArgsConstructor
class P4kShipTwins {

  private final ShipTypeMergeService merges;

  /**
   * Merges the twins of an APPLY, or reports those a preview would merge, and leaves them out of
   * the rows the run goes on with.
   *
   * @param stored the stored ship types
   * @param ships the catalogue's ships
   * @param retiring the ship types this run retires (REQ-P4K-052)
   * @param context the run
   * @return the stored ship types without the merged twins
   */
  @NotNull
  List<ShipType> converge(
      @NotNull List<ShipType> stored,
      @NotNull List<P4kShipDto> ships,
      @NotNull Set<UUID> retiring,
      @NotNull P4kImportContext context) {
    Map<UUID, P4kShipDto> records = new HashMap<>();
    ships.forEach(ship -> {
      UUID guid = ship.parsedGuid();
      if (guid != null) {
        records.putIfAbsent(guid, ship);
      }
    });
    Map<UUID, ShipType> byGuid = new HashMap<>();
    stored.forEach(row -> {
      if (row.getP4kUuid() != null && !row.isP4kRetired() && !retiring.contains(row.getId())) {
        byGuid.put(row.getP4kUuid(), row);
      }
    });
    Set<UUID> merged = new HashSet<>();
    for (ShipType twin : stored) {
      UUID wikiUuid = twin.getExternalUuid();
      ShipType survivor = wikiUuid == null ? null : byGuid.get(wikiUuid);
      P4kShipDto record = wikiUuid == null ? null : records.get(wikiUuid);
      if (twin.getP4kUuid() != null || survivor == null || record == null) {
        continue;
      }
      UUID twinId = Objects.requireNonNull(twin.getId());
      String twinName = twin.getName();
      boolean done =
          !context.isApply()
              || merges.merge(
                  twinId,
                  Objects.requireNonNull(survivor.getId()),
                  ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID);
      if (done) {
        context.getReport().merged(P4kKind.SHIP, record, twinName, survivor.getName());
        merged.add(twinId);
      }
    }
    return merged.isEmpty()
        ? stored
        : stored.stream().filter(row -> !merged.contains(row.getId())).toList();
  }
}
