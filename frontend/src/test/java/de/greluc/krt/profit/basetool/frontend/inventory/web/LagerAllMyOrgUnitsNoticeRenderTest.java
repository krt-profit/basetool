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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.ContextKind;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the Lager scope notice describes the effective context of the stock (REQ-ORG-024).
 */
@SpringBootTest
class LagerAllMyOrgUnitsNoticeRenderTest {

  private static final String NOTICE = "data-testid=\"all-my-org-units-notice\"";

  private static final String MEMBER_STOCK_SCOPE =
      "Bestände der Org-Einheiten, auf deren Lager du Zugriff hast";
  private static final String ADMIN_STOCK_SCOPE = "aller Org-Einheiten zusammen";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders a Lager page in German under the given effective context, answering every list read
   * with an empty list.
   *
   * @param path the page path including its query
   * @param kind what the backend reports the effective context to cover
   * @param activeOrgUnitId the one unit of an {@link ContextKind#ORG_UNIT} context, else {@code
   *     null}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(
      @NotNull String path, @NotNull ContextKind kind, @Nullable UUID activeOrgUnitId)
      throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.context(kind, activeOrgUnitId, List.of()));
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get(path).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/inventory", "/inventory?storage=all"})
  @WithMockUser(roles = "KRT_MEMBER")
  void aPooledLagerPageCarriesTheNoticeUnderAllMyOrgUnits(String path) throws Exception {
    assertThat(render(path, ContextKind.ALL_MY_ORG_UNITS, null)).contains(NOTICE);
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theOverviewDescribesTheMembersAccessibleOrgUnits() throws Exception {
    assertThat(render("/inventory", ContextKind.ALL_MY_ORG_UNITS, null))
        .contains(MEMBER_STOCK_SCOPE)
        .doesNotContain(ADMIN_STOCK_SCOPE);
  }

  @Test
  @WithMockUser(roles = {"ADMIN", "KRT_MEMBER"})
  void theOverviewNamesAllOrgUnitsForAnUnpinnedAdmin() throws Exception {
    assertThat(render("/inventory", ContextKind.ALL_ORG_UNITS, null))
        .contains(ADMIN_STOCK_SCOPE)
        .doesNotContain(NOTICE, MEMBER_STOCK_SCOPE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/inventory", "/inventory?storage=all"})
  @WithMockUser(roles = "KRT_MEMBER")
  void aPooledLagerPageDropsTheNoticeOnceAUnitIsSelected(String path) throws Exception {
    assertThat(render(path, ContextKind.ORG_UNIT, UUID.randomUUID()))
        .doesNotContain(NOTICE, MEMBER_STOCK_SCOPE, ADMIN_STOCK_SCOPE);
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myLagerCarriesNoNoticeBecauseItDoesNotFollowTheSelection() throws Exception {
    assertThat(render("/inventory?storage=my", ContextKind.ALL_MY_ORG_UNITS, null))
        .doesNotContain(NOTICE);
  }
}
