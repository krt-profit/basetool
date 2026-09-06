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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFrequency;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the additive slim sub-resource endpoints introduced for multi-user concurrency on the
 * mission detail page (Option A, Paket 2). The legacy MissionDto-returning endpoints are deprecated
 * via @ApiDeprecation(sunset = 2026-10-20) and remain functional; these tests focus on the new
 * {@code /slim} endpoints: they must be reachable under the same role gates, they must return slim
 * sub-DTOs (not the full MissionDto), and DELETE variants must return 204 No Content.
 */
@SpringBootTest
class MissionControllerSlimEndpointsTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private MissionService missionService;

  @MockitoBean
  private de.greluc.krt.profit.basetool.backend.service.MissionSecurityService
      missionSecurityService;

  @MockitoBean private de.greluc.krt.profit.basetool.backend.service.UserService userService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @MockitoBean
  private de.greluc.krt.profit.basetool.backend.service.OwnerScopeService ownerScopeService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // The participant-add / participant-slim endpoints are now gated by
    // `@ownerScopeService.canSeeMission(#id)` (MULTI_SQUADRON_PLAN.md §1: non-internal
    // missions are open to anonymous + cross-staffel callers). These tests target the slim
    // endpoints' branching logic, not the squadron gate — default the gate to true so the
    // controller code path under test actually runs.
    when(ownerScopeService.canSeeMission(any(UUID.class))).thenReturn(true);
  }

  private SimpleGrantedAuthority officer() {
    return new SimpleGrantedAuthority("ROLE_OFFICER");
  }

  private Mission missionWithUnit(UUID unitId) {
    Mission mission = new Mission();
    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    unit.setName("Alpha");
    unit.setCrew(new LinkedHashSet<>());
    Set<MissionUnit> units = new LinkedHashSet<>();
    units.add(unit);
    mission.setAssignedUnits(units);
    return mission;
  }

  @Test
  void addUnitSlim_returnsSlimListOfUnits() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.addUnitToMission(
            any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
        .thenReturn(missionWithUnit(unitId));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/units/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alpha\",\"isHighValueUnit\":false}")
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].id").value(unitId.toString()))
        .andExpect(jsonPath("$[0].name").value("Alpha"));
  }

  @Test
  void updateUnitSlim_returnsSingleSlimUnit() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.updateMissionUnit(
            any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
        .thenReturn(missionWithUnit(unitId));

    mockMvc
        .perform(
            put("/api/v1/missions/{id}/units/{unitId}/slim", missionId, unitId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alpha\",\"isHighValueUnit\":false}")
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(unitId.toString()))
        .andExpect(jsonPath("$.name").value("Alpha"));
  }

  @Test
  void deleteUnitSlim_returns204NoContent() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.removeMissionUnit(any(), any())).thenReturn(new Mission());

    mockMvc
        .perform(
            delete("/api/v1/missions/{id}/units/{unitId}/slim", missionId, unitId)
                .with(jwt().authorities(officer())))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  @Test
  void addManagerSlim_returnsSlimUserReferenceList() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Mission mission = new Mission();
    User manager = new User();
    manager.setId(userId);
    manager.setUsername("user.one");
    Set<User> managers = new HashSet<>();
    managers.add(manager);
    mission.setManagers(managers);

    when(missionSecurityService.canManageManagers(any(UUID.class), any())).thenReturn(true);
    when(missionService.addManager(any(), any())).thenReturn(mission);

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/managers/{userId}/slim", missionId, userId)
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].id").value(userId.toString()))
        .andExpect(jsonPath("$[0].username").value("user.one"));
  }

  @Test
  void removeManagerSlim_returns204NoContent() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(missionSecurityService.canManageManagers(any(UUID.class), any())).thenReturn(true);
    when(missionService.removeManager(any(), any())).thenReturn(new Mission());

    mockMvc
        .perform(
            delete("/api/v1/missions/{id}/managers/{userId}/slim", missionId, userId)
                .with(jwt().authorities(officer())))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  @Test
  void addFrequencySlim_returnsSlimFrequencyList() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID frequencyTypeId = UUID.randomUUID();
    UUID frequencyId = UUID.randomUUID();

    Mission mission = new Mission();
    MissionFrequency freq = new MissionFrequency();
    freq.setId(frequencyId);
    freq.setValue(new BigDecimal("27.555"));
    Set<MissionFrequency> freqs = new LinkedHashSet<>();
    freqs.add(freq);
    mission.setFrequencies(freqs);

    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.addOrUpdateMissionFrequency(any(), any(), any())).thenReturn(mission);

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/frequencies/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"frequencyTypeId\":\"" + frequencyTypeId + "\",\"value\":27.555}")
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].id").value(frequencyId.toString()));
  }

  private Mission missionWithCustomFrequency(UUID frequencyId, String name) {
    Mission mission = new Mission();
    MissionFrequency freq = new MissionFrequency();
    freq.setId(frequencyId);
    freq.setName(name);
    freq.setValue(new BigDecimal("42.10"));
    Set<MissionFrequency> freqs = new LinkedHashSet<>();
    freqs.add(freq);
    mission.setFrequencies(freqs);
    return mission;
  }

  @Test
  void addCustomFrequencySlim_returnsSlimFrequencyList() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID frequencyId = UUID.randomUUID();

    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.addCustomMissionFrequency(any(), any(), any()))
        .thenReturn(missionWithCustomFrequency(frequencyId, "Recon"));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/frequencies/custom/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Recon\",\"value\":42.10}")
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].id").value(frequencyId.toString()))
        .andExpect(jsonPath("$[0].name").value("Recon"));
  }

  @Test
  void updateCustomFrequencySlim_returnsSlimFrequencyList() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID frequencyId = UUID.randomUUID();

    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.updateCustomMissionFrequency(any(), any(), any(), any(), any()))
        .thenReturn(missionWithCustomFrequency(frequencyId, "Recon 2"));

    mockMvc
        .perform(
            put("/api/v1/missions/{id}/frequencies/custom/{fid}/slim", missionId, frequencyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Recon 2\",\"value\":42.10,\"version\":0}")
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Recon 2"));
  }

  @Test
  void addCustomFrequencySlim_blankName_isBadRequest() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/frequencies/custom/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \",\"value\":42.10}")
                .with(jwt().authorities(officer())))
        .andExpect(status().isBadRequest());

    org.mockito.Mockito.verify(missionService, org.mockito.Mockito.never())
        .addCustomMissionFrequency(any(), any(), any());
  }

  @Test
  void addCustomFrequencySlim_valueOutOfRange_isBadRequest() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);

    // 1000.00 exceeds the shared 999.99 / three-integer-digit limit.
    mockMvc
        .perform(
            post("/api/v1/missions/{id}/frequencies/custom/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Recon\",\"value\":1000.00}")
                .with(jwt().authorities(officer())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addCustomFrequencySlim_withoutPermission_returns403() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/frequencies/custom/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Recon\",\"value\":42.10}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteUnitSlim_withoutPermission_returns403() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(false);

    mockMvc
        .perform(
            delete("/api/v1/missions/{id}/units/{unitId}/slim", missionId, unitId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  private static boolean anyBoolean() {
    return org.mockito.ArgumentMatchers.anyBoolean();
  }

  // --- Participants slim (self-enroll fix) -------------------------------

  private Mission missionWithParticipant(UUID participantId, UUID userId) {
    Mission mission = new Mission();
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    User u = new User();
    u.setId(userId);
    u.setUsername("self.user");
    p.setUser(u);
    Set<MissionParticipant> set = new LinkedHashSet<>();
    set.add(p);
    mission.setParticipants(set);
    return mission;
  }

  @Test
  void addParticipantSlim_memberSelfEnroll_isAllowed() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(userService.getUserIdFromJwt(any())).thenReturn(userId);
    // non-manager
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(false);
    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(missionWithParticipant(participantId, userId));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(
                    jwt()
                        .jwt(j -> j.subject(userId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].id").value(participantId.toString()));
  }

  @Test
  void addParticipantSlim_memberAddingOtherUser_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID callerId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();

    when(userService.getUserIdFromJwt(any())).thenReturn(callerId);
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + otherId + "\"}")
                .with(
                    jwt()
                        .jwt(j -> j.subject(callerId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  /**
   * Reproducer for the "anonymous guest cannot sign up to a mission" bug (see live-log/log.txt: 401
   * UNAUTHENTICATED on POST /api/v1/missions/{id}/participants/slim for `[anonymous]`).
   *
   * <p>Backend SecurityConfig must permit anonymous POSTs to the slim signup endpoint, mirroring
   * the legacy `/participants/add` permit rule, so the controller's own anonymous-guest branch (jwt
   * == null + guestName) can run.
   */
  @Test
  void addParticipantSlim_anonymousGuest_withGuestName_isAllowed() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    Mission mission = new Mission();
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setGuestName("Anon-Guest");
    Set<MissionParticipant> set = new LinkedHashSet<>();
    set.add(p);
    mission.setParticipants(set);

    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(mission);

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestName\":\"Anon-Guest\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].id").value(participantId.toString()));
  }

  /**
   * Reproducer for the follow-up bug "anonymous guest cannot edit/delete their own participant
   * entry" (see live-log/log.txt: 403 on PUT/DELETE /missions/{id}/participants/{pid}/ajax for
   * `[anonymous]`).
   *
   * <p>Backend SecurityConfig must permit anonymous PUT and DELETE on the slim participant
   * sub-resource so that {@code MissionSecurityService#canAccessParticipant} (which already returns
   * true for guest entries with {@code user == null}) can apply.
   */
  @Test
  void updateParticipantSlim_anonymousGuest_isAllowed() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    Mission mission = new Mission();
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setGuestName("Anon-Guest");
    Set<MissionParticipant> set = new LinkedHashSet<>();
    set.add(p);
    mission.setParticipants(set);

    when(missionSecurityService.canAccessParticipant(any(UUID.class), any(UUID.class), any()))
        .thenReturn(true);
    when(missionService.updateParticipantAttributes(
            any(UUID.class),
            any(UUID.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(mission);

    mockMvc
        .perform(
            put("/api/v1/missions/{id}/participants/{pid}/slim", missionId, participantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"guestName\":\"Anon-Guest\",\"comment\":\"edited\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(participantId.toString()));
  }

  @Test
  void deleteParticipantSlim_anonymousGuest_isAllowed() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(missionSecurityService.canAccessParticipant(any(UUID.class), any(UUID.class), any()))
        .thenReturn(true);

    mockMvc
        .perform(delete("/api/v1/missions/{id}/participants/{pid}/slim", missionId, participantId))
        .andExpect(status().isNoContent());
  }

  @Test
  void addParticipantSlim_officerAddingOtherUser_isAllowed() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID callerId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(userService.getUserIdFromJwt(any())).thenReturn(callerId);
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(missionWithParticipant(participantId, otherId));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + otherId + "\"}")
                .with(jwt().jwt(j -> j.subject(callerId.toString())).authorities(officer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(participantId.toString()));
  }

  // ===================================================================
  // RSVP security branches — anonymous spoof / name-resolution paths
  // that the existing 4 cases above don't reach. The CLAUDE.md
  // "Multi-user data isolation (CRITICAL)" rule lives or dies in these.
  // ===================================================================

  @Test
  void addParticipantSlim_anonymousSubmittingUserId_isForbidden() throws Exception {
    // SECURITY: an anonymous caller cannot manufacture a "User" RSVP. The
    // controller throws AccessDeniedException -> 403 BEFORE any service
    // call.
    UUID missionId = UUID.randomUUID();
    UUID spoofUserId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + spoofUserId + "\"}"))
        .andExpect(status().isForbidden());

    org.mockito.Mockito.verify(missionService, org.mockito.Mockito.never())
        .addParticipant(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void addParticipantSlim_guestNameMatchesMultipleUsers_isConflict() throws Exception {
    // Ambiguous free-text name -> 409 BusinessConflictException, regardless
    // of authenticated-vs-anonymous.
    UUID missionId = UUID.randomUUID();

    User a = new User();
    a.setId(UUID.randomUUID());
    a.setUsername("alex");
    User b = new User();
    b.setId(UUID.randomUUID());
    b.setUsername("alex");
    when(userService.findMatchesByExactName("alex")).thenReturn(java.util.List.of(a, b));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestName\":\"alex\"}"))
        .andExpect(status().isConflict());

    org.mockito.Mockito.verify(missionService, org.mockito.Mockito.never())
        .addParticipant(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void addParticipantSlim_authenticatedTypesOwnNameAsGuest_transparentlyLinked() throws Exception {
    // Bug fix lock-in: authenticated user types their own name without
    // hitting autocomplete -> name resolves to a single registered user
    // -> link transparently (finalUserId set, finalGuestName cleared).
    UUID missionId = UUID.randomUUID();
    UUID callerId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    User registered = new User();
    registered.setId(callerId);
    registered.setUsername("alice");
    when(userService.findMatchesByExactName("alice")).thenReturn(java.util.List.of(registered));
    when(userService.getUserIdFromJwt(any())).thenReturn(callerId);
    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(missionWithParticipant(participantId, callerId));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestName\":\"alice\"}")
                .with(
                    jwt()
                        .jwt(j -> j.subject(callerId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(participantId.toString()));

    // Verify the service was called with userId=callerId and guestName=null
    // (the transparent-link transformation).
    org.mockito.ArgumentCaptor<UUID> userIdCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
    org.mockito.ArgumentCaptor<String> guestNameCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(missionService)
        .addParticipant(
            any(), userIdCaptor.capture(), guestNameCaptor.capture(), any(), any(), any(), any());
    org.junit.jupiter.api.Assertions.assertEquals(
        callerId,
        userIdCaptor.getValue(),
        "guestName matching one registered user must transparently set finalUserId");
    org.junit.jupiter.api.Assertions.assertNull(
        guestNameCaptor.getValue(),
        "guestName must be cleared once it resolves to a registered userId");
  }

  @Test
  void addParticipantSlim_anonymousUsingRegisteredName_isBadRequest() throws Exception {
    // SECURITY: anonymous user types a free-text name that resolves to a
    // registered member -> reject (cannot impersonate a member as a guest).
    UUID missionId = UUID.randomUUID();
    User registered = new User();
    registered.setId(UUID.randomUUID());
    registered.setUsername("alice");
    when(userService.findMatchesByExactName("alice")).thenReturn(java.util.List.of(registered));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestName\":\"alice\"}"))
        .andExpect(status().isBadRequest());

    org.mockito.Mockito.verify(missionService, org.mockito.Mockito.never())
        .addParticipant(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void addParticipantSlim_authenticatedEmptyForm_selfEnrolls() throws Exception {
    // Existing memberSelfEnroll_isAllowed test covers part of this, but
    // here we explicitly assert the controller calls addParticipant with
    // finalUserId == caller's id (NOT null, NOT spoofed).
    UUID missionId = UUID.randomUUID();
    UUID callerId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    when(userService.getUserIdFromJwt(any())).thenReturn(callerId);
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(false);
    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(missionWithParticipant(participantId, callerId));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/slim", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(
                    jwt()
                        .jwt(j -> j.subject(callerId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());

    org.mockito.ArgumentCaptor<UUID> userIdCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
    org.mockito.Mockito.verify(missionService)
        .addParticipant(any(), userIdCaptor.capture(), any(), any(), any(), any(), any());
    org.junit.jupiter.api.Assertions.assertEquals(
        callerId,
        userIdCaptor.getValue(),
        "empty form + authenticated caller -> self-enroll with caller's id");
  }

  // ===================================================================
  // addParticipantPublic — public RSVP endpoint with the same branching
  // (no slim wrapping). The legacy endpoint is permitAll() and used by
  // anonymous / unauthenticated mission RSVPs.
  // ===================================================================

  @Test
  void addParticipantPublic_anonymousSubmittingUserId_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID spoofUserId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/add", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + spoofUserId + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void addParticipantPublic_anonymousUsingRegisteredName_isBadRequest() throws Exception {
    UUID missionId = UUID.randomUUID();
    User registered = new User();
    registered.setId(UUID.randomUUID());
    registered.setUsername("alice");
    when(userService.findMatchesByExactName("alice")).thenReturn(java.util.List.of(registered));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/add", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestName\":\"alice\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addParticipantPublic_anonymousAmbiguousName_isConflict() throws Exception {
    UUID missionId = UUID.randomUUID();
    User a = new User();
    a.setId(UUID.randomUUID());
    a.setUsername("alex");
    User b = new User();
    b.setId(UUID.randomUUID());
    b.setUsername("alex");
    when(userService.findMatchesByExactName("alex")).thenReturn(java.util.List.of(a, b));

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/participants/add", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestName\":\"alex\"}"))
        .andExpect(status().isConflict());
  }

  // ===================================================================
  // Audit finding C-1: anonymous callers of addParticipantPublic and
  // addParticipantSlim must never receive participant emails / real
  // names — the response shape must match getMissionById's redacted
  // shape. Authenticated callers (officer, member) keep the full PII so
  // existing UI flows are unchanged.
  // ===================================================================

  /**
   * Builds a mission whose participant set already contains an OTHER registered user (Bob, email
   * populated) plus the newly-added guest entry. Reflects the response shape both endpoints
   * assemble: the entire participant list is returned, so the response carries everyone's PII
   * unless redaction kicks in.
   */
  private Mission missionWithOtherUserAndGuest(UUID otherUserId, UUID guestParticipantId) {
    Mission mission = new Mission();
    Set<MissionParticipant> set = new LinkedHashSet<>();

    MissionParticipant bobEntry = new MissionParticipant();
    bobEntry.setId(UUID.randomUUID());
    User bob = new User();
    bob.setId(otherUserId);
    bob.setUsername("bob.callsign");
    bob.setEmail("bob@example.invalid");
    bobEntry.setUser(bob);
    // ADR-0034: bob carries a free-text comment + the default PAYOUT preference so the outsider
    // tests can assert both are stripped from the anonymous response.
    bobEntry.setComment("bob-private-note");
    set.add(bobEntry);

    MissionParticipant guestEntry = new MissionParticipant();
    guestEntry.setId(guestParticipantId);
    guestEntry.setGuestName("Anon-Guest");
    set.add(guestEntry);

    mission.setParticipants(set);
    return mission;
  }

  @Test
  void addParticipantSlim_anonymousGuest_redactsOtherParticipantsPii() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID guestParticipantId = UUID.randomUUID();

    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(missionWithOtherUserAndGuest(otherUserId, guestParticipantId));

    String body =
        mockMvc
            .perform(
                post("/api/v1/missions/{id}/participants/slim", missionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"guestName\":\"Anon-Guest\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Outsiders see the roster on a non-internal mission, but PII is stripped to the public
    // callsign
    // tuple — the username stays so guests can identify participants, the email never leaks
    // (audit finding C-1).
    org.junit.jupiter.api.Assertions.assertTrue(
        body.contains("bob.callsign"), "username must remain visible to guests");
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "anonymous response must not leak participant email — audit finding C-1");
    // ADR-0034 / REQ-SEC-021: the anonymous outsider roster must not carry a participant's
    // free-text comment or payout preference.
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob-private-note"),
        "ADR-0034: anonymous outsider must not receive a participant's free-text comment");
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("PAYOUT"),
        "ADR-0034: anonymous outsider must not receive a participant's payout preference");
  }

  @Test
  void addParticipantSlim_authenticatedOfficer_stripsParticipantEmail() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID callerId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID guestParticipantId = UUID.randomUUID();

    when(userService.getUserIdFromJwt(any())).thenReturn(callerId);
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(missionWithOtherUserAndGuest(otherUserId, guestParticipantId));

    String body =
        mockMvc
            .perform(
                post("/api/v1/missions/{id}/participants/slim", missionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"guestName\":\"Anon-Guest\"}")
                    .with(jwt().jwt(j -> j.subject(callerId.toString())).authorities(officer())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // H-1 (refined): email is a profile-only field — even an authenticated Officer must not receive
    // a peer's email through the roster; only the public callsign stays visible.
    org.junit.jupiter.api.Assertions.assertTrue(
        body.contains("bob.callsign"), "participant callsign stays visible on the roster");
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "authenticated officer must NOT receive the participant's email");
  }

  @Test
  void addParticipantPublic_anonymousGuest_redactsOtherParticipantsPii() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID guestParticipantId = UUID.randomUUID();

    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(missionWithOtherUserAndGuest(otherUserId, guestParticipantId));

    String body =
        mockMvc
            .perform(
                post("/api/v1/missions/{id}/participants/add", missionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"guestName\":\"Anon-Guest\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The legacy public-add returns the full MissionDto; for an outsider it is redacted via
    // cleanupOutsiderMissionForPeer, which keeps the roster (PII stripped) and hides only the
    // description. The username stays visible, the email never leaks (audit finding C-1).
    org.junit.jupiter.api.Assertions.assertTrue(
        body.contains("bob.callsign"), "username must remain visible to guests");
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"), "an outsider must never receive a participant email");
  }

  @Test
  void addParticipantPublic_authenticatedOfficer_stripsParticipantEmail() throws Exception {
    UUID missionId = UUID.randomUUID();
    UUID callerId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID guestParticipantId = UUID.randomUUID();

    when(userService.getUserIdFromJwt(any())).thenReturn(callerId);
    when(missionSecurityService.canManageMission(any(UUID.class), any())).thenReturn(true);
    when(missionService.addParticipant(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(missionWithOtherUserAndGuest(otherUserId, guestParticipantId));

    String body =
        mockMvc
            .perform(
                post("/api/v1/missions/{id}/participants/add", missionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"guestName\":\"Anon-Guest\"}")
                    .with(jwt().jwt(j -> j.subject(callerId.toString())).authorities(officer())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // H-1 (refined): email is profile-only — an authenticated Officer must not receive a peer's
    // email through the public-add roster response either; only the callsign stays visible.
    org.junit.jupiter.api.Assertions.assertTrue(
        body.contains("bob.callsign"), "participant callsign stays visible on the roster");
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "authenticated officer must NOT receive the participant's email");
  }
}
