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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import de.greluc.krt.profit.basetool.frontend.support.CapabilityStubs;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The empty state of „Eingehende Spenden" names its cause (REQ-SUP-003, ADR-0329): a member who
 * manages an accepting unit only outside the selected unit keeps the tab and is pointed to the
 * switcher with the switcher's own label for all units, a member who manages none is told so.
 */
@SpringBootTest
class SupplyIncomingEmptyStateRenderTest {

  private static final String NOT_IN_CONTEXT =
      "In der gewählten Org-Einheit verwaltest du keine Einheit, die Spenden annimmt.";

  private static final String MEMBER_SWITCH_HINT =
      "eine andere Einheit oder „Alle meine Org-Einheiten“.";

  private static final String ADMIN_SWITCH_HINT = "eine andere Einheit oder „Alle Org-Einheiten“.";

  private static final String MANAGES_NONE = "Du verwaltest keine Einheit, die Spenden annimmt.";

  @Autowired private WebApplicationContext context;

  @Autowired private SupplyFeatureState featureState;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /** The supply surface is on and the one accepting unit is not managed in the active context. */
  @BeforeEach
  void stubTheSurface() {
    featureState.evict();
    Mockito.when(backendApiClient.get(eq("/api/v1/supply/state"), anyClass()))
        .thenReturn(new SupplyStateDto(true));
    Mockito.when(backendApiClient.get(eq("/api/v1/supply/units"), anyTypeRef()))
        .thenReturn(
            List.of(
                new SupplyUnitDto(
                    UUID.randomUUID(), "Hel", "HEL", "SQUADRON", true, true, false, 0L)));
  }

  private @NotNull String render() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/supply/donations/incoming").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aManagerOutsideTheSelectedUnitIsPointedToTheSwitcher() throws Exception {
    CapabilityStubs.report(backendApiClient, LayoutResponses.tabs(true, true, false, false, false));

    assertThat(render())
        .contains(NOT_IN_CONTEXT, MEMBER_SWITCH_HINT)
        .doesNotContain(MANAGES_NONE, ADMIN_SWITCH_HINT);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void anAdminOutsideTheSelectedUnitIsPointedToTheAdminSwitcherLabel() throws Exception {
    CapabilityStubs.report(backendApiClient, LayoutResponses.tabs(true, true, false, false, false));

    assertThat(render())
        .contains(NOT_IN_CONTEXT, ADMIN_SWITCH_HINT)
        .doesNotContain(MANAGES_NONE, MEMBER_SWITCH_HINT);
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberWhoManagesNoAcceptingUnitIsToldSo() throws Exception {
    CapabilityStubs.report(
        backendApiClient, LayoutResponses.tabs(false, false, false, false, false));

    assertThat(render()).contains(MANAGES_NONE).doesNotContain(NOT_IN_CONTEXT);
  }
}
