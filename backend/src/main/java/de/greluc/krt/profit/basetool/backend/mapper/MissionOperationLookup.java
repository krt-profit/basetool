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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionOperationSummary;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The mission module's lookup of an operation summary, memoised for the running transaction. */
public interface MissionOperationLookup {

  /**
   * Returns the operation a mission belongs to; the first miss in a transaction loads every
   * operation the missions of the persistence context belong to in one read.
   *
   * @param operationId the operation
   * @return the operation's summary, or {@code null} when it does not exist
   */
  @Nullable
  MissionOperationSummary operation(@NotNull UUID operationId);
}
