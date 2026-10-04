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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Supplies the org unit the current request acts in, for the {@code orgUnitId} MDC field (plan
 * §5.3).
 *
 * <p>Owned by the platform and implemented by the scope module. It is asked only for an
 * authenticated request.
 */
public interface ActiveOrgUnitProvider {

  /**
   * The org unit the current caller acts in: an admin's active selection, else the home org unit.
   *
   * @return the org unit id, or empty for an admin without a selection or an unassigned member
   */
  @NotNull
  Optional<UUID> activeOrgUnitId();
}
