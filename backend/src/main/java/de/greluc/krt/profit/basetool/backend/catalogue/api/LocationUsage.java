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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One module's claim on catalogue locations: tells whether the module still references a location,
 * so the catalogue can refuse to delete one that is in use. Implemented by every module whose rows
 * point at a location and called inside the delete's transaction.
 */
public interface LocationUsage {

  /**
   * Where this usage is asked, ascending; the first usage that reports a location in use names the
   * refusal.
   *
   * @return the position among the registered usages
   */
  int position();

  /**
   * Names what references the location, in the plural, for the refusal message.
   *
   * @return the plural noun, such as {@code ships}
   */
  @NotNull
  String users();

  /**
   * Tells whether at least one row of this module references the location.
   *
   * @param locationId the location to check
   * @return {@code true} when the location is referenced
   */
  boolean isUsed(@NotNull UUID locationId);
}
