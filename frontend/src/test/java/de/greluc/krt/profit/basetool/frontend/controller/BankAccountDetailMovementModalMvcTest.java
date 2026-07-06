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
 * MVC-level render test guarding the account-detail "Kontobewegung" movement-modal capability gate
 * (REQ-BANK-017). The modal's presence marker is the fragment root's {@code
 * id="bank-movement-modal"} ({@code fragments/bank-movement-modal :: movementModal}). It must
 * render only when the caller can do at least one of deposit/withdraw/transfer.
 *
 * <p>This pins the fix for the Thymeleaf attribute-precedence bug where the guard {@code caps !=
 * null and (caps.canDeposit() or caps.canWithdraw() or caps.canTransfer())} sat on the same element
 * as {@code th:replace}: since {@code th:replace} (precedence 1) runs before {@code th:if} (3), the
 * guard was dead and the modal rendered unconditionally. A view-only employee (all caps false) must
 * see no modal; a caller with any cap must see it. A pure unit test only pins the view name and
 * would miss this template-only regression.
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
   * Positive control: a caller with at least one movement capability (here canWithdraw) must get
   * the movement modal — the fragment root's {@code id="bank-movement-modal"} is present in the
   * full-page HTML. This proves the marker is not simply always-absent and that the fix still
   * renders the modal when the guard passes.
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
   * Stubs the single backend read the full-page render strictly needs — the account-detail
   * aggregate — with a minimal, non-null {@link BankAccountDetailDto} so the {@code <main
   * th:if="${detail != null}">} body renders and the movement-modal guard is actually evaluated.
   * Every other read the handler makes (holders, transfer-target accounts, user lookup, org-unit
   * picklist, transfer-fee rate) is left unstubbed: Mockito returns {@code null} and the controller
   * null-guards each, so the page still renders HTTP 200.
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
    // The booking-history page read uses the ParameterizedTypeReference overload; leaving it to
    // return null lets the template render its empty state, which is enough for this guard test.
    when(backendApiClient.get(
            startsWith("/api/v1/bank/accounts/" + id + "/transactions"), anyTypeRef()))
        .thenReturn(null);
  }
}
