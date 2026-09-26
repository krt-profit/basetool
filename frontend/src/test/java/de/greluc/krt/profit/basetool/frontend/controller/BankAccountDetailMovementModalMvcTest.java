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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankApprovalLimitsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * MVC render test for the account-detail movement-modal gate (REQ-BANK-017): the modal ({@code
 * id="bank-movement-modal"}) renders only when the caller can deposit, withdraw or transfer, which
 * requires the guard not to share an element with {@code th:replace}.
 */
@SpringBootTest
class BankAccountDetailMovementModalMvcTest {

  /** Presence marker of the movement modal — the fragment root's rendered {@code id}. */
  private static final String MOVEMENT_MODAL_MARKER = "id=\"bank-movement-modal\"";

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  /** Builds a Spring-Security-aware {@link MockMvc} against the live web application context. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * A view-only employee (canDeposit/canWithdraw/canTransfer all false) must NOT get the movement
   * modal: the outer {@code th:block} guard suppresses the {@code th:replace}, so the fragment
   * root's {@code id="bank-movement-modal"} is absent from the full-page HTML.
   *
   * @throws Exception if the request dispatch fails
   */
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void accountDetail_viewOnlyCaps_omitsMovementModal() throws Exception {
    UUID id = UUID.randomUUID();
    stubDetail(id, new BankCapabilitiesDto(false, false, false, false));

    String html =
        mockMvc.perform(get("/bank/accounts/" + id)).andReturn().getResponse().getContentAsString();

    assertThat(html).doesNotContain(MOVEMENT_MODAL_MARKER);
  }

  /**
   * A caller with at least one movement capability gets the movement modal.
   *
   * @throws Exception if the request dispatch fails
   */
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void accountDetail_withACap_rendersMovementModal() throws Exception {
    UUID id = UUID.randomUUID();
    stubDetail(id, new BankCapabilitiesDto(false, true, false, false));

    String html =
        mockMvc.perform(get("/bank/accounts/" + id)).andReturn().getResponse().getContentAsString();

    assertThat(html).contains(MOVEMENT_MODAL_MARKER);
  }

  /**
   * Stubs only the account-detail aggregate with a minimal {@link BankAccountDetailDto}, so the
   * page body renders and the movement-modal guard is evaluated; every other read returns {@code
   * null}.
   *
   * @param id the account id echoed into the detail's account
   * @param caps the caller's evaluated capabilities that drive the movement-modal guard
   */
  private void stubDetail(UUID id, BankCapabilitiesDto caps) {
    BankAccountDto account =
        new BankAccountDto(
            id,
            "KB-0001",
            "Staffel IRIDIUM",
            "ORG_UNIT",
            "ACTIVE",
            null,
            null,
            new BigDecimal("1850000"),
            null,
            null,
            null,
            0L,
            Instant.parse("2026-01-15T10:00:00Z"));
    BankApprovalLimitsDto limits =
        new BankApprovalLimitsDto(
            false, false, false, false, List.of(), Map.of(), null, null, List.of());
    BankAccountDetailDto detail =
        new BankAccountDetailDto(account, BigDecimal.ZERO, 0L, caps, limits);
    when(backendApiClient.get(
            startsWith("/api/v1/bank/accounts/" + id), eq(BankAccountDetailDto.class)))
        .thenReturn(detail);
    when(backendApiClient.get(
            startsWith("/api/v1/bank/accounts/" + id + "/transactions"), anyTypeRef()))
        .thenReturn(null);
  }
}
