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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestViewDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the page of one booking request (REQ-BANK-068): its details, the one way to the surface
 * where the caller acts on it, the fragment the live update swaps, and the empty state for a
 * request the backend does not show the caller.
 */
@SpringBootTest
class BankRequestPageControllerMvcTest {

  private static final UUID ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
  private static final String URI = "/api/v1/bank/org-units/requests/" + ID;

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;
  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = {"BANK_EMPLOYEE"})
  void aConfirmableRequestLeadsTheStaffIntoTheQueue() throws Exception {
    stub(new BankBookingRequestViewDto(request(false, true), false, true, false));

    mockMvc
        .perform(get("/bank/requests/" + ID))
        .andExpect(status().isOk())
        .andExpect(view().name("bank/bank-request"))
        .andExpect(content().string(Matchers.containsString("KB-0007")))
        .andExpect(content().string(Matchers.containsString("Staffel Nord")))
        .andExpect(content().string(Matchers.containsString("Fuel for the convoy")))
        .andExpect(content().string(Matchers.containsString("/bank/requests?focus=" + ID)))
        .andExpect(content().string(Matchers.containsString("bank-request-detail-results")))
        .andExpect(
            content()
                .string(Matchers.not(Matchers.containsString("bank-request-page-open-approval"))));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void anApproverIsLedToTheForeignRequestsAndNotToTheQueue() throws Exception {
    stub(new BankBookingRequestViewDto(request(true, null), false, false, true));

    mockMvc
        .perform(get("/bank/requests/" + ID))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("bank-request-page-waiting")))
        .andExpect(content().string(Matchers.containsString("focus=" + ID + "#tab=fremde")))
        .andExpect(
            content()
                .string(Matchers.not(Matchers.containsString("bank-request-page-open-queue"))));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void theFragmentResolvesForTheLiveUpdate() throws Exception {
    stub(new BankBookingRequestViewDto(request(false, null), true, false, false));

    mockMvc
        .perform(get("/bank/requests/" + ID).param("fragment", "requestDetail"))
        .andExpect(status().isOk())
        .andExpect(view().name("bank/bank-request :: requestDetail"))
        .andExpect(content().string(Matchers.containsString("#tab=antraege")));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void aRequestTheBackendDoesNotShowRendersTheEmptyState() throws Exception {
    stub(null);

    mockMvc
        .perform(get("/bank/requests/" + ID))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("bank-request-page-missing")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("bank-request-page\""))));
  }

  /**
   * Answers the single-request read.
   *
   * @param view the backend's answer, or {@code null}
   */
  private void stub(BankBookingRequestViewDto view) {
    when(backendApiClient.get(eq(URI), eq(BankBookingRequestViewDto.class))).thenReturn(view);
  }

  /**
   * A pending withdrawal on the Staffel Nord account.
   *
   * @param approvalOpen whether it waits for the Bankleitung's approval
   * @param mayConfirm the backend's confirm judgement, or {@code null}
   * @return the request
   */
  private static @NotNull BankBookingRequestDto request(boolean approvalOpen, Boolean mayConfirm) {
    return new BankBookingRequestDto(
        ID,
        UUID.randomUUID(),
        "KB-0007",
        "Staffel Nord",
        UUID.randomUUID(),
        "Staffel Nord",
        "NORD",
        "WITHDRAWAL",
        new BigDecimal("2500"),
        null,
        "Fuel for the convoy",
        null,
        "PENDING",
        "nova",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.parse("2026-11-06T09:30:00Z"),
        null,
        null,
        approvalOpen,
        null,
        approvalOpen ? "BANK_MANAGEMENT" : null,
        false,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0L,
        mayConfirm);
  }
}
