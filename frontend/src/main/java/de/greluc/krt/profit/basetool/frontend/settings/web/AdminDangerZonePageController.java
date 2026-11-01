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

package de.greluc.krt.profit.basetool.frontend.settings.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The Gefahrenzone tab of the system settings ({@code /admin/settings/danger-zone}): the
 * destructive one-off actions, the bank wipe reset and deleting every user's blueprints
 * (REQ-UI-026, REQ-BANK-013).
 *
 * <p>The page only hosts the two action cards; their POST endpoints stay with their domains. The
 * result of a wipe reset arrives as flash attributes.
 */
@Controller
@UsesLayoutModel
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminDangerZonePageController {

  /**
   * Renders the danger-zone tab.
   *
   * @return the {@code settings/admin-danger-zone} view name
   */
  @NotNull
  @GetMapping("/admin/settings/danger-zone")
  public String dangerZone() {
    return "settings/admin-danger-zone";
  }
}
