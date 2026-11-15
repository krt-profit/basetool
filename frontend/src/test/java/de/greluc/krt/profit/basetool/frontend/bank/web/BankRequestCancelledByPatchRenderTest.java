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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestViewDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A booking request a patch change's currency wipe cancelled names that patch change beneath its
 * status on every screen that shows a request's status, in the fragments a live refresh fetches; a
 * request its requester withdrew shows the status alone (REQ-PATCH-042, REQ-BANK-022, REQ-BANK-023,
 * REQ-BANK-068).
 */
@SpringBootTest
class BankRequestCancelledByPatchRenderTest {

  private static final String OWN_REQUESTS_URI = "/api/v1/bank/org-units/requests";

  private static final String FOREIGN_REQUESTS_URI = "/api/v1/bank/org-units/requests/foreign";

  private static final String REQUEST_URI = "/api/v1/bank/org-units/requests/{id}";

  private static final String ROW_REASON = "data-testid=\"bank-request-cancelled-by-patch\"";

  private static final String PAGE_REASON = "data-testid=\"bank-request-page-cancelled-by-patch\"";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void answerEveryOtherReadWithNothing() {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
  }

  private @NotNull String render(@NotNull String url, @NotNull Locale locale) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get(url).locale(locale))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A cancelled withdrawal on the Staffel Nord account.
   *
   * @param patchLabel the label of the patch change whose currency wipe cancelled it, or {@code
   *     null} for one its requester withdrew
   * @return the request
   */
  private static @NotNull BankBookingRequestDto cancelled(@Nullable String patchLabel) {
    return new BankBookingRequestDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "KB-0007",
        "Staffel Nord",
        UUID.randomUUID(),
        "Staffel Nord",
        "NORD",
        "WITHDRAWAL",
        new BigDecimal("2500"),
        null,
        null,
        null,
        "CANCELLED",
        "nova",
        null,
        null,
        null,
        null,
        null,
        Instant.parse("2026-11-07T09:30:00Z"),
        Instant.parse("2026-11-06T09:30:00Z"),
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
        null,
        null,
        null,
        2L,
        null,
        null,
        patchLabel);
  }

  /** The member's "Meine Anträge" names patch change 4.4 under the wiped request's status. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myRequestsNameThePatchChangeThatCancelledARequest() throws Exception {
    when(backendApiClient.get(eq(OWN_REQUESTS_URI), anyTypeRef()))
        .thenReturn(List.of(cancelled("4.4"), cancelled(null)));

    String html = render("/org-unit-bank?fragment=orgUnitBank", Locale.GERMAN);

    assertThat(html).containsPattern(ROW_REASON + "[^>]*>Patchwechsel 4\\.4</span>");
    assertThat(html.split(ROW_REASON, -1)).hasSize(2);
  }

  /** The approver's "Fremde Anträge" names patch change 4.4 under the wiped request, in English. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void foreignRequestsNameThePatchChangeThatCancelledARequest() throws Exception {
    when(backendApiClient.get(eq(FOREIGN_REQUESTS_URI), anyTypeRef()))
        .thenReturn(List.of(cancelled(null), cancelled("4.4")));

    String html = render("/org-unit-bank?fragment=orgUnitBank&lang=en", Locale.ENGLISH);

    assertThat(html).containsPattern(ROW_REASON + "[^>]*>Patch change 4\\.4</span>");
    assertThat(html.split(ROW_REASON, -1)).hasSize(2);
  }

  /** The bank staff's request queue names patch change 4.4 under the wiped request's status. */
  @Test
  @WithMockUser(roles = "BANK_EMPLOYEE")
  void theStaffQueueNamesThePatchChangeThatCancelledARequest() throws Exception {
    when(backendApiClient.get(startsWith("/api/v1/bank/requests"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(cancelled("4.4"), cancelled(null)), 0, 200, 2L, 1, List.of()));

    String html = render("/bank/requests?status=ALL&fragment=requestQueue", Locale.GERMAN);

    assertThat(html).containsPattern(ROW_REASON + "[^>]*>Patchwechsel 4\\.4</span>");
    assertThat(html.split(ROW_REASON, -1)).hasSize(2);
  }

  /** The request's own page gives patch change 4.4 as the reason of the cancellation. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theRequestPageGivesThePatchChangeAsTheReason() throws Exception {
    when(backendApiClient.execute(eq(HttpMethod.GET), eq(REQUEST_URI), any(), any()))
        .thenReturn(new BankBookingRequestViewDto(cancelled("4.4"), true, false, false));

    String html = render("/bank/requests/" + UUID.randomUUID(), Locale.GERMAN);

    assertThat(html).containsPattern(PAGE_REASON + "[^>]*>Patchwechsel 4\\.4</span>");
  }

  /** A request its requester withdrew shows no patch change on its own page. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theRequestPageOfAWithdrawnRequestNamesNoPatchChange() throws Exception {
    when(backendApiClient.execute(eq(HttpMethod.GET), eq(REQUEST_URI), any(), any()))
        .thenReturn(new BankBookingRequestViewDto(cancelled(null), true, false, false));

    String html =
        render("/bank/requests/" + UUID.randomUUID() + "?fragment=requestDetail", Locale.GERMAN);

    assertThat(html).contains("data-testid=\"bank-request-page\"").doesNotContain(PAGE_REASON);
  }
}
