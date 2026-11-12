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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reads the caller's stored choice of the active org unit (REQ-ORG-031) for {@link
 * ActiveOrgUnitRestore}; the org-unit domain supplies it, so the kernel names no domain.
 */
public interface ActiveOrgUnitChoiceSource {

  /**
   * Reads the current caller's stored choice from the backend.
   *
   * @return the chosen org unit, or empty for all of the caller's org units
   * @throws RuntimeException when the backend read fails; the caller decides how to degrade
   */
  @NotNull
  Optional<UUID> storedChoice();
}
