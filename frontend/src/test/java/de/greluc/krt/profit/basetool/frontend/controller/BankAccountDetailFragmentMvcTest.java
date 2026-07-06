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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalancePointDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalanceSeriesDto;
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
        // REQ-BANK-045: the Begründung and Notiz render in the expandable per-row detail sub-row.
        .andExpect(content().string(containsString("Fragment reason")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        // REQ-BANK-051: the pager link carries page=1; paginationBaseUrl now also carries the
        // period.
        .andExpect(content().string(containsString("page=1")))
        // REQ-BANK-051: the page-size picker (10/50/100) renders once the total exceeds the
        // smallest.
        .andExpect(content().string(containsString("page-size-picker")))
        // Wrapper div and the page's modals live outside the fragment.
        .andExpect(content().string(not(containsString("id=\"bank-bookings-results\""))))
        .andExpect(content().string(not(containsString("bank-statement-submit"))));
  }

  // covers REQ-BANK-049 — fragment=balanceChart renders only the balance-chart block: the preset
  // range selector (default 90d active), the inline SVG line + a target-line legend when a target
  // is
  // set. The swap-target wrapper (outside the fragment) is not.
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void accountDetail_fragmentBalanceChart_rendersRangeSelectorAndChart() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(backendApiClient.get(contains("/balance-series"), eq(BankBalanceSeriesDto.class)))
        .thenReturn(
            new BankBalanceSeriesDto(
                List.of(
                    new BankBalancePointDto(
                        Instant.parse("2026-06-01T00:00:00Z"), new BigDecimal("100000")),
                    new BankBalancePointDto(
                        Instant.parse("2026-06-15T00:00:00Z"), new BigDecimal("300000"))),
                new BigDecimal("500000")));

    mockMvc
        .perform(get("/bank/accounts/" + accountId).param("fragment", "balanceChart"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("bank-chart-range-90d")))
        .andExpect(content().string(containsString("data-testid=\"bank-chart\"")))
        .andExpect(content().string(containsString("bank-chart-line")))
        .andExpect(content().string(containsString("bank-chart-target-legend")))
        .andExpect(content().string(not(containsString("id=\"bank-chart-results\""))));
  }

  // covers REQ-BANK-049 — an empty series renders the chart's empty state, not the SVG.
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void accountDetail_fragmentBalanceChart_emptySeries_rendersEmptyState() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(backendApiClient.get(contains("/balance-series"), eq(BankBalanceSeriesDto.class)))
        .thenReturn(new BankBalanceSeriesDto(List.of(), null));

    mockMvc
        .perform(get("/bank/accounts/" + accountId).param("fragment", "balanceChart"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("bank-chart-empty")))
        .andExpect(content().string(not(containsString("data-testid=\"bank-chart\""))));
  }
}
