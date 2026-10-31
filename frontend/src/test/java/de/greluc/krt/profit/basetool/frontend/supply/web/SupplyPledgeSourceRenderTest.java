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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.DonationSourceDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * Renders the stock picker of the pledge dialog and relays its read through the proxy
 * (REQ-SUP-025).
 */
@SpringBootTest
class SupplyPledgeSourceRenderTest {

  private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ROW = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
  private static final UUID MATERIAL = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

  @Autowired private WebApplicationContext context;

  @Autowired private SupplyFeatureState featureState;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void stubTheSurface() {
    featureState.evict();
    when(backendApiClient.get(eq("/api/v1/supply/state"), anyClass()))
        .thenReturn(new SupplyStateDto(true));
    when(backendApiClient.get(eq("/api/v1/supply/units"), anyTypeRef()))
        .thenReturn(
            List.of(
                new SupplyUnitDto(UNIT, "Argon", "ARG", "SPECIAL_COMMAND", true, true, true, 0L)));
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** The pledge dialog carries the stock picker with its empty choice and its hint. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void thePledgeDialogOffersTheStockPicker() throws Exception {
    String html =
        mockMvc
            .perform(get("/supply/needs").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("id=\"supply-pledge-source\"")
        .contains("Nicht aus dem Lager")
        .contains("erst umgebucht, wenn die Einheit die Spende annimmt")
        .doesNotContain("supply-pledge-source-id");
  }

  /** The proxy relays the caller's rows for the chosen material. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theProxyRelaysTheCallersRows() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/supply/me/donation-sources?materialId={materialId}"),
            anyTypeRef(),
            eq(MATERIAL)))
        .thenReturn(List.of(new DonationSourceDto(ROW, "Lorville", 700, 10.0, "SCU")));

    String json =
        mockMvc
            .perform(
                get("/api/proxy/supply/me/donation-sources")
                    .param("materialId", MATERIAL.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(json).contains(ROW.toString()).contains("Lorville").contains("\"freeAmount\":10.0");
  }

  /** The proxy relays the open needs a Lager row can be pledged to (REQ-SUP-026). */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theProxyRelaysTheNeedsARowFits() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/supply/me/donation-targets?rowId={rowId}"), anyTypeRef(), eq(ROW)))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/api/proxy/supply/me/donation-targets").param("rowId", ROW.toString()))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }
}
