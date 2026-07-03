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

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
@Slf4j
public class BankGrantsPageController {

  /** Response type for the bank-grant list pulls ({@code GET /api/v1/bank/grants}). */
  private static final ParameterizedTypeReference<List<BankGrantDto>> BANK_GRANT_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the paginated bank-account pull ({@code GET /api/v1/bank/accounts}). */
  private static final ParameterizedTypeReference<PageResponse<BankAccountDto>>
      BANK_ACCOUNT_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for the user-reference lookup ({@code GET /api/v1/users/lookup}). */
  private static final ParameterizedTypeReference<List<UserReferenceDto>> USER_REFERENCE_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the grants matrix. The {@code view} parameter switches the grouping (G2 toggle): {@code
   * account} (default) filters by the selected account, {@code employee} filters by the selected
   * grantee. The user lookup feeds the grant-creation modal's searchable select.
   *
   * @param view grouping mode ({@code account} default, {@code employee})
   * @param accountId selected account in per-account mode; absent = all accounts
   * @param userId selected grantee in per-employee mode; absent = first employee with grants
   * @param fragment when {@code "grantsMatrix"} only the capability matrix is re-rendered after a
   *     grant create/revoke (REQ-FE-005), honouring the current {@code view}/{@code
   *     accountId}/{@code userId} filter so the in-place swap keeps the active grouping; the filter
   *     selectors and the create modal's lookups (all grants, accounts, users) are then skipped
   * @param model Spring MVC model
   * @return the grants template, or its {@code grantsMatrix} fragment for an AJAX swap
   */
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
    PageResponse<BankAccountDto> accounts =
        backendApiClient.get("/api/v1/bank/accounts?size=500", BANK_ACCOUNT_PAGE_TYPE);
    final List<UserReferenceDto> users =
        backendApiClient.get("/api/v1/users/lookup", USER_REFERENCE_LIST_TYPE);

    // The per-employee selector lists every grantee that currently holds at least one grant.
    Map<UUID, String> grantees = new LinkedHashMap<>();
    for (BankGrantDto grant : allGrants == null ? List.<BankGrantDto>of() : allGrants) {
      grantees.putIfAbsent(grant.userId(), grant.userHandle());
    }

    model.addAttribute(
        "accounts",
        accounts == null
            ? List.<BankAccountDto>of()
            : BankAccountOrder.byName(accounts.content(), BankAccountDto::name));
    model.addAttribute("users", users == null ? List.<UserReferenceDto>of() : users);
    model.addAttribute("grantees", grantees);
    model.addAttribute("byEmployee", byEmployee);
    model.addAttribute("selectedAccountId", accountId);
    model.addAttribute("selectedUserId", userId);
    return "bank-grants";
  }
}
