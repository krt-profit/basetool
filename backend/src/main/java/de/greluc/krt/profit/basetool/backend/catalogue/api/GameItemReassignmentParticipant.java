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
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Repoints a module's item references inside an atomic catalogue reconciliation. */
@ObserverSpi
public interface GameItemReassignmentParticipant {

  /**
   * Moves references to the surviving item and invalidates versions of affected rows.
   *
   * @param retiredId duplicate catalogue row being retired
   * @param survivorId the canonical item
   * @return the number of rows whose references changed
   */
  int reassignGameItem(@NotNull UUID retiredId, @NotNull UUID survivorId);
}
