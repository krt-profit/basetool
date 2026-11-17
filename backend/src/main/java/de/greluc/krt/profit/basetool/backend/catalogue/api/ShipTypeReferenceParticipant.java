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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reports which ship types a module's rows reference, so the catalogue retires only a ship type
 * nothing uses (REQ-P4K-052).
 */
@ObserverSpi
public interface ShipTypeReferenceParticipant {

  /**
   * Tells which of the given ship types one of the module's rows references.
   *
   * @param shipTypeIds the ship types asked about
   * @return the referenced ones among them, empty when none is
   */
  @NotNull
  Set<UUID> referencedShipTypes(@NotNull Collection<UUID> shipTypeIds);
}
