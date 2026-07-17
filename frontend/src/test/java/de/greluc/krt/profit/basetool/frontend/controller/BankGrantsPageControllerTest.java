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
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class BankGrantsPageControllerTest {

  private static BankGrantDto grant(UUID userId, String handle, UUID accountId) {
    return new BankGrantDto(
        userId, handle, accountId, "KB-0001", "Staffel IRIDIUM", true, false, false, true, 0L);
  }

  /** A minimal account-detail payload so the controller can seed the filter combobox's account. */
  private static BankAccountDetailDto detail(UUID accountId) {
    BankAccountDto account =
        new BankAccountDto(
            accountId,
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
    return new BankAccountDetailDto(
        account,
        BigDecimal.ZERO,
        0,
        new BankCapabilitiesDto(false, false, false, false),
        new BankApprovalLimitsDto(
            false, false, false, false, List.of(), Map.of(), null, null, List.of()));
  }

  @Test
  void grants_ShouldFilterByAccount_andSeedSelectedAccountForCombobox() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankGrantsPageController controller = new BankGrantsPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID accountId = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(List.of(grant(user, "alpha", accountId)));
    // The account roster is no longer preloaded (remote-bank-accounts combobox); only the selected
    // account is resolved for the filter's edit-mode seed.
    when(backendApiClient.get(
            eq("/api/v1/bank/accounts/" + accountId), eq(BankAccountDetailDto.class)))
        .thenReturn(detail(accountId));

    // When
    String view = controller.grants(null, accountId, null, null, model);

    // Then
    assertEquals("bank-grants", view);
    assertEquals(Boolean.FALSE, model.getAttribute("byEmployee"));
    BankAccountDto selected = (BankAccountDto) model.getAttribute("selectedAccount");
    assertNotNull(selected);
    assertEquals("KB-0001", selected.accountNo());
    verify(backendApiClient).get(eq("/api/v1/bank/grants?accountId=" + accountId), anyTypeRef());
    // No full account roster is preloaded (the picker searches on demand).
    verify(backendApiClient, never()).get(startsWith("/api/v1/bank/accounts?"), anyTypeRef());
  }

  @Test
  void grants_ShouldFilterByUserInEmployeeViewAndCollectGrantees() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankGrantsPageController controller = new BankGrantsPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID userId = UUID.randomUUID();
    UUID otherUser = UUID.randomUUID();
    List<BankGrantDto> allGrants =
        List.of(
            grant(userId, "alpha", UUID.randomUUID()),
            grant(userId, "alpha", UUID.randomUUID()),
            grant(otherUser, "bravo", UUID.randomUUID()));
    when(backendApiClient.get(eq("/api/v1/bank/grants?userId=" + userId), anyTypeRef()))
        .thenReturn(List.of(allGrants.get(0)));
    when(backendApiClient.get(eq("/api/v1/bank/grants"), anyTypeRef())).thenReturn(allGrants);

    // When
    controller.grants("employee", null, userId, null, model);

    // Then
    assertEquals(Boolean.TRUE, model.getAttribute("byEmployee"));
    assertEquals(userId, model.getAttribute("selectedUserId"));
    // The per-employee view resolves no account (the account seed is account-view only).
    assertNull(model.getAttribute("selectedAccount"));
    Map<UUID, String> grantees = (Map<UUID, String>) model.getAttribute("grantees");
    assertNotNull(grantees);
    assertEquals(2, grantees.size());
    assertEquals("alpha", grantees.get(userId));
    assertEquals("bravo", grantees.get(otherUser));
  }

  // covers REQ-FE-005 (#579) — an in-place re-render (fragment=grantsMatrix) returns only the
  // matrix fragment honouring the active filter, and skips the all-grants / account-seed lookups
  // that feed the filter selectors and the create modal (all outside the swapped region).
  @Test
  void grants_fragmentGrantsMatrix_rendersOnlyMatrixFragment_andSkipsFilterAndModalLookups() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    BankGrantsPageController controller = new BankGrantsPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID accountId = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/bank/grants?accountId=" + accountId), anyTypeRef()))
        .thenReturn(List.of(grant(user, "alpha", accountId)));

    // When
    String view = controller.grants(null, accountId, null, "grantsMatrix", model);

    // Then
    assertEquals("bank-grants :: grantsMatrix", view);
    List<BankGrantDto> grants = (List<BankGrantDto>) model.getAttribute("grants");
    assertNotNull(grants);
    assertEquals(1, grants.size());
    // The fragment path must not load the filter selectors / create-modal lookups or the account
    // seed.
    verify(backendApiClient, never())
        .get(eq("/api/v1/bank/accounts/" + accountId), eq(BankAccountDetailDto.class));
    verify(backendApiClient, never()).get(eq("/api/v1/bank/grants"), anyTypeRef());
  }
}
