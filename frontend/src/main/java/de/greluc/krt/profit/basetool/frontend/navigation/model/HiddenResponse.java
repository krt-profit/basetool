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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The caller's hidden-pages section as the backend stores it (REQ-UI-032).
 *
 * @param keys the hidden page keys
 * @param version the section's optimistic-lock version, echoed on the next write
 */
@DtoMirror
public record HiddenResponse(@NotNull List<String> keys, long version) {

  /** The section of a caller who hid nothing. */
  public static final HiddenResponse EMPTY = new HiddenResponse(List.of(), 0);

  /**
   * Copies the keys so the record stays immutable.
   *
   * @param keys the hidden page keys
   * @param version the section's version
   */
  public HiddenResponse {
    keys = keys == null ? List.of() : List.copyOf(keys);
  }
}
