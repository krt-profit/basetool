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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the in-place mission core-edit twin {@link
 * MissionWriteController#updateMissionAjax}.
 *
 * <ul>
 *   <li>Success returns the four fresh versions, including the schedule version after the
 *       PLANNED→ACTIVE auto-bump.
 *   <li>An unedited schedule time round-trips unchanged.
 *   <li>A validation failure returns a {@code 422} field map without a backend call.
 *   <li>A backend {@code 409} is relayed as {@code problem+json} with its {@code code}.
 *   <li>A POST without the AJAX header falls back to the redirect handler.
 * </ul>
 */
@SpringBootTest
class MissionCoreEditAjaxControllerTest {

  private static final UUID MISSION_ID = UUID.randomUUID();

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void updateMissionAjax_validForm_runsThreePatchesAndReturnsFourFreshVersions() throws Exception {
    MissionDto refreshed = mock(MissionDto.class);
    when(refreshed.version()).thenReturn(11L);
    when(refreshed.coreVersion()).thenReturn(22L);
    when(refreshed.scheduleVersion()).thenReturn(33L);
    when(refreshed.flagsVersion()).thenReturn(44L);
    when(backendApiClient.get(eq("/api/v1/missions/" + MISSION_ID), eq(MissionDto.class)))
        .thenReturn(refreshed);

    mockMvc
        .perform(
            post("/missions/" + MISSION_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf())
                .param("name", "Edited Mission")
                .param("status", "PLANNED")
                .param("coreVersion", "2")
                .param("scheduleVersion", "5")
                .param("flagsVersion", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(11))
        .andExpect(jsonPath("$.coreVersion").value(22))
        .andExpect(jsonPath("$.scheduleVersion").value(33))
        .andExpect(jsonPath("$.flagsVersion").value(44));

    verify(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/schedule"), any(), eq(Void.class));
    verify(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/core"), any(), eq(Void.class));
    verify(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/flags"), any(), eq(Void.class));
  }

  @Test
  void updateMissionAjax_microsecondZonelessPlannedStart_isPreservedNotNulled() throws Exception {
    MissionDto refreshed = mock(MissionDto.class);
    when(refreshed.version()).thenReturn(1L);
    when(refreshed.coreVersion()).thenReturn(1L);
    when(refreshed.scheduleVersion()).thenReturn(1L);
    when(refreshed.flagsVersion()).thenReturn(1L);
    when(backendApiClient.get(eq("/api/v1/missions/" + MISSION_ID), eq(MissionDto.class)))
        .thenReturn(refreshed);

    mockMvc
        .perform(
            post("/missions/" + MISSION_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf())
                .param("name", "Edited Mission")
                .param("status", "PLANNED")
                .param("plannedStartTime", "2026-06-21T11:59:58.222717")
                .param("scheduleVersion", "5"))
        .andExpect(status().isOk());
    ArgumentCaptor<Map<String, Object>> scheduleBody = ArgumentCaptor.captor();
    verify(backendApiClient)
        .patch(
            eq("/api/v1/missions/" + MISSION_ID + "/schedule"),
            scheduleBody.capture(),
            eq(Void.class));
    assertEquals(
        Instant.parse("2026-06-21T09:59:58.222717Z"),
        scheduleBody.getValue().get("plannedStartTime"),
        "an unedited microsecond zoneless schedule time must round-trip, not be nulled on save");
  }

  @Test
  void updateMissionAjax_onlyCoreDirty_skipsScheduleAndFlagsPatches() throws Exception {
    MissionDto refreshed = mock(MissionDto.class);
    when(refreshed.version()).thenReturn(1L);
    when(refreshed.coreVersion()).thenReturn(2L);
    when(refreshed.scheduleVersion()).thenReturn(3L);
    when(refreshed.flagsVersion()).thenReturn(4L);
    when(backendApiClient.get(eq("/api/v1/missions/" + MISSION_ID), eq(MissionDto.class)))
        .thenReturn(refreshed);

    mockMvc
        .perform(
            post("/missions/" + MISSION_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf())
                .param("name", "Renamed Only")
                .param("status", "PLANNED")
                .param("coreVersion", "2")
                .param("scheduleVersion", "5")
                .param("flagsVersion", "1")
                .param("dirtyCore", "true")
                .param("dirtySchedule", "false")
                .param("dirtyFlags", "false"))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/core"), any(), eq(Void.class));
    verify(backendApiClient, never())
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/schedule"), any(), eq(Void.class));
    verify(backendApiClient, never())
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/flags"), any(), eq(Void.class));
  }

  @Test
  void updateMissionAjax_onlyScheduleDirty_skipsCoreAndFlagsPatches() throws Exception {
    MissionDto refreshed = mock(MissionDto.class);
    when(refreshed.version()).thenReturn(1L);
    when(refreshed.coreVersion()).thenReturn(1L);
    when(refreshed.scheduleVersion()).thenReturn(2L);
    when(refreshed.flagsVersion()).thenReturn(1L);
    when(backendApiClient.get(eq("/api/v1/missions/" + MISSION_ID), eq(MissionDto.class)))
        .thenReturn(refreshed);

    mockMvc
        .perform(
            post("/missions/" + MISSION_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf())
                .param("name", "Unchanged Name")
                .param("status", "PLANNED")
                .param("plannedStartTime", "2026-06-21T11:59:58.222717")
                .param("scheduleVersion", "5")
                .param("dirtyCore", "false")
                .param("dirtySchedule", "true")
                .param("dirtyFlags", "false"))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/schedule"), any(), eq(Void.class));
    verify(backendApiClient, never())
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/core"), any(), eq(Void.class));
    verify(backendApiClient, never())
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/flags"), any(), eq(Void.class));
  }

  @Test
  void updateMissionAjax_dirtyFlagsAbsent_stillPatchesEverySection() throws Exception {
    MissionDto refreshed = mock(MissionDto.class);
    when(refreshed.version()).thenReturn(1L);
    when(refreshed.coreVersion()).thenReturn(1L);
    when(refreshed.scheduleVersion()).thenReturn(1L);
    when(refreshed.flagsVersion()).thenReturn(1L);
    when(backendApiClient.get(eq("/api/v1/missions/" + MISSION_ID), eq(MissionDto.class)))
        .thenReturn(refreshed);

    mockMvc
        .perform(
            post("/missions/" + MISSION_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf())
                .param("name", "Edited Mission")
                .param("status", "PLANNED")
                .param("scheduleVersion", "5"))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/schedule"), any(), eq(Void.class));
    verify(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/core"), any(), eq(Void.class));
    verify(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/flags"), any(), eq(Void.class));
  }

  @Test
  void updateMissionAjax_blankNameAndStatus_returns422FieldMapWithoutBackendCall()
      throws Exception {
    mockMvc
        .perform(
            post("/missions/" + MISSION_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf())
                .param("name", "")
                .param("status", ""))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.name").exists())
        .andExpect(jsonPath("$.name", not(containsString("{"))))
        .andExpect(jsonPath("$.status").exists())
        .andExpect(jsonPath("$.status", not(containsString("{"))));

    verify(backendApiClient, never()).patch(anyString(), any(), eq(Void.class));
  }

  @Test
  void updateMissionAjax_backendConflict_propagatesProblemJsonWithCode() throws Exception {
    doThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null))
        .when(backendApiClient)
        .patch(eq("/api/v1/missions/" + MISSION_ID + "/schedule"), any(), eq(Void.class));

    mockMvc
        .perform(
            post("/missions/" + MISSION_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin())
                .with(csrf())
                .param("name", "Edited Mission")
                .param("status", "PLANNED")
                .param("scheduleVersion", "5"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  void updateMission_withoutHeader_fallsBackToClassicRedirect() throws Exception {
    mockMvc
        .perform(
            post("/missions/" + MISSION_ID)
                .with(oidcLogin())
                .with(csrf())
                .param("name", "Edited Mission")
                .param("status", "PLANNED"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/missions/" + MISSION_ID));
  }
}
