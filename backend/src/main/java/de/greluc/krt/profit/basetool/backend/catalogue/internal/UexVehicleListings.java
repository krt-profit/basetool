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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexVehicleDto;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which of two UEX listings of one game ship a ship type belongs to (REQ-DATA-043). UEX
 * lists some ships twice: under two ids that share a game UUID (Prowler and Prowler Utility), or
 * under the same name (Sabre Raven EX). The listing whose game UUID alone confirms the row's P4K
 * GUID keeps the row; else the listing whose name or full name is the row's name; the other is a
 * secondary listing, which writes nothing and creates no row.
 */
final class UexVehicleListings {

  /** The fetched listings by UEX vehicle id. */
  @NotNull private final Map<Integer, UexVehicleDto> byId;

  /** The fetch's identity census. */
  @NotNull private final UexIdentityCensus census;

  private UexVehicleListings(
      @NotNull Map<Integer, UexVehicleDto> byId, @NotNull UexIdentityCensus census) {
    this.byId = byId;
    this.census = census;
  }

  /**
   * Indexes the fetched listings.
   *
   * @param vehicles the fetched listings
   * @param census the fetch's identity census
   * @return the index
   */
  @NotNull
  static UexVehicleListings of(
      @NotNull List<UexVehicleDto> vehicles, @NotNull UexIdentityCensus census) {
    Map<Integer, UexVehicleDto> byId = new HashMap<>();
    vehicles.forEach(vehicle -> {
      if (vehicle.id() != null) {
        byId.putIfAbsent(vehicle.id(), vehicle);
      }
    });
    return new UexVehicleListings(byId, census);
  }

  /**
   * Tells whether a listing takes a row that holds another fetched listing.
   *
   * @param row the row a resolution step found
   * @param listing the listing being synced
   * @return {@code true} when the listing takes the row over, {@code false} when it is the row's
   *     secondary listing
   */
  boolean takesOver(@NotNull ShipType row, @NotNull UexVehicleDto listing) {
    UexVehicleDto held = row.getUexVehicleId() == null ? null : byId.get(row.getUexVehicleId());
    if (held == null) {
      return true;
    }
    UUID confirmed =
        row.getExternalUuid() != null && row.getExternalUuid().equals(row.getP4kUuid())
            ? row.getExternalUuid()
            : null;
    if (confirmed != null && confirms(held, confirmed)) {
      return false;
    }
    if (confirmed != null && confirms(listing, confirmed)) {
      return true;
    }
    return carries(listing, row.getName()) && !carries(held, row.getName());
  }

  /**
   * Tells whether a listing no resolution step placed is a second listing of a row: one of its
   * names, which another listing also carries, is the name of a row holding such a listing.
   *
   * @param listing the listing being synced
   * @param findByName finds the row carrying a name
   * @return whether the listing is a secondary listing
   */
  boolean isSecondary(
      @NotNull UexVehicleDto listing, @NotNull Function<String, Optional<ShipType>> findByName) {
    for (String name : Arrays.asList(listing.name(), listing.nameFull())) {
      if (name == null || name.isBlank() || census.uniqueName(name)) {
        continue;
      }
      Optional<ShipType> row = findByName.apply(name);
      Integer heldId = row.map(ShipType::getUexVehicleId).orElse(null);
      UexVehicleDto held = heldId == null ? null : byId.get(heldId);
      if (held != null && !Objects.equals(heldId, listing.id()) && carries(held, name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tells whether a listing's game UUID, which no other listing carries, is a given UUID.
   *
   * @param listing the listing
   * @param uuid the UUID
   * @return whether the listing confirms it
   */
  private boolean confirms(@NotNull UexVehicleDto listing, @NotNull UUID uuid) {
    UUID own = UexValues.parseUuid(listing.uuid());
    return uuid.equals(own) && census.uniqueUuid(own);
  }

  /**
   * Tells whether a listing carries a name as its name or full name.
   *
   * @param listing the listing
   * @param name the name, or {@code null}
   * @return whether the folded names match
   */
  private static boolean carries(@NotNull UexVehicleDto listing, @Nullable String name) {
    String key = UexIdentityCensus.nameKey(name);
    return key != null
        && (key.equals(UexIdentityCensus.nameKey(listing.name()))
            || key.equals(UexIdentityCensus.nameKey(listing.nameFull())));
  }
}
