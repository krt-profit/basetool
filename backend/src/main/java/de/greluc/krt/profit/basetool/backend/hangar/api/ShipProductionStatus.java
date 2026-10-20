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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** RSI production status of a ship, as an imported hangar export reports it (REQ-HANGAR-015). */
public enum ShipProductionStatus {
  /** The ship is flyable in the game. */
  FLIGHT_READY,
  /** The ship is sold but still a concept. */
  IN_CONCEPT,
  /** The ship is being built and not flyable yet. */
  IN_PRODUCTION;

  /**
   * Reads an export's status text, ignoring case, spaces, hyphens and underscores.
   *
   * @param raw the status text, e.g. {@code "Flight-Ready"} or {@code "In-Concept"}
   * @return the matching status, or {@code null} for a blank or unknown text
   */
  public static @Nullable ShipProductionStatus fromExport(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    return switch (raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")) {
      case "flightready" -> FLIGHT_READY;
      case "inconcept", "concept" -> IN_CONCEPT;
      case "inproduction" -> IN_PRODUCTION;
      default -> null;
    };
  }
}
