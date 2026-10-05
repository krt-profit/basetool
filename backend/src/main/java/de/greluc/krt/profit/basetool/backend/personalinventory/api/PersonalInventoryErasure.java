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

package de.greluc.krt.profit.basetool.backend.personalinventory.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** The personal-inventory module's erasure command for the GDPR user deletion (plan §5.3, §7.6). */
public interface PersonalInventoryErasure {

  /**
   * Deletes every "Mein Inventar" row of an owner, inside the caller's transaction.
   *
   * @param ownerUserId the departing owner's {@code app_user.id}
   * @return the number of deleted rows
   */
  int deleteAllItemsOf(@NotNull UUID ownerUserId);
}
