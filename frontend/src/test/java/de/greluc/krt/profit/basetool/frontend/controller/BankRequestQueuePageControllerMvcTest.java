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

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Renders the bank-staff confirmation queue: the request table and the confirm and reject modals
 * render, the page is gated to {@code BANK_EMPLOYEE}, and the {@code requestQueue} fragment
 * resolves.
 */
@SpringBootTest
class BankRequestQueuePageControllerMvcTest {

  private static final String HOLDERS_URI = "/api/v1/bank/holders";

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

  private void stubData() {
    BankBookingRequestDto request =
        new BankBookingRequestDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "KB-0001",
            "Staffel IRIDIUM",
            UUID.randomUUID(),
            "IRIDIUM",
            "IRI",
            "DEPOSIT",
            new BigDecimal("5000"),
            "from sale",
            null,
            null,
            "PENDING",
            "officerX",
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-06-17T14:02:00Z"),
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0L);
    PageResponse<BankBookingRequestDto> page =
        new PageResponse<>(List.of(request), 0, 200, 1, 1, List.of());
    BankHolderDto holder =
        new BankHolderDto(
            UUID.randomUUID(), UUID.randomUUID(), "greluc", true, BigDecimal.ZERO, false, 0L);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(startsWith("/api/v1/bank/requests"), anyTypeRef())).thenReturn(page);
    when(backendApiClient.get(eq(HOLDERS_URI), anyTypeRef())).thenReturn(List.of(holder));
  }

  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void queue_rendersRequestRowConfirmAndRejectModals() throws Exception {
    stubData();

    mockMvc
        .perform(get("/bank/requests"))
        .andExpect(status().isOk())
        .andExpect(view().name("bank-requests"))
        .andExpect(content().string(Matchers.containsString("KB-0001")))
        .andExpect(content().string(Matchers.containsString("Staffel IRIDIUM")))
        .andExpect(content().string(Matchers.containsString("officerX")))
        .andExpect(content().string(Matchers.containsString("bank-request-confirm-btn")))
        .andExpect(content().string(Matchers.containsString("bank-confirm-request-modal")))
        .andExpect(content().string(Matchers.containsString("greluc")));
  }

  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void queue_fragmentViewResolves() throws Exception {
    stubData();

    mockMvc
        .perform(get("/bank/requests").param("fragment", "requestQueue"))
        .andExpect(status().isOk())
        .andExpect(view().name("bank-requests :: requestQueue"));
  }

  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void queue_defaultRendersPendingCheckboxActiveUserIdAndExpandableDetail() throws Exception {
    stubData();

    mockMvc
        .perform(get("/bank/requests"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("data-bank-status-filter-bar")))
        .andExpect(content().string(Matchers.containsString("data-user-id")))
        .andExpect(content().string(Matchers.containsString("data-bank-status-filter=\"PENDING\"")))
        .andExpect(content().string(Matchers.containsString("type=\"checkbox\"")))
        .andExpect(content().string(Matchers.containsString("checked=\"checked\"")))
        .andExpect(content().string(Matchers.containsString("bank-request-detail")))
        .andExpect(content().string(Matchers.containsString("from sale")));
  }

  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void queue_allFiltersOffShowsNoFilterHintAndNoTable() throws Exception {
    stubData();

    mockMvc
        .perform(get("/bank/requests").param("status", "NONE"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("bank-requests-nofilter")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("bank-requests-table"))));
  }

  @Test
  @WithMockUser(roles = {"OFFICER"})
  void queue_nonStaffIsForbidden() throws Exception {
    mockMvc.perform(get("/bank/requests")).andExpect(status().isForbidden());
  }

  /**
   * With an active account, the Kontobewegung CTA and the movement modal render with type selector,
   * source-account picker and field hints (REQ-BANK-017/-023).
   */
  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void queue_withActiveAccounts_rendersMovementCtaAndModal() throws Exception {
    stubData();
    BankAccountDto account =
        new BankAccountDto(
            UUID.randomUUID(),
            "KB-0001",
            "Staffel IRIDIUM",
            "ORG_UNIT",
            "ACTIVE",
            null,
            null,
            new BigDecimal("1000"),
            null,
            null,
            null,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"));
    PageResponse<BankAccountDto> accountsPage =
        new PageResponse<>(List.of(account), 0, 500, 1, 1, List.of());
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts"), anyTypeRef()))
        .thenReturn(accountsPage);

    mockMvc
        .perform(get("/bank/requests"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("bank-movement-open")))
        .andExpect(content().string(Matchers.containsString("id=\"bank-movement-modal\"")))
        .andExpect(content().string(Matchers.containsString("bank-movement-type")))
        .andExpect(content().string(Matchers.containsString("bank-movement-staff-note")))
        .andExpect(content().string(Matchers.containsString("bank-movement-source-account")))
        .andExpect(
            content().string(Matchers.containsString("data-krt-combobox=\"remote-bank-accounts\"")))
        .andExpect(
            content().string(Matchers.containsString("data-role=\"bank-movement-account-label\"")))
        .andExpect(content().string(Matchers.containsString("data-label-deposit")))
        .andExpect(content().string(Matchers.containsString("field-hint-marker")))
        .andExpect(content().string(Matchers.containsString("data-movement-types")))
        .andExpect(
            content().string(Matchers.containsString("data-krt-combobox=\"remote-bank-users\"")));
    verify(backendApiClient, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
  }

  /**
   * With no active account, {@code canBook} is false and neither the movement modal nor its CTA
   * renders.
   */
  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void queue_noActiveAccounts_omitsMovementModal() throws Exception {
    stubData();

    mockMvc
        .perform(get("/bank/requests"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("id=\"bank-movement-modal\""))))
        .andExpect(content().string(Matchers.not(Matchers.containsString("bank-movement-open"))));
  }

  /**
   * REQ-BANK-054: the request confirmation modal offers the "Notiz Bankmitarbeiter" field, so the
   * employee can record internal context for the booking the confirmation produces. (The movement
   * modal's copy is asserted by the canBook test above, which owns that fixture.)
   */
  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void bankRequests_confirmModalOffersTheStaffNoteField() throws Exception {
    mockMvc
        .perform(get("/bank/requests"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("bank-confirm-staff-note")))
        .andExpect(content().string(Matchers.containsString("name=\"staffNote\"")));
  }
}
