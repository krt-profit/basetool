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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of {@code loadouts} (contract, "Loadouts"): the Default Loadout of a ship or an item,
 * resolved into a port tree.
 *
 * @param entityGuid the GUID of the entry in {@code ships} or {@code items}
 * @param entityKind {@code ship}, {@code item} or a value the game adds
 * @param issues the codes of what kept part of the Loadout from being read; empty when all was read
 * @param ports the top-level ports
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kLoadoutDto(
    @Nullable String entityGuid,
    @Nullable String entityKind,
    @Nullable List<String> issues,
    @Nullable List<P4kLoadoutPortDto> ports) {

  /** The {@code entityKind} of a ship's loadout. */
  public static final String SHIP = "ship";

  /**
   * Whether the loadout belongs to an entry of {@code ships}.
   *
   * @return {@code true} for {@code entityKind = ship}
   */
  public boolean isShip() {
    return SHIP.equals(entityKind);
  }
}
