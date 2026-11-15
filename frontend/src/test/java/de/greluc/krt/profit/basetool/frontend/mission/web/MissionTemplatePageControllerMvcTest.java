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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionCrewDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionObjectiveDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionStepDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateOwnerDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateSummaryDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionUnitDto;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;

/** Checks the localized management page and its isolated list fragment. */
@SpringBootTest
class MissionTemplatePageControllerMvcTest {

  private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
  private static final String BASE = "/api/v1/mission-templates";
  private static final String LIST = BASE + "?owningOrgUnitId={ownerId}&page={page}";

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
    when(backendApiClient.get(eq(LIST), anyTypeRef(), eq(OWNER), eq(0)))
        .thenReturn(
            new PageResponse<>(
                List.of(new MissionTemplateSummaryDto(ID, OWNER, "Patrol", 4L)),
                0,
                1,
                2,
                2,
                List.of()));
    when(backendApiClient.get(eq(LIST), anyTypeRef(), eq(OWNER), eq(1)))
        .thenReturn(
            new PageResponse<>(
                List.of(new MissionTemplateSummaryDto(ID, OWNER, "Recovery", 1L)),
                1,
                1,
                2,
                2,
                List.of()));
    when(backendApiClient.get(eq(LIST), anyTypeRef(), eq(OWNER), eq(2)))
        .thenReturn(new PageResponse<>(List.of(), 2, 1, 2, 2, List.of()));

    var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String page =
        mvc.perform(get("/mission-templates").param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(page)
        .contains("Mission templates", "Unit A", "Patrol", "Main goal")
        .contains("/mission-templates/list?ownerId=" + OWNER + "&amp;page=1")
        .contains("id=\"mission-template-editor\"", "id=\"mission-template-list\"");

    String fragment =
        mvc.perform(
                get("/mission-templates/list")
                    .param("ownerId", OWNER.toString())
                    .param("page", "1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(fragment)
        .contains("Recovery", "data-template-page=\"1\"")
        .doesNotContain("Patrol", "id=\"mission-template-editor\"", "id=\"mission-template-list\"");

    String afterDeletion =
        mvc.perform(
                get("/mission-templates/list")
                    .param("ownerId", OWNER.toString())
                    .param("page", "2"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(afterDeletion).contains("Recovery", "data-template-page=\"1\"");
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "MISSION_MANAGER"})
  void offersAnIndependentDraftFromTheReadableMission() throws Exception {
    UUID sourceOwner = UUID.randomUUID();
    UUID shipTypeId = UUID.randomUUID();
    MissionDto mission = mock(MissionDto.class);
    ShipTypeDto shipType = mock(ShipTypeDto.class);
    when(shipType.id()).thenReturn(shipTypeId);
    UUID frequencyTypeId = UUID.randomUUID();
    when(mission.name()).thenReturn("Recovery \"<plan>\"");
    when(mission.description()).thenReturn("Bring **fuel**");
    when(mission.meetingPoint()).thenReturn("  ");
    when(mission.isInternal()).thenReturn(true);
    when(mission.meetingTime()).thenReturn(Instant.parse("2026-11-20T18:30:00Z"));
    when(mission.plannedStartTime()).thenReturn(Instant.parse("2026-11-20T19:00:00Z"));
    when(mission.frequencies())
        .thenReturn(
            List.of(
                new MissionFrequencyDto(
                    UUID.randomUUID(),
                    new MissionFrequencyDto.FrequencyTypeRef(frequencyTypeId, "Command"),
                    null,
                    new BigDecimal("123.40"),
                    2L),
                new MissionFrequencyDto(
                    UUID.randomUUID(), null, "Ground team", new BigDecimal("7.00"), 1L)));
    when(mission.owningSquadron())
        .thenReturn(new SquadronReferenceDto(sourceOwner, "Source", "SOURCE"));
    when(mission.objectives())
        .thenReturn(
            List.of(
                new MissionObjectiveDto(
                    UUID.randomUUID(), "Survey wreck", MissionObjectiveKind.SECONDARY, 0),
                new MissionObjectiveDto(
                    UUID.randomUUID(), "Recover cargo", MissionObjectiveKind.PRIMARY, 1)));
    when(mission.steps())
        .thenReturn(
            List.of(
                new MissionStepDto(UUID.randomUUID(), "Launch", "At 18:00", true, 0),
                new MissionStepDto(UUID.randomUUID(), "Extract", null, false, 1)));
    when(mission.assignedUnits())
        .thenReturn(
            List.of(
                new MissionUnitDto(
                    UUID.randomUUID(),
                    "Scout",
                    shipType,
                    mock(ShipDto.class),
                    127.46,
                    true,
                    mock(UserReferenceDto.class),
                    "Check landing zone",
                    7L,
                    List.of(mock(MissionCrewDto.class)))));
    when(backendApiClient.get(eq("/api/v1/missions/{id}"), anyTypeRef(), eq(ID)))
        .thenReturn(mission);
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(
            new MeLayoutResponse(null, null, List.of(), LayoutResponses.missionManager(), 0L));
    when(backendApiClient.get(eq(BASE + "/owning-org-units"), anyTypeRef()))
        .thenReturn(List.of(new MissionTemplateOwnerDto(OWNER, "Destination")));
    when(backendApiClient.get(eq(LIST), anyTypeRef(), eq(OWNER), eq(0)))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    String page =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build()
            .perform(get("/mission-templates").param("sourceMissionId", ID.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var draft = Pattern.compile("data-template-draft=\"([^\"]+)\"").matcher(page);
    assertThat(draft.find()).isTrue();
    var mapper = JsonMapper.builder().build();
    assertThat(mapper.readTree(HtmlUtils.htmlUnescape(draft.group(1))))
        .isEqualTo(
            mapper.readTree(
                """
                {
                  "template":{
                    "owningOrgUnitId":"%s",
                    "name":"Recovery \\"<plan>\\"",
                    "description":"Bring **fuel**",
                    "meetingPoint":null,
                    "isInternal":true,
                    "meetingTime":null,
                    "plannedStartTime":null,
                    "plannedEndTime":null,
                    "frequencies":[{"frequencyTypeId":"%s","name":null,"value":123.40},{"frequencyTypeId":null,"name":"Ground team","value":7.00}],
                    "objectives":[{"title":"Survey wreck","kind":"SECONDARY"},{"title":"Recover cargo","kind":"PRIMARY"}],
                    "steps":[{"title":"Launch","meta":"At 18:00"},{"title":"Extract","meta":null}],
                    "units":[{"name":"Scout","shipTypeId":"%s","note":"Check landing zone","highValueUnit":true,"frequency":127.46}]
                  },
                  "meetingTime":"2026-11-20T18:30:00Z",
                  "plannedStartTime":"2026-11-20T19:00:00Z",
                  "plannedEndTime":null
                }
                """
                    .formatted(OWNER, frequencyTypeId, shipTypeId)));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "MISSION_MANAGER"})
  void refusesADirectDraftRequestWhenTheMissionReadIsForbidden() throws Exception {
    when(backendApiClient.get(eq(BASE + "/owning-org-units"), anyTypeRef()))
        .thenReturn(List.of(new MissionTemplateOwnerDto(OWNER, "Destination")));
    when(backendApiClient.get(eq("/api/v1/missions/{id}"), anyTypeRef(), eq(ID)))
        .thenThrow(new BackendServiceException("Forbidden", null, 403));

    MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build()
        .perform(get("/mission-templates").param("sourceMissionId", ID.toString()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void refusesADirectDraftRequestWithoutAnyManageableDestination() throws Exception {
    when(backendApiClient.get(eq(BASE + "/owning-org-units"), anyTypeRef())).thenReturn(List.of());

    MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build()
        .perform(get("/mission-templates").param("sourceMissionId", ID.toString()))
        .andExpect(status().isForbidden());
  }
}
