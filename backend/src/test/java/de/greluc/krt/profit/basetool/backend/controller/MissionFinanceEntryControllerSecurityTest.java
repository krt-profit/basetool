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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.service.MissionFinanceEntryService;
import de.greluc.krt.profit.basetool.backend.service.MissionSecurityService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Security tests for {@link MissionFinanceEntryController#createFinanceEntry} and the finance
 * reads, restricted to members and above:
 *
 * <ul>
 *   <li>anonymous create → 401, role-less create → 403, no service call;
 *   <li>member / officer create on an in-scope mission → 201 with participant e-mail stripped;
 *   <li>member create on a mission they may only read → 403 (REQ-SEC-042);
 *   <li>role-less read → 403; member / officer read → 200 with participant e-mail stripped;
 *   <li>oversized {@code note} or out-of-range {@code amount} → 400 before the service.
 * </ul>
 */
@SpringBootTest
class MissionFinanceEntryControllerSecurityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private MissionFinanceEntryService financeEntryService;
  @MockitoBean private OwnerScopeService ownerScopeService;
  @MockitoBean private MissionSecurityService missionSecurityService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static SimpleGrantedAuthority officer() {
    return new SimpleGrantedAuthority("ROLE_OFFICER");
  }

  private static SimpleGrantedAuthority member() {
    return new SimpleGrantedAuthority("ROLE_KRT_MEMBER");
  }

  /**
   * An authenticated but role-less authority that passes {@code isAuthenticated()} but no member
   * gate (REQ-SEC-053).
   */
  private static SimpleGrantedAuthority roleLess() {
    return new SimpleGrantedAuthority("ROLE_NO_ROLE");
  }

  /**
   * Builds a finance-entry DTO whose nested participant carries a registered user with PII
   * populated — the response shape the controller assembles after a successful service call.
   */
  private static MissionFinanceEntryDto persistedEntryWithUserPii(UUID missionId) {
    UserDto user =
        new UserDto(
            UUID.randomUUID(),
            "bob.callsign",
            "Bob",
            "Bob",
            "bob@example.invalid",
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
            UUID.randomUUID(), user, null, null, null, null, null, null, null, null, 1L);
    return new MissionFinanceEntryDto(
        UUID.randomUUID(),
        missionId,
        participant,
        "note",
        FinanceType.INCOME,
        new BigDecimal("500.00"),
        1L);
  }

  @Test
  void createFinanceEntry_anonymous_isUnauthorized() throws Exception {
    UUID missionId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/finance-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"missionId\":\""
                        + missionId
                        + "\",\"participantId\":\""
                        + UUID.randomUUID()
                        + "\",\"type\":\"INCOME\",\"amount\":500.00,\"note\":\"my-line\"}"))
        .andExpect(status().isUnauthorized());

    verify(financeEntryService, never()).createEntry(any());
  }

  @Test
  void createFinanceEntry_roleLessRoleLess_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(true);

    mockMvc
        .perform(
            post("/api/v1/finance-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"missionId\":\""
                        + missionId
                        + "\",\"participantId\":\""
                        + UUID.randomUUID()
                        + "\",\"type\":\"INCOME\",\"amount\":500.00}")
                .with(jwt().authorities(roleLess())))
        .andExpect(status().isForbidden());

    verify(financeEntryService, never()).createEntry(any());
  }

  @Test
  void createFinanceEntry_member_returnsEntryWithParticipantEmailStripped() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(missionSecurityService.canCreateFinanceEntry(any(), any(), any())).thenReturn(true);
    when(financeEntryService.createEntry(any())).thenReturn(persistedEntryWithUserPii(missionId));

    String body =
        mockMvc
            .perform(
                post("/api/v1/finance-entries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"missionId\":\""
                            + missionId
                            + "\",\"participantId\":\""
                            + UUID.randomUUID()
                            + "\",\"type\":\"INCOME\",\"amount\":500.00,\"note\":\"my-line\"}")
                    .with(jwt().authorities(member())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.participant.user.username").value("bob.callsign"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "member create response must not echo the participant's email");
  }

  @Test
  void createFinanceEntry_authenticatedOfficer_stripsParticipantEmail() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(missionSecurityService.canCreateFinanceEntry(any(), any(), any())).thenReturn(true);
    when(financeEntryService.createEntry(any())).thenReturn(persistedEntryWithUserPii(missionId));

    String body =
        mockMvc
            .perform(
                post("/api/v1/finance-entries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"missionId\":\""
                            + missionId
                            + "\",\"participantId\":\""
                            + UUID.randomUUID()
                            + "\",\"type\":\"INCOME\",\"amount\":500.00}")
                    .with(jwt().authorities(officer())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.participant.user.username").value("bob.callsign"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "authenticated create response must not echo the participant's email");
  }

  /**
   * Verifies that a member who may only see the mission may not book into its ledger (REQ-SEC-042).
   */
  @Test
  void createFinanceEntry_memberWhoMayOnlySeeTheMission_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(true);
    when(missionSecurityService.canCreateFinanceEntry(any(), any(), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/finance-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"missionId\":\""
                        + missionId
                        + "\",\"participantId\":\""
                        + UUID.randomUUID()
                        + "\",\"type\":\"EXPENSE\",\"amount\":99999999.00}")
                .with(jwt().authorities(member())))
        .andExpect(status().isForbidden());

    verify(financeEntryService, never()).createEntry(any());
  }

  @Test
  void createFinanceEntry_noteOver2000Chars_isBadRequest() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(true);

    String oversizedNote = "a".repeat(2001);
    mockMvc
        .perform(
            post("/api/v1/finance-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"missionId\":\""
                        + missionId
                        + "\",\"participantId\":\""
                        + UUID.randomUUID()
                        + "\",\"type\":\"INCOME\",\"amount\":500.00,\"note\":\""
                        + oversizedNote
                        + "\"}")
                .with(jwt().authorities(member())))
        .andExpect(status().isBadRequest());

    verify(financeEntryService, never()).createEntry(any());
  }

  @Test
  void createFinanceEntry_amountOverCap_isBadRequest() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(true);

    mockMvc
        .perform(
            post("/api/v1/finance-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"missionId\":\""
                        + missionId
                        + "\",\"participantId\":\""
                        + UUID.randomUUID()
                        + "\",\"type\":\"INCOME\",\"amount\":1000000000.01}")
                .with(jwt().authorities(member())))
        .andExpect(status().isBadRequest());

    verify(financeEntryService, never()).createEntry(any());
  }

  @Test
  void getFinanceEntries_authenticatedNonMember_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(false);

    mockMvc
        .perform(
            get("/api/v1/missions/{id}/finance-entries", missionId)
                .with(jwt().authorities(member())))
        .andExpect(status().isForbidden());

    verify(financeEntryService, never()).getEntriesByMission(any(), any());
  }

  @Test
  void getFinanceEntriesSum_authenticatedNonMember_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(false);

    mockMvc
        .perform(
            get("/api/v1/missions/{id}/finance-entries/sum", missionId)
                .with(jwt().authorities(member())))
        .andExpect(status().isForbidden());

    verify(financeEntryService, never()).calculateTotalSum(any());
  }

  @Test
  void getFinanceEntries_roleLessRoleLess_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(true);

    mockMvc
        .perform(
            get("/api/v1/missions/{id}/finance-entries", missionId)
                .with(jwt().authorities(roleLess())))
        .andExpect(status().isForbidden());

    verify(financeEntryService, never()).getEntriesByMission(any(), any());
  }

  @Test
  void getFinanceEntries_inScopeMember_redactsParticipantPii() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(true);
    when(financeEntryService.getEntriesByMission(any(), any()))
        .thenReturn(new PageImpl<>(List.of(persistedEntryWithUserPii(missionId))));

    String body =
        mockMvc
            .perform(
                get("/api/v1/missions/{id}/finance-entries", missionId)
                    .with(jwt().authorities(member())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].participant.user.username").value("bob.callsign"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "an in-scope member must not receive participant email through the finance ledger");
  }

  @Test
  void getFinanceEntries_officer_alsoRedactsParticipantEmail() throws Exception {
    UUID missionId = UUID.randomUUID();
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(true);
    when(financeEntryService.getEntriesByMission(any(), any()))
        .thenReturn(new PageImpl<>(List.of(persistedEntryWithUserPii(missionId))));

    String body =
        mockMvc
            .perform(
                get("/api/v1/missions/{id}/finance-entries", missionId)
                    .with(jwt().authorities(officer())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].participant.user.username").value("bob.callsign"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "an Officer must not receive participant email through the finance ledger either");
  }
}
