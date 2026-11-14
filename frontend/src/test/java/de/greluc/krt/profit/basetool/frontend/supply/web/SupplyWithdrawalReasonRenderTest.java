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

package de.greluc.krt.profit.basetool.frontend.supply.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.supply.model.DonationDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.MyDonationStatsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyRefDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A pledge a patch change's wipe withdrew names that patch change under its status on the
 * my-donations and incoming-donations lists, in the result fragments a live refresh fetches; a
 * pledge withdrawn by its donor shows the status alone (REQ-SUP-010, REQ-PATCH-013).
 */
@SpringBootTest
class SupplyWithdrawalReasonRenderTest {

  private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  private static final String REASON_TESTID = "data-testid=\"supply-donation-withdrawn-by-patch\"";

  @Autowired private WebApplicationContext context;

  @Autowired private SupplyFeatureState featureState;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void stubTheSurface() {
    featureState.evict();
    when(backendApiClient.get(eq("/api/v1/supply/state"), anyClass()))
        .thenReturn(new SupplyStateDto(true));
    when(backendApiClient.get(eq("/api/v1/supply/units"), anyTypeRef()))
        .thenReturn(
            List.of(
                new SupplyUnitDto(UNIT, "Argon", "ARG", "SPECIAL_COMMAND", true, true, true, 0L)));
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

  private static @NotNull DonationDto withdrawn(@Nullable String patchLabel) {
    return new DonationDto(
        UUID.randomUUID(),
        UNIT,
        "Argon",
        new SupplyRefDto(UUID.randomUUID(), "Quantanium"),
        null,
        500,
        "SCU",
        2.0,
        null,
        null,
        "WITHDRAWN",
        null,
        patchLabel,
        "Vexx",
        true,
        true,
        null,
        false,
        false,
        1L,
        Instant.now());
  }

  private static @NotNull PageResponse<DonationDto> page(@NotNull List<DonationDto> rows) {
    return new PageResponse<>(rows, 0, 10, rows.size(), 1, List.of());
  }

  /** The donor's list names patch change 4.4 under the wiped pledge's status, in German. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myDonationsNameThePatchChangeThatWithdrewAPledge() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/supply/me/donations?page="), anyTypeRef(), eq(0), eq(10)))
        .thenReturn(page(List.of(withdrawn("4.4"), withdrawn(null))));
    when(backendApiClient.get(eq("/api/v1/supply/me/donations/stats"), anyClass()))
        .thenReturn(new MyDonationStatsDto(0, List.of()));

    String html = render("/supply/donations/mine?fragment=results", Locale.GERMAN);

    assertThat(html).containsPattern(REASON_TESTID + "[^>]*>Patchwechsel 4\\.4</span>");
    assertThat(html.split(REASON_TESTID, -1)).hasSize(2);
  }

  /** The unit's incoming list names patch change 4.4 under the wiped pledge's status, in English. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void incomingDonationsNameThePatchChangeThatWithdrewAPledge() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/supply/donations?orgUnitId="),
            anyTypeRef(),
            eq(UNIT),
            eq(0),
            eq(10)))
        .thenReturn(page(List.of(withdrawn(null), withdrawn("4.4"))));

    String html = render("/supply/donations/incoming?fragment=results&lang=en", Locale.ENGLISH);

    assertThat(html).containsPattern(REASON_TESTID + "[^>]*>Patch change 4\\.4</span>");
    assertThat(html.split(REASON_TESTID, -1)).hasSize(2);
  }
}
