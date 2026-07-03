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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class BankManagePageControllerTest {

  /** A bank-management authentication so the controller takes the full (any-type) perspective. */
  private static Authentication management() {
    return new TestingAuthenticationToken(
        "mgmt", "pw", "ROLE_BANK_EMPLOYEE", "ROLE_BANK_MANAGEMENT");
  }

  /** A plain bank-employee authentication (no management role) — the self-link perspective. */
  private static Authentication employee() {
    return new TestingAuthenticationToken("emp", "pw", "ROLE_BANK_EMPLOYEE");
  }

  /**
   * An OIDC principal whose {@code sub} is the given value. The frontend deliberately exposes the
   * username as {@link Authentication#getName()} (user-name-attribute = preferred_username), so the
   * holder self-link must read the {@code sub} from the principal, never the authentication name.
   *
   * @param sub the Keycloak subject (UUID) to expose via {@link OidcUser#getSubject()}
   * @return a mock OIDC user returning {@code sub} as its subject
   */
  private static OidcUser oidcUser(String sub) {
    OidcUser principal = mock(OidcUser.class);
    when(principal.getSubject()).thenReturn(sub);
    return principal;
  }

  @Test
  void manage_ShouldDefaultToHolderTabAndFillModel() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    BankAccountDto account =
        new BankAccountDto(
            UUID.randomUUID(),
            "KB-0001",
            "Staffel IRIDIUM",
            "ORG_UNIT",
            "ACTIVE",
            null,
            null,
            BigDecimal.ZERO,
            null,
            null,
            null,
            0L,
            Instant.parse("2026-01-15T10:00:00Z"));
    BankHolderDto holder =
        new BankHolderDto(
            UUID.randomUUID(), UUID.randomUUID(), "greluc", true, BigDecimal.ZERO, false, 0L);
    when(backendApiClient.get(eq("/api/v1/bank/accounts?size=500"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(account), 0, 500, 1, 1, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(List.of(holder));
    when(backendApiClient.get(eq("/api/v1/org-units/active"), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef())).thenReturn(List.of());

    // When
    String view =
        controller.manage(null, null, management(), oidcUser(UUID.randomUUID().toString()), model);

    // Then
    assertEquals("bank-manage", view);
    // Halter is the default-open tab (it sits first/left in the tab nav).
    assertEquals("halter", model.getAttribute("activeTab"));
    // The holder→holder Umbuchung is fee-free (REQ-BANK-031, ADR-0052), so this page fetches no
    // transfer-fee rate and exposes no transferFeeRate attribute.
    assertNull(model.getAttribute("transferFeeRate"));
    List<BankAccountDto> accounts = (List<BankAccountDto>) model.getAttribute("accounts");
    assertNotNull(accounts);
    assertEquals(1, accounts.size());
    List<BankHolderDto> holders = (List<BankHolderDto>) model.getAttribute("holders");
    assertNotNull(holders);
    assertEquals("greluc", holders.get(0).handle());
  }

  @Test
  void manage_explicitKontenTab_selectsAccountsTab() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef())).thenReturn(null);

    // When: an explicit ?tab=konten opens the accounts tab (the non-default branch).
    controller.manage("konten", null, management(), oidcUser(UUID.randomUUID().toString()), model);

    // Then
    assertEquals("konten", model.getAttribute("activeTab"));
  }

  @Test
  void manage_ShouldSelectHolderTabAndSurviveNullBackendResponses() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef())).thenReturn(null);

    // When
    String view =
        controller.manage(
            "HALTER", null, management(), oidcUser(UUID.randomUUID().toString()), model);

    // Then
    assertEquals("bank-manage", view);
    assertEquals("halter", model.getAttribute("activeTab"));
    assertEquals(List.of(), model.getAttribute("accounts"));
    assertEquals(List.of(), model.getAttribute("holders"));
    assertEquals(List.of(), model.getAttribute("orgUnits"));
    assertEquals(List.of(), model.getAttribute("users"));
  }

  // covers REQ-FE-005 (#579) — an in-place re-render (fragment=manageBody) returns only the tab-nav
  // + active panel fragment and skips the creation-modal lookups (org-units, users) that live
  // outside the swapped region.
  @Test
  void manage_fragmentManageBody_rendersOnlyBodyFragment_andSkipsModalLookups() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(eq("/api/v1/bank/accounts?size=500"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 500, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef())).thenReturn(List.of());

    // When
    String view =
        controller.manage(
            "halter", "manageBody", management(), oidcUser(UUID.randomUUID().toString()), model);

    // Then
    assertEquals("bank-manage :: manageBody", view);
    assertEquals("halter", model.getAttribute("activeTab"));
    assertNotNull(model.getAttribute("accounts"));
    assertNotNull(model.getAttribute("holders"));
    // The fragment path must not load the creation-modal lookups.
    verify(backendApiClient, never()).get(eq("/api/v1/org-units/active"), anyTypeRef());
    verify(backendApiClient, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
  }

  // Regression for the holder self-link bug (#876 follow-up): a plain bank employee never saw the
  // link to their own holder because the controller exposed authentication.getName() (the
  // preferred_username) as selfUserId while the template compares it against the holder's userId
  // (== app_user.id == the OIDC sub). The selfUserId attribute must carry the principal's sub, not
  // the username — same carve-out as MissionPageController#authUserId (REQ-BANK-032).
  @Test
  void manage_ShouldExposeOidcSubjectAsSelfUserId_NotPreferredUsername() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    String sub = "33333333-3333-3333-3333-333333333333";
    BankHolderDto ownHolder =
        new BankHolderDto(
            UUID.randomUUID(), UUID.fromString(sub), "emp", true, BigDecimal.ZERO, false, 0L);
    when(backendApiClient.get(eq("/api/v1/bank/accounts?size=500"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 500, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(List.of(ownHolder));

    // When — the employee's principal carries the sub; getName() ("emp") deliberately differs.
    controller.manage("halter", null, employee(), oidcUser(sub), model);

    // Then — selfUserId is the sub (matches the holder's userId), never the preferred_username.
    assertEquals(sub, model.getAttribute("selfUserId"));
  }
}
