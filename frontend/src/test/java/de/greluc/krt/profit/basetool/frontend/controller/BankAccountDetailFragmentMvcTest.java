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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * MVC-level render test for the {@code bank-account-detail :: bookings} AJAX fragment (REQ-FE-002):
 * proves the booking-history pager fragment actually resolves and renders one booking row + its
 * page-nav through the real {@code @moneyFormat} bean. A pure unit test only pins the view-name
 * string; this fails if the fragment selector is misspelled or the booking markup breaks.
 */
@SpringBootTest
class BankAccountDetailFragmentMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  // covers REQ-FE-002 — fragment=bookings renders only the booking-history block: the booking row
  // and page-nav are present, but the swap-target wrapper and the page's modals (outside the
  // fragment) are not.
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void accountDetail_fragmentBookings_rendersOnlyBookingsFragment() throws Exception {
    UUID accountId = UUID.randomUUID();
    BankBookingDto booking =
        new BankBookingDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "DEPOSIT",
            new BigDecimal("250000"),
            "alpha",
            "Fragment note",
            "Fragment reason",
            Instant.parse("2026-06-10T18:30:00Z"),
            null,
            null,
            null,
            null,
            false,
            BigDecimal.ZERO,
            null,
            null);
    // Two pages so the embedded pager renders.
    when(backendApiClient.get(contains("/transactions"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(booking), 0, 20, 25L, 2, List.of()));

    mockMvc
        .perform(get("/bank/accounts/" + accountId).param("fragment", "bookings"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Fragment note")))
        // REQ-BANK-045: the Begründung column renders alongside the note.
        .andExpect(content().string(containsString("Fragment reason")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("/bank/accounts/" + accountId + "?page=1")))
        // Wrapper div and the page's modals live outside the fragment.
        .andExpect(content().string(not(containsString("id=\"bank-bookings-results\""))))
        .andExpect(content().string(not(containsString("bank-statement-submit"))));
  }
}
