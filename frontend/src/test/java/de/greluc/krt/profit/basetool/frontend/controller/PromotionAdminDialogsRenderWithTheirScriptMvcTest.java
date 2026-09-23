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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The promotion admin pages render their dialogs exactly when they render the script that drives
 * them (REQ-UI-013).
 *
 * <p>In the admin's all-squadrons view the two pages show a pick-a-squadron prompt and load neither
 * their openers nor their page script. Until 2026-09-23 they still rendered every dialog: dead
 * markup whose close handlers did not exist. With a squadron pinned, dialogs and script come
 * together.
 */
@SpringBootTest
class PromotionAdminDialogsRenderWithTheirScriptMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private final UUID squadronId = UUID.randomUUID();

  /** Stubs the layout context: one promotion-enabled squadron the admin may pin. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    SquadronDto squadron =
        new SquadronDto(squadronId, "IRIDIUM", "IRI", null, true, true, false, 0L);
    PageResponse<SquadronDto> page =
        new PageResponse<>(List.of(squadron), 0, 1000, 1, 1, List.of());
    when(backendApiClient.get(contains("/api/v1/squadrons"), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(eq(CachedCatalog.SQUADRONS), anyTypeRef())).thenReturn(page);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.activeOrgUnit(squadronId));
  }

  /**
   * An admin without a pinned squadron gets neither the dialogs nor the script.
   *
   * @param path the page
   * @param script the page's own script
   * @param dialog the id of one of the page's dialogs
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @CsvSource({
    "/promotion/admin/topics, /js/promotion-admin-topics.js, modal-create-topic",
    "/promotion/admin/rank-requirements, /js/promotion-admin-rank-requirements.js, modal-create"
  })
  @WithMockUser(roles = "ADMIN")
  void allSquadronsViewRendersNoDialogAndNoScript(String path, String script, String dialog)
      throws Exception {
    String html =
        mockMvc
            .perform(get(path))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html).doesNotContain("src=\"" + script).doesNotContain("id=\"" + dialog + "\"");
  }

  /**
   * With a squadron pinned, the dialogs and the script that drives them render together.
   *
   * @param path the page
   * @param script the page's own script
   * @param dialog the id of one of the page's dialogs
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @CsvSource({
    "/promotion/admin/topics, /js/promotion-admin-topics.js, modal-create-topic",
    "/promotion/admin/rank-requirements, /js/promotion-admin-rank-requirements.js, modal-create"
  })
  @WithMockUser(roles = "ADMIN")
  void pinnedSquadronRendersDialogsTogetherWithTheScript(String path, String script, String dialog)
      throws Exception {
    String html =
        mockMvc
            .perform(get(path).sessionAttr("iridium.activeOrgUnitId", squadronId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html).contains("src=\"" + script).contains("id=\"" + dialog + "\"");
  }
}
