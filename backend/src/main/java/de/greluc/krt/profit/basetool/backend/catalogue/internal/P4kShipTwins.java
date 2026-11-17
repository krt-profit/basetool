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
import java.util.ArrayList;
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
 * stands for the same game ship. The twins are found before the run decides which rows move to
 * their base or successor (REQ-P4K-051), since a twin's Wiki UUID would block such a move, and are
 * merged once it has: a row that moves keeps its own Wiki identity.
 */
@Component
@RequiredArgsConstructor
class P4kShipTwins {

  private final ShipTypeMergeService merges;

  /**
   * Finds the twins among the stored ship types.
   *
   * @param stored the stored ship types
   * @param ships the catalogue's ships
   * @param retiring the ship types this run retires (REQ-P4K-052); none of them takes a twin
   * @return the twins, each with the row it is merged into
   */
  @NotNull
  List<Twin> find(
      @NotNull List<ShipType> stored,
      @NotNull List<P4kShipDto> ships,
      @NotNull Set<UUID> retiring) {
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
    List<Twin> twins = new ArrayList<>();
    for (ShipType row : stored) {
      UUID wikiUuid = row.getExternalUuid();
      ShipType survivor = wikiUuid == null ? null : byGuid.get(wikiUuid);
      P4kShipDto record = wikiUuid == null ? null : records.get(wikiUuid);
      if (row.getP4kUuid() == null && survivor != null && record != null) {
        twins.add(new Twin(row, survivor, record));
      }
    }
    return List.copyOf(twins);
  }

  /**
   * Merges the twins of an APPLY, or reports those a preview would merge.
   *
   * @param twins the twins {@link #find} found
   * @param moves the GUID each ship type this run moves takes (REQ-P4K-051), by ship type
   * @param context the run
   * @return the twins merged, or in a preview those an APPLY would merge
   */
  @NotNull
  Set<UUID> merge(
      @NotNull List<Twin> twins,
      @NotNull Map<UUID, UUID> moves,
      @NotNull P4kImportContext context) {
    Set<UUID> merged = new HashSet<>();
    for (Twin twin : twins) {
      UUID twinId = Objects.requireNonNull(twin.row().getId());
      UUID survivorId = Objects.requireNonNull(twin.survivor().getId());
      String twinName = twin.row().getName();
      boolean done =
          !context.isApply()
              || merges.merge(
                  twinId,
                  survivorId,
                  moves.getOrDefault(survivorId, twin.survivor().getP4kUuid()),
                  ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID);
      if (done) {
        context
            .getReport()
            .merged(P4kKind.SHIP, twin.record(), twinName, twin.survivor().getName());
        merged.add(twinId);
      }
    }
    return Set.copyOf(merged);
  }

  /**
   * A twin and the row it is merged into.
   *
   * @param row the twin
   * @param survivor the row holding the GUID that is the twin's Wiki UUID
   * @param record the catalogue record of that GUID
   */
  record Twin(@NotNull ShipType row, @NotNull ShipType survivor, @NotNull P4kShipDto record) {}
}
