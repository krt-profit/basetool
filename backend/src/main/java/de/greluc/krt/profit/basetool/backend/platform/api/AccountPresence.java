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

package de.greluc.krt.profit.basetool.backend.platform.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Tells which account ids no longer belong to an account, for a module that keeps an actor's id on
 * a record without a foreign key (REQ-DATA-047); the identity module implements it.
 */
public interface AccountPresence {

  /**
   * Returns the ids among {@code ids} that have no account row.
   *
   * @param ids the ids to check
   * @return the ids of deleted or never-existing accounts
   */
  @NotNull
  @Unmodifiable
  Set<UUID> withoutAccount(@NotNull Collection<UUID> ids);
}
