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

import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A module that references blueprint products by their product key and moves those references when
 * a name key is upgraded to its produced item's GUID key (REQ-INV-070, ADR-0343): one key in the
 * system, no translation layer.
 */
public interface ProductKeyChangeParticipant {

  /**
   * Returns every product key the module references.
   *
   * @return the keys, GUID and name keys alike
   */
  @NotNull
  @Unmodifiable
  Set<String> referencedProductKeys();

  /**
   * Moves every reference from one product key to another, inside the caller's transaction and
   * before the member's blueprint rows move, so a change the move records lands after it.
   *
   * @param from the name key
   * @param to the GUID key
   */
  void moveProductKey(@NotNull String from, @NotNull String to);
}
