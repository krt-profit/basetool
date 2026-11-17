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
 * Reports which game items a module's member rows reference, so the catalogue retires only an item
 * no member uses (REQ-P4K-056).
 */
@ObserverSpi
public interface GameItemReferenceParticipant {

  /**
   * Tells which of the given game items one of the module's rows references.
   *
   * @param gameItemIds the items asked about
   * @return the referenced ones among them, empty when none is
   */
  @NotNull
  Set<UUID> referencedGameItems(@NotNull Collection<UUID> gameItemIds);
}
