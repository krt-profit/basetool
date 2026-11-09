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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import de.greluc.krt.profit.basetool.frontend.bank.client.OrgUnitBankRequestsBackendClient;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestViewDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the page of one booking request (REQ-BANK-068), the target of the exchange staff queue's
 * {@code link}: its details and the way to the surface where the caller acts on it. The backend
 * decides who may see it; a peer's decision re-renders the {@code requestDetail} fragment in place.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
public class BankRequestPageController {

  /** The fragment the live update swaps. */
  static final String FRAGMENT = "requestDetail";

  /** The org-unit booking-request reads. */
  private final OrgUnitBankRequestsBackendClient requestsClient;

  /**
   * Renders the request's page, or its {@code requestDetail} fragment.
   *
   * @param id the request
   * @param fragment {@code "requestDetail"} to re-render only the detail card
   * @param model Spring MVC model populated with {@code view}, {@code null} when the request is
   *     unknown or hidden from the caller
   * @return the template, or its fragment
   */
  @NotNull
  @GetMapping("/bank/requests/{id}")
  @PreAuthorize("isAuthenticated()")
  public String request(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) @Nullable String fragment,
      @NotNull Model model) {
    BankBookingRequestViewDto view = requestsClient.view(id);
    model.addAttribute("view", view);
    model.addAttribute("requestId", id);
    return FRAGMENT.equals(fragment) ? "bank/bank-request :: " + FRAGMENT : "bank/bank-request";
  }
}
