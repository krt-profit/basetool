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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
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
import org.springframework.util.StringUtils;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC render test for the holder detail page and its {@code holderBookings} fragment
 * (REQ-BANK-032), including the {@code DEPOSIT} and {@code HOLDER_TRANSFER} annotations rendered
 * through the real {@code @moneyFormat} bean.
 */
@SpringBootTest
class BankHolderDetailFragmentMvcTest {

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

  /** A deposit row annotated with the account it moved on (positive: received into the stash). */
  private static BankHolderBookingDto depositRow() {
    return new BankHolderBookingDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "DEPOSIT",
        new BigDecimal("250000"),
        "Mission payout",
        Instant.parse("2026-06-10T18:30:00Z"),
        null,
        "KB-0001",
        "Staffel IRIDIUM",
        null,
        BigDecimal.ZERO);
  }

  /**
   * A holder→holder Umbuchung row annotated with the counter holder (negative: paid out). It is
   * fee-free (REQ-BANK-031, ADR-0052): the internal Umbuchung carries no transfer fee.
   */
  private static BankHolderBookingDto umbuchungRow() {
    return new BankHolderBookingDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "HOLDER_TRANSFER",
        new BigDecimal("-500"),
        "Reconcile",
        Instant.parse("2026-06-11T18:30:00Z"),
        null,
        null,
        null,
        "carol",
        BigDecimal.ZERO);
  }

  /**
   * A withdrawal's outgoing holder leg of 500 carrying a fee of 3 (REQ-BANK-033, ADR-0052); the
   * history shows the 497 received.
   */
  private static BankHolderBookingDto withdrawalRow() {
    return new BankHolderBookingDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "WITHDRAWAL",
        new BigDecimal("-500"),
        "Auszahlung",
        Instant.parse("2026-06-12T18:30:00Z"),
        null,
        "KB-0001",
        "Staffel IRIDIUM",
        null,
        new BigDecimal("3"));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void holderDetail_rendersHeaderAndHistory() throws Exception {
    UUID holderId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/bank/holders/" + holderId), eq(BankHolderDto.class)))
        .thenReturn(
            new BankHolderDto(
                holderId, UUID.randomUUID(), "greluc", true, new BigDecimal("1000000"), false, 0L));
    when(backendApiClient.get(contains("/transactions"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(depositRow(), umbuchungRow(), withdrawalRow()), 0, 20, 3L, 1, List.of()));

    String body =
        mockMvc
            .perform(get("/bank/holders/" + holderId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("greluc")))
            .andExpect(content().string(containsString("Mission payout")))
            .andExpect(content().string(containsString("KB-0001")))
            .andExpect(content().string(containsString("carol")))
            .andExpect(content().string(containsString("497")))
            .andExpect(content().string(containsString("bank-holder-back-link")))
            .andExpect(content().string(containsString("bank-holder-balance-calc")))
            .andExpect(content().string(containsString("bank-holder-balance-input")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertEquals(1, StringUtils.countOccurrencesOf(body, "bank-holder-booking-fee"));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void holderDetail_fragmentHolderBookings_rendersOnlyHistoryFragment() throws Exception {
    UUID holderId = UUID.randomUUID();
    when(backendApiClient.get(contains("/transactions"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(umbuchungRow()), 0, 20, 25L, 2, List.of()));

    mockMvc
        .perform(get("/bank/holders/" + holderId).param("fragment", "holderBookings"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Reconcile")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("/bank/holders/" + holderId + "?page=1")))
        .andExpect(content().string(not(containsString("id=\"bank-holder-bookings-results\""))))
        .andExpect(content().string(not(containsString("bank-holder-back-link"))))
        .andExpect(content().string(not(containsString("bank-holder-balance-calc"))));
  }
}
