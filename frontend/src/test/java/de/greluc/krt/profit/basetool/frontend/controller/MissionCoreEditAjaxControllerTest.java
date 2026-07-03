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
 * MVC tests for the #589 in-place mission core-edit twin {@link
 * MissionPageController#updateMissionAjax}: the success path returns the four fresh versions (incl.
 * the schedule version re-read after the PLANNED→ACTIVE auto-bump), an unedited microsecond
 * zoneless schedule time round-trips through the schedule PATCH instead of being nulled, a
 * {@code @Valid} failure returns a {@code 422} {@code {field: message}} map (messages resolved
 * exactly as {@code th:errors}) with no backend call, a backend {@code 409} is propagated as {@code
 * problem+json} preserving the {@code code}, and a header-less POST falls back to the classic
 * redirect handler.
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
    // The re-read after the three patches is the authoritative source of the versions — the
    // PLANNED→ACTIVE auto-transition during the core patch bumps the schedule version a second
    // time,
    // so the value the client gets back is 33 (the fresh read), not the 5 it submitted.
    MissionDto refreshed = mock(MissionDto.class);
    when(refreshed.version()).thenReturn(11L);
    when(refreshed.coreVersion()).thenReturn(22L);
    when(refreshed.scheduleVersion()).thenReturn(33L);
    when(refreshed.flagsVersion()).thenReturn(44L);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + MISSION_ID), eq(MissionDto.class), eq(false)))
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
    // Regression for the datetime round-trip data loss: formatInstant renders a schedule time that
    // was displayed but never re-edited as a zoneless Europe/Berlin local datetime that can carry
    // microseconds (e.g. 2026-06-21T11:59:58.222717). parseToInstant must round-trip it to the
    // correct instant instead of throwing and nulling the field — otherwise every core-edit save
    // silently clears plannedStartTime/meetingTime/plannedEndTime the user did not re-touch, which
    // is exactly what broke MissionCoreEditInPlaceE2eTest (the next page load lost the required
    // plannedStart, so the form could no longer submit).
    MissionDto refreshed = mock(MissionDto.class);
    when(refreshed.version()).thenReturn(1L);
    when(refreshed.coreVersion()).thenReturn(1L);
    when(refreshed.scheduleVersion()).thenReturn(1L);
    when(refreshed.flagsVersion()).thenReturn(1L);
    when(backendApiClient.get(
            eq("/api/v1/missions/" + MISSION_ID), eq(MissionDto.class), eq(false)))
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
    // 2026-06-21T11:59:58.222717 in Europe/Berlin (CEST, +02:00 in June) is 09:59:58.222717Z — and
    // critically NOT null, which is what the broken parse produced.
    assertEquals(
        Instant.parse("2026-06-21T09:59:58.222717Z"),
        scheduleBody.getValue().get("plannedStartTime"),
        "an unedited microsecond zoneless schedule time must round-trip, not be nulled on save");
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
        // The map carries the RESOLVED localized message (not the raw {validation.*} key), matching
        // exactly what th:errors renders for the request locale.
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
    // No X-Requested-With → Spring routes to the classic form-post handler (the no-JS fallback),
    // which redirects instead of returning JSON.
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
