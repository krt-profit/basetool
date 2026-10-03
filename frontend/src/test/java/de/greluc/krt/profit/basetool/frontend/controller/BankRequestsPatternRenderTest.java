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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * Renders the bank request queue on the list pattern (REQ-UI-027, REQ-BANK-023/-041): page head
 * without a filled primary action, the status segment with counts, the waiting counter, decision
 * buttons only on requests ready for the bank, the approval route and "wartet auf" on the others,
 * and the empty state.
 */
@SpringBootTest
class BankRequestsPatternRenderTest {

  private static final UUID OPEN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /bank/requests} in German with the given page for every request query.
   *
   * @param page the page the backend returns for each status query
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(
      @NotNull PageResponse<BankBookingRequestDto> page, @NotNull String query) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(startsWith("/api/v1/bank/requests"), anyTypeRef())).thenReturn(page);
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/bank/requests?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A pending booking request as the staff queue returns it.
   *
   * @param id the request id
   * @param type the movement type
   * @param requiresApproval whether the amount exceeds the requester's limit
   * @param granted whether the owner approval was granted in-app
   * @param approver the approver class, or {@code null}
   * @param justification the requester's justification, or {@code null}
   * @return the request
   */
  private static @NotNull BankBookingRequestDto request(
      @NotNull UUID id,
      @NotNull String type,
      boolean requiresApproval,
      boolean granted,
      @Nullable String approver,
      @Nullable String justification) {
    return new BankBookingRequestDto(
        id,
        UUID.randomUUID(),
        "KB-0007",
        "IRI Betriebskonto",
        UUID.randomUUID(),
        "IRIDIUM",
        "IRI",
        type,
        new BigDecimal("600000"),
        null,
        justification,
        null,
        "PENDING",
        "talon",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.parse("2026-10-01T12:00:00Z"),
        null,
        "TRANSFER".equals(type) ? "KB-0001" : null,
        requiresApproval,
        null,
        approver,
        granted,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        3L);
  }

  /** The queue renders head, segment, counter and the per-row decision state. */
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void rendersTheQueuePattern() throws Exception {
    PageResponse<BankBookingRequestDto> page =
        new PageResponse<>(
            List.of(
                request(UUID.randomUUID(), "DEPOSIT", false, false, null, null),
                request(OPEN_ID, "WITHDRAWAL", true, false, "BANK_MANAGEMENT", "Quartalsabgabe Q3"),
                request(UUID.randomUUID(), "TRANSFER", true, true, "RESPONSIBLE_HOLDER", null)),
            0,
            200,
            3L,
            1,
            List.of());

    String html = render(page, "");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Kartellbank<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("bank-requests-nofilter")
        .doesNotContain("data-bank-status-filter");
    assertThat(html.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"segment-status-pending\"")
        .contains("data-testid=\"segment-status-all\"")
        .containsPattern("name=\"status\" value=\"PENDING\" checked=\"checked\"")
        .contains("class=\"seg-count\"")
        .containsPattern("data-testid=\"bank-requests-waiting\"[^>]*>2 warten auf dich<")
        .contains("data-count-waiting=\"2\"");
    assertThat(html)
        .contains("class=\"data-table data-table--stack\"")
        .contains(">Ausstehend<")
        .doesNotContain(">PENDING<")
        .contains("Quartalsabgabe Q3")
        .contains("IRI Betriebskonto → KB-0001");
    assertThat(html.split("btn btn-success btn-xs", -1)).hasSize(3);
    assertThat(html)
        .containsPattern("class=\"chip chip--warning bank-req-route\"[^>]*>→ Bankleitung<")
        .containsPattern("data-testid=\"bank-request-waiting\"[^>]*>wartet auf Bankleitung<")
        .contains("id=\"bank-req-more-" + OPEN_ID + "\"")
        .contains("data-testid=\"bank-request-approval-granted\"");
  }

  /** An empty segment renders the empty state and no table. */
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void rendersTheEmptyState() throws Exception {
    String html =
        render(new PageResponse<>(List.<BankBookingRequestDto>of(), 0, 200, 0L, 0, List.of()), "");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("id=\"bank-requests-empty\"")
        .contains("Keine Anträge")
        .doesNotContain("data-testid=\"bank-requests-table\"");
  }

  /** The all segment is selected from the URL and queries every lifecycle state. */
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void selectsTheAllSegmentAndQueriesEveryState() throws Exception {
    String html =
        render(
            new PageResponse<>(List.<BankBookingRequestDto>of(), 0, 200, 0L, 0, List.of()),
            "status=ALL");

    assertThat(html).containsPattern("name=\"status\" value=\"ALL\" checked=\"checked\"");
    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/bank/requests?size=200&status=PENDING&status=CONFIRMED"
                    + "&status=REJECTED&status=CANCELLED"),
            anyTypeRef());
  }

  /** A status list from the former checkbox filter maps onto the all segment. */
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void mapsALegacyStatusListOntoTheAllSegment() throws Exception {
    String html =
        render(
            new PageResponse<>(List.<BankBookingRequestDto>of(), 0, 200, 0L, 0, List.of()),
            "status=PENDING,CONFIRMED");

    assertThat(html).containsPattern("name=\"status\" value=\"ALL\" checked=\"checked\"");
  }
}
