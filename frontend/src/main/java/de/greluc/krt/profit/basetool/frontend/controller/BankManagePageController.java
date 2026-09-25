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
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the bank management page {@code /bank/manage} with its account-lifecycle and holder
 * registry tabs. Open to bank employees for {@code SPECIAL} accounts and the holder menu; the other
 * actions are management-only, enforced per action (REQ-BANK-030).
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
public class BankManagePageController {

  /** Response type for the paged {@code /bank/accounts} listing. */
  private static final ParameterizedTypeReference<PageResponse<BankAccountDto>>
      BANK_ACCOUNT_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for the {@code /bank/holders} holder-registry list. */
  private static final ParameterizedTypeReference<List<BankHolderDto>> BANK_HOLDER_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the {@code /org-units/active-all-kinds} org-unit option list. */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_OPTION_LIST_TYPE = new ParameterizedTypeReference<>() {};

  /** Offered page sizes for the account-management table (REQ-BANK-053). */
  private static final List<Integer> PAGE_SIZES = List.of(25, 50, 100);

  /** Default page size when none (or a non-whitelisted one) is requested. */
  private static final int DEFAULT_PAGE_SIZE = 25;

  private final BackendApiClient backendApiClient;

  /**
   * Renders the management page with all accounts including balances and the holder registry with
   * custody totals.
   *
   * @param tab the active tab ({@code halter} default, {@code konten})
   * @param fragment {@code "manageBody"} to re-render only the tab navigation and active panel
   *     (REQ-FE-005)
   * @param authentication the caller's authentication, used to detect the management perspective
   * @param principal the OIDC user whose {@code sub} links the caller's own holder row; {@code
   *     null} for a non-OIDC principal
   * @param model Spring MVC model
   * @return the manage template, or its {@code manageBody} fragment
   */
  @NotNull
  @GetMapping("/bank/manage")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  public String manage(
      @RequestParam(required = false) String tab,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Authentication authentication,
      @AuthenticationPrincipal OidcUser principal,
      Model model) {
    boolean management = hasRole(authentication, Roles.authority(Roles.BANK_MANAGEMENT));
    int effectiveSize = size == null || !PAGE_SIZES.contains(size) ? DEFAULT_PAGE_SIZE : size;
    int effectivePage = page == null || page < 0 ? 0 : page;
    PageResponse<BankAccountDto> accounts =
        backendApiClient.get(
            "/api/v1/bank/accounts?page="
                + effectivePage
                + "&size="
                + effectiveSize
                + "&sort=name,asc",
            BANK_ACCOUNT_PAGE_TYPE);
    List<BankHolderDto> holders =
        backendApiClient.get("/api/v1/bank/holders", BANK_HOLDER_LIST_TYPE);
    model.addAttribute("accountsPage", accounts);
    model.addAttribute(
        "accounts", accounts == null ? List.<BankAccountDto>of() : accounts.content());
    model.addAttribute("accountCount", accounts == null ? 0L : accounts.totalElements());
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("accountsPaginationBaseUrl", "/bank/manage?tab=konten");
    model.addAttribute("holders", holders == null ? List.<BankHolderDto>of() : holders);
    model.addAttribute("management", management);
    model.addAttribute("cartelAccount", management ? fetchCartelAccount() : null);
    model.addAttribute("selfUserId", CurrentUser.userIdText(principal));
    String activeTab;
    if (management && "krt-freigaben".equalsIgnoreCase(tab)) {
      activeTab = "krt-freigaben";
    } else if ("konten".equalsIgnoreCase(tab)) {
      activeTab = "konten";
    } else {
      activeTab = "halter";
    }
    model.addAttribute("activeTab", activeTab);
    if ("manageBody".equals(fragment)) {
      return "bank-manage :: manageBody";
    }

    if (management) {
      List<OrgUnitMembershipOptionDto> orgUnits =
          backendApiClient.getCached(
              CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS, ORG_UNIT_OPTION_LIST_TYPE);
      model.addAttribute(
          "orgUnits", orgUnits == null ? List.<OrgUnitMembershipOptionDto>of() : orgUnits);
    } else {
      model.addAttribute("orgUnits", List.<OrgUnitMembershipOptionDto>of());
    }
    return "bank-manage";
  }

  /**
   * Resolves the singleton {@code CARTEL} (KRT) account for the KRT-Freigaben tab (REQ-BANK-047)
   * via a type-filtered one-row search.
   *
   * @return the CARTEL account, or {@code null} when none exists or the lookup fails
   */
  @Nullable
  private BankAccountDto fetchCartelAccount() {
    try {
      PageResponse<BankAccountDto> page =
          backendApiClient.get("/api/v1/bank/accounts?type=CARTEL&size=1", BANK_ACCOUNT_PAGE_TYPE);
      return page == null || page.content().isEmpty() ? null : page.content().get(0);
    } catch (RuntimeException e) {
      log.debug("Could not resolve the CARTEL account for the KRT-Freigaben tab", e);
      return null;
    }
  }

  /**
   * Whether the caller holds the given authority — the management-vs-employee split for the
   * data-fetch decisions (the template re-checks each action with {@code sec:authorize}).
   *
   * @param authentication the caller's authentication, possibly {@code null}
   * @param authority the authority to look for (e.g. {@code ROLE_BANK_MANAGEMENT})
   * @return {@code true} when the authority is present
   */
  private static boolean hasRole(@Nullable Authentication authentication, String authority) {
    return authentication != null
        && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(authority::equals);
  }
}
