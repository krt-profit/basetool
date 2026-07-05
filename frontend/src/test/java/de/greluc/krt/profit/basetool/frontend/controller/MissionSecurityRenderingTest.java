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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class MissionSecurityRenderingTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  /** Static marker attribute that uniquely identifies the inline payout-preference select. */
  private static final String PAYOUT_SELECT_MARKER = "data-trigger=\"mission-update-payout\"";

  /** The payout select as rendered in its disabled state (marker immediately followed by it). */
  private static final String PAYOUT_SELECT_DISABLED =
      PAYOUT_SELECT_MARKER + " disabled=\"disabled\"";

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void missionDetail_AsAnonymous_ShouldDisableRegisteredParticipantPayoutDropdown()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    UserDto user =
        new UserDto(
            userId,
            "TestUser",
            "Test User",
            "Test User",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    MissionParticipantDto participant =
        new MissionParticipantDto(
            participantId,
            user,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            false,
            false,
            1L,
            1L,
            1L,
            1L,
            0,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId),
            org.mockito.ArgumentMatchers
                .<org.springframework.core.ParameterizedTypeReference<Object>>any(),
            anyBoolean()))
        .thenReturn(mission);
    when(backendApiClient.getCached(
            any(CachedCatalog.class),
            org.mockito.ArgumentMatchers
                .<org.springframework.core.ParameterizedTypeReference<Object>>any(),
            anyBoolean()))
        .thenReturn(Collections.emptyList());

    // Anonymously access the mission detail page: a registered participant's payout select must be
    // disabled for guests, and guests never get the edit-participant button on a registered row.
    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(PAYOUT_SELECT_DISABLED)))
        .andExpect(content().string(containsString("payout-preference")))
        // The rendered edit-participant button element must be absent for a guest viewing a
        // registered participant (the "edit-participant-btn" token still appears in the page's
        // JS, so assert on the full rendered class attribute, not the bare CSS class name).
        .andExpect(
            content().string(not(containsString("class=\"btn btn-ghost edit-participant-btn\""))));
  }

  @Test
  @WithMockUser(username = "admin-uuid", roles = "ADMIN")
  void missionDetail_AsAdmin_ShouldEnableRegisteredParticipantPayoutDropdown() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    UserDto user =
        new UserDto(
            userId,
            "TestUser",
            "Test User",
            "Test User",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    MissionParticipantDto participant =
        new MissionParticipantDto(
            participantId,
            user,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId),
            org.mockito.ArgumentMatchers
                .<org.springframework.core.ParameterizedTypeReference<Object>>any(),
            anyBoolean()))
        .thenReturn(mission);
    when(backendApiClient.getCached(
            any(CachedCatalog.class),
            org.mockito.ArgumentMatchers
                .<org.springframework.core.ParameterizedTypeReference<Object>>any(),
            anyBoolean()))
        .thenReturn(Collections.emptyList());

    // Authenticated as Admin
    mockMvc
        .perform(get("/missions/" + missionId))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("disabled=\"disabled\""))));
  }

  @Test
  void missionDetail_AsOtherUser_ShouldDisableRegisteredParticipantPayoutDropdown()
      throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    UserDto user =
        new UserDto(
            userId,
            "TestUser",
            "Test User",
            "Test User",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    MissionParticipantDto participant =
        new MissionParticipantDto(
            participantId,
            user,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            false,
            false,
            1L,
            1L,
            1L,
            1L,
            0,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId),
            org.mockito.ArgumentMatchers
                .<org.springframework.core.ParameterizedTypeReference<Object>>any(),
            anyBoolean()))
        .thenReturn(mission);
    when(backendApiClient.getCached(
            any(CachedCatalog.class),
            org.mockito.ArgumentMatchers
                .<org.springframework.core.ParameterizedTypeReference<Object>>any(),
            anyBoolean()))
        .thenReturn(Collections.emptyList());

    // Authenticated as another user. The OIDC subject (the Keycloak sub, which equals app_user.id)
    // differs from the participant's user id, and preferred_username deliberately differs from the
    // sub to mirror production — the self-edit carve-out must key off the sub (authUserId), never
    // authentication.name (the preferred_username). A foreign member must see the select disabled.
    mockMvc
        .perform(
            get("/missions/" + missionId)
                .with(
                    oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject("11111111-1111-1111-1111-111111111111")
                                    .claim("preferred_username", "other1"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(PAYOUT_SELECT_DISABLED)));
  }

  @Test
  void missionDetail_AsSelf_ShouldEnableRegisteredParticipantPayoutDropdown() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    UUID participantId = UUID.randomUUID();

    UserDto user =
        new UserDto(
            userId,
            "TestUser",
            "Test User",
            "Test User",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    MissionParticipantDto participant =
        new MissionParticipantDto(
            participantId,
            user,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            PayoutPreference.PAYOUT,
            1L,
            null);

    MissionDto mission =
        new MissionDto(
            missionId,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Set.of(participant),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            false,
            false,
            1L,
            1L,
            1L,
            1L,
            0,
            1,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null);

    when(backendApiClient.get(
            eq("/api/v1/missions/" + missionId),
            org.mockito.ArgumentMatchers
                .<org.springframework.core.ParameterizedTypeReference<Object>>any(),
            anyBoolean()))
        .thenReturn(mission);
    when(backendApiClient.getCached(
            any(CachedCatalog.class),
            org.mockito.ArgumentMatchers
                .<org.springframework.core.ParameterizedTypeReference<Object>>any(),
            anyBoolean()))
        .thenReturn(Collections.emptyList());

    // Authenticated as the participant themselves via an OIDC login whose subject (sub) equals the
    // participant's app_user.id. preferred_username is intentionally different from the sub so this
    // test fails if the template ever regresses to comparing against authentication.name. The
    // member's own payout select must be enabled even though they cannot edit the mission itself.
    String expectedUrl =
        "/missions/" + missionId + "/participants/" + participantId + "/payout-preference";
    mockMvc
        .perform(
            get("/missions/" + missionId)
                .with(
                    oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .subject(userId.toString())
                                    .claim("preferred_username", "member1"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(expectedUrl)))
        .andExpect(content().string(not(containsString(PAYOUT_SELECT_DISABLED))));
  }
}
