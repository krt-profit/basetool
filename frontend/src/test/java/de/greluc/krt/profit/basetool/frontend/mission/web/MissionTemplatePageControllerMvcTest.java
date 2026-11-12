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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateOwnerDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateSummaryDto;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Checks the localized management page and its isolated list fragment. */
@SpringBootTest
class MissionTemplatePageControllerMvcTest {

  private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
  private static final String BASE = "/api/v1/mission-templates";

  @Autowired private WebApplicationContext context;
  @MockitoBean private BackendApiClient backendApiClient;
  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /** The page displays the editor, and a list refresh renders only the list's children. */
  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "MISSION_MANAGER"})
  void rendersEligibleOwnerAndIndependentListFragment() throws Exception {
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(
            new MeLayoutResponse(null, null, List.of(), LayoutResponses.missionManager(), 0L));
    when(backendApiClient.get(eq(BASE + "/owning-org-units"), anyTypeRef()))
        .thenReturn(List.of(new MissionTemplateOwnerDto(OWNER, "Unit A")));
    when(backendApiClient.get(eq(BASE + "?owningOrgUnitId={ownerId}"), anyTypeRef(), eq(OWNER)))
        .thenReturn(List.of(new MissionTemplateSummaryDto(ID, OWNER, "Patrol", 4L)));

    var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String page =
        mvc.perform(get("/mission-templates").param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(page)
        .contains("Mission templates", "Unit A", "Patrol", "Main goal")
        .contains("id=\"mission-template-editor\"", "id=\"mission-template-list\"");

    String fragment =
        mvc.perform(get("/mission-templates/list").param("ownerId", OWNER.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(fragment)
        .contains("Patrol")
        .doesNotContain("id=\"mission-template-editor\"", "id=\"mission-template-list\"");
  }
}
