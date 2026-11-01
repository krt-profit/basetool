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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsAcceptanceStatusDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the bodies of the admin dashboard's widgets (REQ-UI-034). Every endpoint is for
 * administrators only, and a count is the total of the page or list the existing admin read
 * returns. A backend failure is not swallowed, so the widget shows its own error state.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/dashboard/widgets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class DashboardAdminWidgetController {

  private static final String WIDGETS = "dashboard/fragments/admin-widgets :: ";
  private static final String PENDING = "PENDING";

  private final IdentityBackendClient identityClient;
  private final SyncStatusAssembler syncStatus;

  /**
   * Renders the admin links as tiles; the navigation advice puts the links into the model.
   *
   * @return the {@code adminHub} fragment
   */
  @NotNull
  @GetMapping(value = "/admin-hub", headers = "X-Requested-With=XMLHttpRequest")
  public String adminHub() {
    return WIDGETS + "adminHub";
  }

  /**
   * Renders the pending deletion requests, oldest first.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @return the {@code deletionRequests} fragment
   */
  @NotNull
  @GetMapping(value = "/deletion-requests", headers = "X-Requested-With=XMLHttpRequest")
  public String deletionRequests(
      @NotNull Model model, @RequestParam(required = false) @Nullable Integer rows) {
    List<AdminDeletionRequestDto> requests = orEmpty(identityClient.deletionRequests());
    model.addAttribute("adminTotal", requests.size());
    model.addAttribute(
        "adminRows", requests.stream().limit(DashboardWidgetOptions.rows(rows)).toList());
    return WIDGETS + "deletionRequests";
  }

  /**
   * Renders the pending Discord registrations.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @return the {@code discordRegistrations} fragment
   */
  @NotNull
  @GetMapping(value = "/discord-registrations", headers = "X-Requested-With=XMLHttpRequest")
  public String discordRegistrations(
      @NotNull Model model, @RequestParam(required = false) @Nullable Integer rows) {
    List<PendingRegistrationDto> registrations = orEmpty(identityClient.pendingRegistrations());
    model.addAttribute("adminTotal", registrations.size());
    model.addAttribute(
        "adminRows", registrations.stream().limit(DashboardWidgetOptions.rows(rows)).toList());
    return WIDGETS + "discordRegistrations";
  }

  /**
   * Renders the members without accepted terms; the count is the page's total.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @return the {@code openTerms} fragment
   */
  @NotNull
  @GetMapping(value = "/open-terms", headers = "X-Requested-With=XMLHttpRequest")
  public String openTerms(
      @NotNull Model model, @RequestParam(required = false) @Nullable Integer rows) {
    PageResponse<TermsAcceptanceStatusDto> page =
        identityClient.termsAcceptances(PENDING, 0, DashboardWidgetOptions.rows(rows));
    model.addAttribute("adminTotal", page == null ? 0L : page.totalElements());
    model.addAttribute(
        "adminRows", page == null || page.content() == null ? List.of() : page.content());
    return WIDGETS + "openTerms";
  }

  /**
   * Renders the latest sync and import runs against a range.
   *
   * @param model the Thymeleaf model
   * @param days the range in days
   * @return the {@code syncStatus} fragment
   */
  @NotNull
  @GetMapping(value = "/sync-status", headers = "X-Requested-With=XMLHttpRequest")
  public String syncStatus(
      @NotNull Model model, @RequestParam(required = false) @Nullable Integer days) {
    int range = DashboardWidgetOptions.range(days);
    model.addAttribute("syncRows", syncStatus.rows(range, Instant.now()));
    model.addAttribute("syncRange", range);
    return WIDGETS + "syncStatus";
  }

  @NotNull
  private static <T> List<T> orEmpty(@Nullable List<T> values) {
    return values == null ? List.of() : values;
  }
}
