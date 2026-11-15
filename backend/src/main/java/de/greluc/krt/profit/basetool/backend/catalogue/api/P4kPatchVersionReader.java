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

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** Reads the patch version of the catalogue's P4K imports (REQ-P4K-005, REQ-PATCH-001). */
public interface P4kPatchVersionReader {

  /**
   * Returns the patch version of the latest applied P4K import that knows one.
   *
   * @return the version, e.g. {@code 4.4.0}, or empty when no applied import knows one
   */
  @NotNull
  Optional<String> latestPatchVersion();
}
