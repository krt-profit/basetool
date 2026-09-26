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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import org.mockito.ArgumentCaptor;
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
   * Creates an OIDC principal with the given {@code sub}, which the holder self-link must read
   * instead of {@link Authentication#getName()}.
   *
   * @param sub the Keycloak subject (UUID) to expose via {@link OidcUser#getSubject()}
   * @return a mock OIDC user returning {@code sub} as its subject
   */
  private static OidcUser oidcUser(String sub) {
    OidcUser principal = mock(OidcUser.class);
    when(principal.getSubject()).thenReturn(sub);
    return principal;
  }

  /** Builds a minimal account DTO of the given number/name/type/status for the paged list. */
  private static BankAccountDto account(String no, String name, String type, String status) {
    return new BankAccountDto(
        UUID.randomUUID(),
        no,
        name,
        type,
        status,
        null,
        null,
        BigDecimal.ZERO,
        null,
        null,
        null,
        0L,
        Instant.parse("2026-01-15T10:00:00Z"));
  }

  @Test
  void manage_ShouldDefaultToHolderTabAndFillPagedModel() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    BankAccountDto acc = account("KB-0001", "Staffel IRIDIUM", "ORG_UNIT", "ACTIVE");
    BankHolderDto holder =
        new BankHolderDto(
            UUID.randomUUID(), UUID.randomUUID(), "greluc", true, BigDecimal.ZERO, false, 0L);
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(acc), 0, 25, 1, 1, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(List.of(holder));

    String view =
        controller.manage(
            null, null, null, null, management(), oidcUser(UUID.randomUUID().toString()), model);

    assertEquals("bank-manage", view);
    assertEquals("halter", model.getAttribute("activeTab"));
    assertNull(model.getAttribute("transferFeeRate"));
    List<BankAccountDto> accounts = (List<BankAccountDto>) model.getAttribute("accounts");
    assertNotNull(accounts);
    assertEquals(1, accounts.size());
    assertEquals(1L, model.getAttribute("accountCount"));
    assertNotNull(model.getAttribute("accountsPage"));
    assertNotNull(model.getAttribute("pageSizes"));
    assertEquals("/bank/manage?tab=konten", model.getAttribute("accountsPaginationBaseUrl"));
    List<BankHolderDto> holders = (List<BankHolderDto>) model.getAttribute("holders");
    assertNotNull(holders);
    assertEquals("greluc", holders.get(0).handle());
  }

  @Test
  void manage_paginates_clampsUnknownSize_andForwardsPageAndSort() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 2, 50, 130, 3, Collections.emptyList()));

    controller.manage(
        "konten", 2, 50, null, management(), oidcUser(UUID.randomUUID().toString()), model);

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
        .get(uriCaptor.capture(), anyTypeRef());
    assertTrue(
        uriCaptor.getAllValues().stream()
            .anyMatch(
                u -> u.contains("page=2") && u.contains("size=50") && u.contains("sort=name")),
        "the paged list request carries page=2&size=50&sort=name,asc");
    assertTrue(
        uriCaptor.getAllValues().stream().anyMatch(u -> u.contains("type=CARTEL")),
        "the CARTEL account is fetched via a type-filtered lookup");
  }

  @Test
  void manage_rejectsNonWhitelistedSize_fallsBackToDefault() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 25, 0, 0, Collections.emptyList()));

    controller.manage(
        "konten", null, 999, null, management(), oidcUser(UUID.randomUUID().toString()), model);

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
        .get(uriCaptor.capture(), anyTypeRef());
    assertTrue(
        uriCaptor.getAllValues().stream()
            .anyMatch(u -> u.contains("page=0") && u.contains("size=25")),
        "a non-whitelisted size falls back to the default 25");
    assertTrue(
        uriCaptor.getAllValues().stream().noneMatch(u -> u.contains("size=999")),
        "the bogus size is never forwarded");
  }

  @Test
  void manage_explicitKontenTab_selectsAccountsTab() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef())).thenReturn(null);

    controller.manage(
        "konten", null, null, null, management(), oidcUser(UUID.randomUUID().toString()), model);

    assertEquals("konten", model.getAttribute("activeTab"));
  }

  @Test
  void manage_ShouldSelectHolderTabAndSurviveNullBackendResponses() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef())).thenReturn(null);

    String view =
        controller.manage(
            "HALTER",
            null,
            null,
            null,
            management(),
            oidcUser(UUID.randomUUID().toString()),
            model);

    assertEquals("bank-manage", view);
    assertEquals("halter", model.getAttribute("activeTab"));
    assertEquals(List.of(), model.getAttribute("accounts"));
    assertEquals(0L, model.getAttribute("accountCount"));
    assertEquals(List.of(), model.getAttribute("holders"));
    assertEquals(List.of(), model.getAttribute("orgUnits"));
  }

  @Test
  void manage_fragmentManageBody_rendersOnlyBodyFragment_andSkipsModalLookups() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 25, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef())).thenReturn(List.of());

    String view =
        controller.manage(
            "halter",
            null,
            null,
            "manageBody",
            management(),
            oidcUser(UUID.randomUUID().toString()),
            model);

    assertEquals("bank-manage :: manageBody", view);
    assertEquals("halter", model.getAttribute("activeTab"));
    assertNotNull(model.getAttribute("accounts"));
    assertNotNull(model.getAttribute("holders"));
    verify(backendApiClient, never()).get(eq("/api/v1/org-units/active"), anyTypeRef());
  }

  @Test
  void manage_ShouldExposeOidcSubjectAsSelfUserId_NotPreferredUsername() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankManagePageController controller = new BankManagePageController(backendApiClient);
    Model model = new ConcurrentModel();
    String sub = "33333333-3333-3333-3333-333333333333";
    BankHolderDto ownHolder =
        new BankHolderDto(
            UUID.randomUUID(), UUID.fromString(sub), "emp", true, BigDecimal.ZERO, false, 0L);
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 25, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(List.of(ownHolder));

    controller.manage("halter", null, null, null, employee(), oidcUser(sub), model);

    assertEquals(sub, model.getAttribute("selfUserId"));
  }
}
