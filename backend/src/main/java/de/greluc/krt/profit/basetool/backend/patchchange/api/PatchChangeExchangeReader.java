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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Reads the patch change an exchange client shows its member (REQ-PATCH-050, REQ-PATCH-051). */
public interface PatchChangeExchangeReader {

  /**
   * Reads the latest patch change and the member's state in the latest home-location phase, with
   * the same preview as the member's own prompt.
   *
   * @param member the member the client acts for
   * @return the view
   */
  @NotNull
  PatchChangeExchangeView viewFor(@NotNull UUID member);
}
