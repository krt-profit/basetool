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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Supplies the amounts a unit has asked for but not yet received, so a minimum stock level can show
 * them as „angefordert" (REQ-INV-067); implemented by the supply module for open needs and by the
 * job-order module for open and in-progress requesting orders. The amounts are shown only and never
 * count as stock.
 */
public interface StockLevelDemandSource {

  /**
   * One outstanding request of a unit for a material or a game item at a quality floor.
   *
   * @param unitId the requesting unit
   * @param subjectId the requested material or game item
   * @param minQuality the floor of the requested quality, {@code 0} for any quality or an item
   * @param amount the outstanding amount, above zero
   */
  record Request(@NotNull UUID unitId, @NotNull UUID subjectId, int minQuality, double amount) {}

  /**
   * Lists the outstanding requests of the given units for the given catalogue entries.
   *
   * @param unitIds the requesting units; never empty
   * @param subjectIds the materials and game items; never empty
   * @return the outstanding requests, any order
   */
  @NotNull
  List<Request> outstandingRequests(
      @NotNull Collection<UUID> unitIds, @NotNull Collection<UUID> subjectIds);
}
