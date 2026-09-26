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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Renders the grants administration page ({@code /bank/grants}, G1 mockup with the G2 grouping
 * toggle): the per-(employee, account) capability flag matrix, grouped per account by default and
 * per employee on demand. Management-only (REQ-BANK-009/-010).
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
public class BankGrantsPageController {

  /** Response type for the bank-grant list pulls ({@code GET /api/v1/bank/grants}). */
  private static final ParameterizedTypeReference<List<BankGrantDto>> BANK_GRANT_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the grants matrix, grouped by account (default) or by employee. User and account
   * pickers are server-side search comboboxes, so only the filtered account is resolved.
   *
   * @param view grouping mode ({@code account} default, {@code employee})
   * @param accountId selected account in per-account mode; absent = all accounts
   * @param userId selected grantee in per-employee mode; absent = first employee with grants
   * @param fragment {@code "grantsMatrix"} to re-render only the matrix under the current filter
   *     (REQ-FE-005)
   * @param model Spring MVC model
   * @return the grants template, or its {@code grantsMatrix} fragment
   */
  @NotNull
  @GetMapping("/bank/grants")
  @PreAuthorize("hasRole('" + Roles.BANK_MANAGEMENT + "')")
  public String grants(
      @RequestParam(required = false) String view,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) UUID userId,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean byEmployee = "employee".equalsIgnoreCase(view);

    UriComponentsBuilder grantsUri = UriComponentsBuilder.fromPath("/api/v1/bank/grants");
    if (byEmployee && userId != null) {
      grantsUri.queryParam("userId", userId);
    } else if (!byEmployee && accountId != null) {
      grantsUri.queryParam("accountId", accountId);
    }
    List<BankGrantDto> grants = backendApiClient.get(grantsUri.toUriString(), BANK_GRANT_LIST_TYPE);
    model.addAttribute("grants", grants == null ? List.<BankGrantDto>of() : grants);
    if ("grantsMatrix".equals(fragment)) {
      return "bank-grants :: grantsMatrix";
    }

    List<BankGrantDto> allGrants =
        backendApiClient.get("/api/v1/bank/grants", BANK_GRANT_LIST_TYPE);

    Map<UUID, String> grantees = new LinkedHashMap<>();
    for (BankGrantDto grant : allGrants == null ? List.<BankGrantDto>of() : allGrants) {
      grantees.putIfAbsent(grant.userId(), grant.userHandle());
    }

    BankAccountDto selectedAccount = null;
    if (!byEmployee && accountId != null) {
      try {
        BankAccountDetailDto detail =
            backendApiClient.get("/api/v1/bank/accounts/" + accountId, BankAccountDetailDto.class);
        selectedAccount = detail == null ? null : detail.account();
      } catch (RuntimeException e) {
        log.debug("Could not resolve selected grant-filter account {} for seeding", accountId, e);
      }
    }

    model.addAttribute("selectedAccount", selectedAccount);
    model.addAttribute("grantees", grantees);
    model.addAttribute("byEmployee", byEmployee);
    model.addAttribute("selectedUserId", userId);
    return "bank-grants";
  }
}
