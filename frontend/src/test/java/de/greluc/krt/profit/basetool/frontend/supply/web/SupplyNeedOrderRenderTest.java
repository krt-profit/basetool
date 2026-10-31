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
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedJobOrderOptionDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedOrderDto;
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
 * Renders the job-order picker of the need form and the linked order's coverage on a need row
 * (REQ-SUP-003, REQ-SUP-023).
 */
@SpringBootTest
class SupplyNeedOrderRenderTest {

  private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

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

  private @NotNull String render(@NotNull String url) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get(url).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static @NotNull NeedDto linkedNeed(@Nullable NeedOrderDto order) {
    return new NeedDto(
        UUID.randomUUID(),
        UNIT,
        "Argon",
        "ARG",
        new SupplyRefDto(UUID.randomUUID(), "Stileron"),
        null,
        "SCU",
        null,
        15.0,
        0.0,
        15.0,
        "NORMAL",
        "OPEN",
        false,
        null,
        null,
        null,
        null,
        ORDER,
        order,
        true,
        0L,
        Instant.now());
  }

  private void needsAre(@NotNull NeedDto need) {
    when(backendApiClient.get(
            startsWith("/api/v1/supply/needs?page="), anyTypeRef(), eq(0), eq(10)))
        .thenReturn(new PageResponse<>(List.of(need), 0, 10, 1, 1, List.of()));
  }

  /** A linked order whose stock covers the need's bucket shows the figures and the chip. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aCoveredLinkedOrderShowsItsStockAndTheCoveredChip() throws Exception {
    needsAre(linkedNeed(new NeedOrderDto(ORDER, 42, "IN_PROGRESS", 12.4, 12.4)));

    String html = render("/supply/needs?fragment=results");

    assertThat(html)
        .contains("Auftrag #42: 12,4 von 12,4 SCU im Bestand")
        .contains("data-testid=\"supply-need-order-covered\"")
        .contains("data-job-order-id=\"" + ORDER + "\"")
        .contains("data-job-order-label=\"#42\"");
  }

  /** A linked order that does not require the need's bucket names the order only. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aLinkedOrderWithoutTheBucketNamesTheOrderOnly() throws Exception {
    needsAre(linkedNeed(new NeedOrderDto(ORDER, 42, "OPEN", null, null)));

    String html = render("/supply/needs?fragment=results");

    assertThat(html)
        .contains("Auftrag #42")
        .doesNotContain("im Bestand")
        .doesNotContain("data-testid=\"supply-need-order-covered\"");
  }

  /** A row without an order view carries no hint. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aNeedWithoutTheOrderViewShowsNoHint() throws Exception {
    needsAre(linkedNeed(null));

    assertThat(render("/supply/needs?fragment=results"))
        .doesNotContain("data-testid=\"supply-need-order\"");
  }

  /** The need form offers the managed unit's orders, tagged with their unit. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theNeedFormOffersTheOrdersOfTheManagedUnits() throws Exception {
    needsAre(linkedNeed(null));
    when(backendApiClient.get(eq("/api/v1/supply/needs/job-orders"), anyTypeRef()))
        .thenReturn(List.of(new NeedJobOrderOptionDto(ORDER, UNIT, 42)));

    String html = render("/supply/needs");

    assertThat(html)
        .containsPattern(
            "<option value=\"" + ORDER + "\" data-unit-id=\"" + UNIT + "\">#42</option>")
        .contains("Kein Auftrag");
  }
}
