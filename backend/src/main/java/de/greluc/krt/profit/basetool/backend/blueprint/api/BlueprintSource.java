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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where an owned blueprint came from (REQ-INV-054); its lower-case name is the exchange contract's
 * {@code provenance.source}.
 */
public enum BlueprintSource {

  /** Read from the game log by a client. */
  LOG,

  /** Added by hand, in the web or the app, or by a client that says so. */
  MANUAL,

  /** Taken over from an uploaded file's reviewed preview. */
  IMPORT,

  /** Granted to every member by default. */
  DEFAULT,

  /** Any other way a client names. */
  OTHER;

  /**
   * Returns the name the exchange contract uses.
   *
   * @return the lower-case name
   */
  public @NotNull String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Reads what a client sends for an added blueprint; a default grant is the server's alone, so a
   * client's {@code default}, an unknown value and none all count as {@link #OTHER}.
   *
   * @param wire the contract's value, or {@code null}
   * @return the source
   */
  public static @NotNull BlueprintSource fromClient(@Nullable String wire) {
    if (wire == null) {
      return OTHER;
    }
    return switch (wire) {
      case "log" -> LOG;
      case "manual" -> MANUAL;
      case "import" -> IMPORT;
      default -> OTHER;
    };
  }
}
