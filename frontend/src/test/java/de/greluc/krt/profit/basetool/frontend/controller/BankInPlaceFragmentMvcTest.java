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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the three in-place swap fragments introduced for #579 (no-reload Bank conversion,
 * REQ-FE-005) so a Thymeleaf error in the {@code th:fragment} / {@code th:block} wrapping is caught
 * at build time (the pure-Mockito controller tests only assert the returned view name, not that the
 * fragment renders). Each test also pins the swap BOUNDARY: the manage/grants fragments must
 * exclude their creation modals (which stay outside the swapped region), while the account-detail
 * {@code accountBody} fragment must INCLUDE the booking modals (their distribution-derived holder
 * selects refresh with the money region).
 */
@SpringBootTest
class BankInPlaceFragmentMvcTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = {"BANK_MANAGEMENT"})
  void manage_fragmentManageBody_rendersPanelWithoutTheCreationModals() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    // The account list is paged now (REQ-BANK-053): the paged list request and the CARTEL lookup
    // both hit /api/v1/bank/accounts?…, so a single startsWith stub feeds both.
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts?"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(account(UUID.randomUUID(), "KB-0001", "ACTIVE", "0")),
                0,
                25,
                1,
                1,
                Collections.emptyList()));

    mockMvc
        // Pin the accounts tab explicitly: Halter is the default-open tab, so the account row only
        // renders in the swapped body when ?tab=konten is requested.
        .perform(get("/bank/manage").param("tab", "konten").param("fragment", "manageBody"))
        .andExpect(status().isOk())
        .andExpect(view().name("bank-manage :: manageBody"))
        // The tab-nav (with its counts) and the account row are inside the swapped body.
        .andExpect(content().string(Matchers.containsString("data-testid=\"bank-tab-accounts\"")))
        .andExpect(content().string(Matchers.containsString("data-testid=\"bank-account-row\"")))
        // The creation modal lives outside the fragment and must NOT be in the swapped HTML.
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("bank-create-account-modal"))));
  }

  @Test
  @WithMockUser(roles = {"BANK_MANAGEMENT"})
  void grants_fragmentGrantsMatrix_rendersMatrixWithoutTheCreateModal() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenReturn(List.of(grant(userId, "alpha", accountId)));

    mockMvc
        .perform(get("/bank/grants").param("fragment", "grantsMatrix"))
        .andExpect(status().isOk())
        .andExpect(view().name("bank-grants :: grantsMatrix"))
        .andExpect(content().string(Matchers.containsString("data-testid=\"bank-grant-row\"")))
        // The create-grant modal lives outside the fragment and must NOT be in the swapped HTML.
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("bank-grant-create-modal"))));
  }

  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void accountDetail_fragmentAccountBody_rendersBodyIncludingTheBookingModals() throws Exception {
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    BankAccountDto self = account(accountId, "KB-0001", "ACTIVE", "1000");
    BankAccountDetailDto detail =
        new BankAccountDetailDto(
            self,
            new BigDecimal("10"),
            1,
            new BankCapabilitiesDto(true, true, true, false),
            new de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto(
                false,
                false,
                false,
                false,
                java.util.List.of(),
                java.util.Map.of(),
                null,
                null,
                java.util.List.of()));
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(
            eq("/api/v1/bank/accounts/" + accountId), eq(BankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(contains("/transactions"), anyTypeRef()))
        .thenReturn(
            new PageResponse<BankBookingDto>(List.of(), 0, 20, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(
            List.of(
                new BankHolderDto(
                    holderId, UUID.randomUUID(), "alpha", true, BigDecimal.ZERO, false, 0L)));
    // No transfer-target roster is preloaded now — the destination is a server-side account-search
    // combobox (remote-bank-accounts, REQ-FE-017/ADR-0104).

    mockMvc
        .perform(get("/bank/accounts/" + accountId).param("fragment", "accountBody"))
        .andExpect(status().isOk())
        .andExpect(view().name("bank-account-detail :: accountBody"))
        // The facts strip + booking history are inside the swapped body.
        .andExpect(content().string(Matchers.containsString("data-testid=\"bank-balance\"")))
        .andExpect(content().string(Matchers.containsString("data-testid=\"bank-bookings-panel\"")))
        // Unlike manage/grants, the unified movement modal is part of the accountBody fragment so
        // its
        // distribution-derived holder selects refresh in the same swap (REQ-BANK-017, #997). The
        // single Kontobewegung entry point + its type selector replace the three old modals.
        .andExpect(content().string(Matchers.containsString("data-testid=\"bank-movement-open\"")))
        .andExpect(content().string(Matchers.containsString("id=\"bank-movement-modal\"")))
        .andExpect(content().string(Matchers.containsString("data-testid=\"bank-movement-type\"")))
        // Field hints are inline "?" tooltip markers, not sub-field text.
        .andExpect(content().string(Matchers.containsString("field-hint-marker")))
        // The fee-inclusive toggle (REQ-BANK-033, #999) renders in the modal, hidden by default
        // (bank.js reveals it only for a fee-bearing withdrawal/transfer) and is unchecked by
        // default: on-top is the default mode, fee-inclusive is opt-in.
        .andExpect(
            content()
                .string(Matchers.containsString("data-testid=\"bank-movement-fee-inclusive\"")))
        .andExpect(content().string(Matchers.containsString("data-fee-inclusive-row")))
        .andExpect(
            content()
                .string(
                    Matchers.not(
                        Matchers.containsString("name=\"feeInclusive\" value=\"true\" checked"))))
        // The external-counterparty toggle + free-text name + shared all-org-units source render
        // (REQ-BANK-044, #994); the "kein Tool-Account" toggle now sits AFTER the
        // Einzahler/Empfaenger
        // picker and BEFORE the shared Einheit row.
        .andExpect(
            content().string(Matchers.containsString("data-role=\"bank-cp-external-toggle\"")))
        .andExpect(content().string(Matchers.containsString("name=\"counterpartyExternalName\"")))
        .andExpect(content().string(Matchers.containsString("data-bank-all-orgunits")))
        .andExpect(
            content()
                .string(
                    Matchers.stringContainsInOrder(
                        List.of(
                            "data-counterparty-user",
                            "data-role=\"bank-cp-external-toggle\"",
                            "data-counterparty-orgunit"))))
        // #1193 follow-up: the counterparty user picker is a server-side searchable combobox
        // (remote-bank-users), so it carries the marker and preloads no user roster.
        .andExpect(
            content().string(Matchers.containsString("data-krt-combobox=\"remote-bank-users\"")))
        // REQ-FE-017/ADR-0104: the transfer-destination account picker is a server-side
        // account-search combobox (remote-bank-accounts) and preloads no account roster.
        .andExpect(
            content().string(Matchers.containsString("data-krt-combobox=\"remote-bank-accounts\"")))
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("id=\"bank-deposit-modal\""))));
    // The roster is fetched on demand, so the accountBody render issues no all-users lookup.
    verify(backendApiClient, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
  }

  private static BankAccountDto account(UUID id, String no, String status, String balance) {
    return new BankAccountDto(
        id,
        no,
        "Konto " + no,
        "ORG_UNIT",
        status,
        null,
        null,
        new BigDecimal(balance),
        null,
        null,
        null,
        0L,
        Instant.parse("2026-01-15T10:00:00Z"));
  }

  private static BankGrantDto grant(UUID userId, String handle, UUID accountId) {
    return new BankGrantDto(
        userId, handle, accountId, "KB-0001", "Staffel IRIDIUM", true, false, false, true, 0L);
  }
}
