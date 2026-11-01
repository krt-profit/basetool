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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Redirects the admin page URLs that merged into another page to their page and tab, so bookmarks
 * and notification links keep working (REQ-UI-026).
 *
 * <p>Only the page {@code GET} without the {@code X-Requested-With} header is redirected; the
 * in-place requests of the merged pages are served by their own endpoints.
 */
@Controller
@UsesLayoutModel
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class LegacyAdminRedirectController {

  private static final String PAGE_ONLY = "!X-Requested-With";

  /**
   * The former bank page, now the Gefahrenzone tab of the system settings.
   *
   * @return a redirect to the danger-zone tab
   */
  @NotNull
  @GetMapping(value = "/admin/bank", headers = PAGE_ONLY)
  public String bank() {
    return "redirect:/admin/settings/danger-zone";
  }

  /**
   * The Staffeln list of the mission data, now the Staffeln tab of the organisation structure.
   *
   * @return a redirect to the Staffeln tab
   */
  @NotNull
  @GetMapping(value = "/admin/mission-data", params = "kind=squadrons", headers = PAGE_ONLY)
  public String squadrons() {
    return "redirect:/admin/org-structure/squadrons";
  }

  /**
   * The former announcement editor, now the inline editor of the Information widget on the
   * dashboard.
   *
   * @return a redirect to the dashboard
   */
  @NotNull
  @GetMapping(value = "/admin/announcement", headers = PAGE_ONLY)
  public String announcement() {
    return "redirect:/";
  }
}
