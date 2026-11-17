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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether a stored ship type already stands for a catalogue ship of another GUID
 * (REQ-P4K-050): both carry the same in-game name, and the ship is either a variant of the group
 * the stored ship type's GUID belongs to, or a ship no player owns. Such a ship needs neither a row
 * of its own nor a review case.
 */
final class P4kShipCoverage {

  /** The playabilities of the ships no player can own, buy or fit. */
  private static final Set<P4kShipPlayability> NOT_OWNED =
      EnumSet.of(P4kShipPlayability.AI, P4kShipPlayability.MISSION, P4kShipPlayability.SPECIAL);

  /** The catalogue's ships by GUID; a repeated GUID keeps its first record. */
  @NotNull private final Map<UUID, P4kShipDto> records;

  private P4kShipCoverage(@NotNull Map<UUID, P4kShipDto> records) {
    this.records = records;
  }

  /**
   * Indexes the catalogue's ships.
   *
   * @param ships the catalogue's ships
   * @return the coverage rule over them
   */
  @NotNull
  static P4kShipCoverage of(@NotNull List<P4kShipDto> ships) {
    Map<UUID, P4kShipDto> records = new HashMap<>();
    for (P4kShipDto ship : ships) {
      UUID guid = ship.parsedGuid();
      if (guid != null) {
        records.putIfAbsent(guid, ship);
      }
    }
    return new P4kShipCoverage(records);
  }

  /**
   * Tells whether a stored ship type stands for the catalogue ship of a GUID it does not hold.
   *
   * @param guid the catalogue ship's GUID
   * @param row the stored ship type a fallback key found
   * @return {@code true} when both carry the same in-game name and the ship is a variant of the
   *     row's group or a ship no player owns
   */
  boolean covers(@NotNull UUID guid, @NotNull ShipType row) {
    P4kShipDto record = records.get(guid);
    if (record == null
        || !Objects.equals(P4kMatchKeys.fold(record.nameEn()), P4kMatchKeys.fold(row.getName()))) {
      return false;
    }
    UUID base = P4kGuids.parse(record.variantOf());
    UUID held = row.getP4kUuid();
    if (base != null && held != null && base.equals(groupOf(held))) {
      return true;
    }
    return NOT_OWNED.contains(P4kClosedSet.parse(P4kShipPlayability.class, record.playability()));
  }

  /**
   * Returns the base GUID of the variant group a ship belongs to.
   *
   * @param guid the ship's GUID
   * @return its {@code variantOf}, the GUID itself for a base or an ungrouped ship, or {@code null}
   *     when the catalogue does not list it
   */
  @Nullable
  private UUID groupOf(@NotNull UUID guid) {
    P4kShipDto record = records.get(guid);
    if (record == null) {
      return null;
    }
    UUID base = P4kGuids.parse(record.variantOf());
    return base == null ? guid : base;
  }
}
