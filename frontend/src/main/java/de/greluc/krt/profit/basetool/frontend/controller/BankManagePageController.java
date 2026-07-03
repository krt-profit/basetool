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
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Renders the bank management page ({@code /bank/manage}, W1 mockup): the account-lifecycle tab and
 * the holder-registry tab. Open to bank employees (REQ-BANK-030, ADR-0040): employees may create
 * {@code SPECIAL} accounts and use the holder menu incl. the holder→holder Umbuchung, while
 * account-relationship lifecycle, manual holder registration and grants stay management-only —
 * enforced per-action in the template (and server-side). Admins pass via the role hierarchy.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class BankManagePageController {

  private final BackendApiClient backendApiClient;

  /**
   * Renders the management page with both tabs' data: all accounts (incl. balances — the
   * zero-balance rule disables the close button server-knowledge-first) and the holder registry
   * with custody totals. The org-unit list and the user lookup feed the two creation modals.
   *
   * @param tab the active tab ({@code halter} default/first, {@code konten})
   * @param fragment when {@code "manageBody"} only the tab-nav + active panel are re-rendered after
   *     an account/holder lifecycle write (REQ-FE-005), refreshing the row plus the tab-count
   *     aggregates in place; the creation-modal lookups (org-units, users) are then skipped because
   *     the modals live outside the swapped region
   * @param authentication the caller's authentication, used to detect the management perspective
   * @param principal the authenticated OIDC user, used to read the caller's {@code sub} (Keycloak
   *     UUID) so the holder tab can link the caller's own holder row; {@code null} for a non-OIDC
   *     principal (e.g. a {@code @WithMockUser} test), in which case no self-link is rendered
   * @param model Spring MVC model
   * @return the manage template, or its {@code manageBody} fragment for an AJAX swap
   */
  @GetMapping("/bank/manage")
  @PreAuthorize("hasRole('" + Roles.BANK_EMPLOYEE + "')")
  public String manage(
      @RequestParam(required = false) String tab,
      @RequestParam(required = false) String fragment,
      Authentication authentication,
      @AuthenticationPrincipal OidcUser principal,
      Model model) {
    boolean management = hasRole(authentication, Roles.authority(Roles.BANK_MANAGEMENT));
    PageResponse<BankAccountDto> accounts =
        backendApiClient.get(
            "/api/v1/bank/accounts?size=500", new ParameterizedTypeReference<>() {});
    List<BankHolderDto> holders =
        backendApiClient.get(
            "/api/v1/bank/holders", new ParameterizedTypeReference<List<BankHolderDto>>() {});
    List<BankAccountDto> orderedAccounts =
        accounts == null
            ? List.<BankAccountDto>of()
            : BankAccountOrder.byName(accounts.content(), BankAccountDto::name);
    model.addAttribute("accounts", orderedAccounts);
    model.addAttribute("holders", holders == null ? List.<BankHolderDto>of() : holders);
    model.addAttribute("management", management);
    // The KRT account (singleton CARTEL) for the Bankleitung-only "KRT-Freigaben" tab
    // (REQ-BANK-047),
    // where the two 3-stage thresholds T1/T2 are edited; null until a KRT account exists.
    model.addAttribute(
        "cartelAccount",
        orderedAccounts.stream().filter(a -> "CARTEL".equals(a.type())).findFirst().orElse(null));
    // The caller's own user id (OIDC sub) so the holder tab can link only the caller's own holder
    // row to its history; management links every row (REQ-BANK-032). The real per-holder gate is
    // server-side (canSeeHolder) — this only governs which links the UI renders.
    // NOTE: authentication.getName() returns the preferred_username (the frontend OAuth2
    // user-name-attribute), NOT the Keycloak sub — comparing it against the holder's userId
    // (== app_user.id == sub) never matched, so a plain bank employee never saw the link to their
    // own holder. principal.getSubject() is the sub (UUID) that equals BankHolderDto.userId; same
    // fix as the mission participant self-edit carve-out (MissionPageController#authUserId).
    model.addAttribute("selfUserId", principal != null ? principal.getSubject() : null);
    // Halter is the default-open tab (it sits first/left in the tab nav); ?tab=konten opens the
    // accounts tab and ?tab=krt-freigaben the Bankleitung-only KRT approval-thresholds tab
    // (REQ-BANK-047) — the latter only for a management caller, so a plain employee forcing the
    // query
    // param falls back to Halter and never sees the KRT-thresholds panel.
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

    // The org-unit/user lookups feed management-only modals (non-special account creation links a
    // Bereich/OL; manual holder registration picks a user). An employee may only create SPECIAL
    // accounts (no org unit) and cannot register holders, so those backend reads — themselves
    // management-gated — are skipped for a plain employee (REQ-BANK-030).
    if (management) {
      List<OrgUnitMembershipOptionDto> orgUnits =
          backendApiClient.get(
              "/api/v1/org-units/active-all-kinds",
              new ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>() {});
      List<UserReferenceDto> users =
          backendApiClient.get(
              "/api/v1/users/lookup", new ParameterizedTypeReference<List<UserReferenceDto>>() {});
      model.addAttribute(
          "orgUnits", orgUnits == null ? List.<OrgUnitMembershipOptionDto>of() : orgUnits);
      model.addAttribute("users", users == null ? List.<UserReferenceDto>of() : users);
    } else {
      model.addAttribute("orgUnits", List.<OrgUnitMembershipOptionDto>of());
      model.addAttribute("users", List.<UserReferenceDto>of());
    }
    // No transfer-fee rate is fetched here: the only booking modal on this page is the
    // holder→holder
    // Umbuchung, which is fee-free (REQ-BANK-031, ADR-0052), so it needs no live fee preview.
    return "bank-manage";
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
