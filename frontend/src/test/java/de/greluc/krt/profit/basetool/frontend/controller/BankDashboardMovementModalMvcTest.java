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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * Pins the direct-booking movement-modal gating on the bank dashboard (REQ-BANK-017/-023) against
 * the Thymeleaf attribute-precedence trap: {@code th:if="${canBook}"} must NOT share an element
 * with {@code th:replace}, because {@code th:replace} (precedence 1) is processed before {@code
 * th:if} (3) — a same-element combination rendered the movement modal unconditionally, so a viewer
 * with no bookable (active) account still got the hidden modal in the DOM even though the CTA
 * button that opens it was correctly hidden. Since REQ-BANK-053/ADR-0104 the account pickers search
 * on demand, so {@code canBook} is derived from the already-loaded dashboard's active accounts in
 * {@link BankPageController} rather than a separate {@code /api/v1/bank/accounts} preload.
 */
@SpringBootTest
class BankDashboardMovementModalMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** A dashboard card of the given status so the grid renders and canBook can be exercised. */
  private BankDashboardAccountDto card(String status) {
    return new BankDashboardAccountDto(
        UUID.randomUUID(),
        "KB-0001",
        "Staffel IRIDIUM",
        "ORG_UNIT",
        status,
        new BigDecimal("1000"),
        BigDecimal.ZERO,
        List.of(),
        null,
        null,
        null);
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void dashboard_noBookableAccount_omitsMovementModal() throws Exception {
    // A dashboard whose only account is CLOSED -> no ACTIVE account -> canBook false. The movement
    // modal (and its CTA) must be absent. Pre-fix, the modal rendered unconditionally because th:if
    // shared the element with th:replace.
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(new BankDashboardDto(true, List.of(card("CLOSED")), null));

    mockMvc
        .perform(get("/bank"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(Matchers.not(Matchers.containsString("id=\"bank-movement-modal\""))))
        .andExpect(content().string(Matchers.not(Matchers.containsString("bank-movement-open"))));
  }

  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void dashboard_withBookableAccount_rendersMovementModal() throws Exception {
    // An ACTIVE account in the dashboard -> canBook true -> the CTA and the movement modal render
    // (positive control, so the gating does not over-suppress). No separate account fetch is
    // needed:
    // canBook is read from the dashboard itself, and the modal's account pickers search on demand.
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(new BankDashboardDto(true, List.of(card("ACTIVE")), null));

    mockMvc
        .perform(get("/bank"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("id=\"bank-movement-modal\"")))
        .andExpect(content().string(Matchers.containsString("bank-movement-open")))
        // REQ-FE-017/ADR-0104: the modal's source + destination account pickers are remote-search
        // comboboxes and preload no account roster.
        .andExpect(
            content()
                .string(Matchers.containsString("data-krt-combobox=\"remote-bank-accounts\"")));
  }
}
