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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReferenceParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
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
 * Retires the ship types an earlier seeding created whose record no seeding rule would create any
 * more ({@code ai}, {@code mission}) and that no module references (REQ-P4K-052): each is hidden
 * and gives its in-game name up for its record's class name. A referenced one stays a {@code
 * SEEDED_NOT_CREATABLE} case.
 */
@Component
@RequiredArgsConstructor
class P4kSeededShipRetirement {

  private final List<ShipTypeReferenceParticipant> participants;

  /**
   * Retires the unreferenced seeded ship types of an APPLY, or reports those a preview would.
   *
   * @param stored the stored ship types
   * @param ships the catalogue's ships
   * @param context the run
   * @return the ship types this run retires
   */
  @NotNull
  Set<UUID> retire(
      @NotNull List<ShipType> stored,
      @NotNull List<P4kShipDto> ships,
      @NotNull P4kImportContext context) {
    Map<UUID, P4kShipDto> records = new HashMap<>();
    ships.forEach(ship -> {
      UUID guid = ship.parsedGuid();
      if (guid != null) {
        records.putIfAbsent(guid, ship);
      }
    });
    Map<UUID, Candidate> candidates = new HashMap<>();
    for (ShipType row : stored) {
      P4kShipDto record = row.getP4kUuid() == null ? null : records.get(row.getP4kUuid());
      if (record != null
          && row.getSourceSystems() == GameItemSourceSystem.P4K
          && !row.isP4kRetired()
          && P4kSeeding.forShip(P4kClosedSet.parse(P4kShipPlayability.class, record.playability()))
              == P4kSeeding.COUNT_ONLY) {
        candidates.put(Objects.requireNonNull(row.getId()), new Candidate(row, record));
      }
    }
    if (candidates.isEmpty()) {
      return Set.of();
    }
    Set<UUID> referenced = new HashSet<>();
    participants.forEach(
        participant -> referenced.addAll(participant.referencedShipTypes(candidates.keySet())));
    Set<String> names = new HashSet<>();
    stored.forEach(row -> names.add(P4kMatchKeys.fold(row.getName())));
    Set<UUID> retired = new HashSet<>();
    candidates.forEach((id, candidate) -> {
      if (!referenced.contains(id)) {
        retired.add(id);
        context.retire(P4kKind.SHIP, id);
        hide(candidate, context);
        releaseName(candidate, names, context);
      }
    });
    return Set.copyOf(retired);
  }

  /**
   * Hides a retired ship type as an admin's hide would (REQ-P4K-016).
   *
   * @param candidate the ship type and its record
   * @param context the run
   */
  private static void hide(@NotNull Candidate candidate, @NotNull P4kImportContext context) {
    context
        .getReport()
        .changed(
            P4kKind.SHIP, candidate.record(), "p4k_hidden_at", null, context.getNow().toString());
    if (context.isApply()) {
      candidate.row().setP4kHiddenAt(context.getNow());
    }
  }

  /**
   * Renames a retired ship type to its record's class name, so its in-game name is free for the
   * ship it copies; a class name another ship type carries keeps the name as it is.
   *
   * @param candidate the ship type and its record
   * @param names the folded names the stored ship types carry, updated with the new one
   * @param context the run
   */
  private static void releaseName(
      @NotNull Candidate candidate, @NotNull Set<String> names, @NotNull P4kImportContext context) {
    String className = candidate.record().className();
    String folded = P4kMatchKeys.fold(className);
    if (className == null
        || folded == null
        || folded.isEmpty()
        || names.contains(folded)
        || context.isRepeatedClassName(P4kKind.SHIP, className)) {
      return;
    }
    ShipType row = candidate.row();
    context
        .getReport()
        .changed(P4kKind.SHIP, candidate.record(), "name", row.getName(), className.strip());
    names.remove(P4kMatchKeys.fold(row.getName()));
    names.add(folded);
    if (context.isApply()) {
      row.setName(className.strip());
    }
  }

  /**
   * A seeded ship type and the record it holds.
   *
   * @param row the ship type
   * @param record the catalogue record of its GUID
   */
  private record Candidate(@NotNull ShipType row, @NotNull P4kShipDto record) {}
}
