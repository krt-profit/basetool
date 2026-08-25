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
 * Security-focused MockMvc tests for {@link MissionFinanceEntryController#createFinanceEntry} and
 * the finance-read endpoints. The finance ledger is the mission's payout view and is restricted to
 * registered members and above ({@code isMemberOrAbove}): anonymous callers AND authenticated but
 * role-less {@code GUEST} accounts are blocked, mirroring the "treat guest like anonymous on the
 * mission surface" rule. The rules pinned here are:
 *
 * <ul>
 *   <li>anonymous create → 401 (URL gate requires authentication), no service call,
 *   <li>role-less GUEST create → 403 (method gate requires a member), no service call,
 *   <li>member / officer create on an in-scope mission → 201 with the nested participant's email
 *       stripped (H-1),
 *   <li>member create on a mission they may only <em>read</em> → 403, because the write gate is
 *       {@code canCreateFinanceEntry} and not the public-escape-granting {@code canSeeMission}
 *       (REQ-SEC-042),
 *   <li>GUEST read → 403; member / officer read → 200 with participant email stripped,
 *   <li>oversized {@code note} or out-of-range {@code amount} (member caller) → 400 before the
 *       service is hit.
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
   * An authenticated but role-less GUEST — passes {@code isAuthenticated()} but not a member gate.
   */
  private static SimpleGrantedAuthority guest() {
    return new SimpleGrantedAuthority("ROLE_GUEST");
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
            // effectiveName == displayName by construction (User.getEffectiveName), never a
            // realname
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
            UUID.randomUUID(), user, null, null, null, null, null, null, null, null, 1L, null);
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

    // The finance ledger is no longer anonymous: POST /api/v1/finance-entries is URL-gated to
    // authenticated callers, so an anonymous request is rejected with 401 (the resource server's
    // bearer entry point) before the controller or any service is reached.
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
  void createFinanceEntry_roleLessGuest_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    // canSeeMission would pass for a non-internal mission, but the method gate also requires
    // isMemberOrAbove(); a role-less GUEST is treated like an anonymous visitor and denied with 403
    // before the service is invoked.
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
                .with(jwt().authorities(guest())))
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
            // The public callsign confirms which line was created…
            .andExpect(jsonPath("$.participant.user.username").value("bob.callsign"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // …but H-1: even a member must not get a peer's email back on create.
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
            // the participant callsign still confirms which line was created…
            .andExpect(jsonPath("$.participant.user.username").value("bob.callsign"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // …but H-1 (refined): even an authenticated Officer must not get a peer's email back on create.
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "authenticated create response must not echo the participant's email");
  }

  /**
   * REQ-SEC-042: a member who may merely <em>see</em> the mission may not book into its ledger.
   *
   * <p>This is the regression the create gate shipped with: it was gated on {@code
   * ownerScopeService.canSeeMission}, which deliberately grants the cross-squadron public escape on
   * a non-internal mission. A member of another squadron could therefore post income/expense rows
   * into that mission's payout ledger and attribute them to one of its participants — while editing
   * or deleting the very same row required being its owner or an officer in scope. The stub below
   * reproduces exactly that state: the read gate says yes, the write gate says no, and the write
   * gate is the one that decides.
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

  // ---------------------------------------------------------------------------
  // Audit finding H-1: the mission-finance READ endpoints used to be gated only by
  // isAuthenticated(), so any authenticated user could read any mission's ledger (and the nested
  // participant email) by UUID — a cross-squadron IDOR. They now carry
  // @ownerScopeService.canSeeMission AND redact participant PII for every caller (email is a
  // profile-only field — never echoed to a peer, not even to a Logistician/Officer).
  // ---------------------------------------------------------------------------

  @Test
  void getFinanceEntries_authenticatedNonMember_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    // canSeeMission == false models a foreign squadron's internal mission: the @PreAuthorize gate
    // denies before the service is ever invoked.
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
  void getFinanceEntries_roleLessGuest_isForbidden() throws Exception {
    UUID missionId = UUID.randomUUID();
    // Even with canSeeMission granting visibility of a non-internal mission, a role-less GUEST is
    // treated like an anonymous visitor on the mission's payout view: isMemberOrAbove() fails the
    // method gate, so the ledger read is denied with 403 before the service is invoked.
    when(ownerScopeService.canSeeMission(missionId)).thenReturn(true);

    mockMvc
        .perform(
            get("/api/v1/missions/{id}/finance-entries", missionId)
                .with(jwt().authorities(guest())))
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
            // public callsign stays visible
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
            // the public callsign still comes through — only the PII is stripped
            .andExpect(jsonPath("$.content[0].participant.user.username").value("bob.callsign"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // H-1 (refined): redaction is unconditional — even an Officer must not receive a peer's email
    // through the ledger; email is shown only to the user themselves in their own profile.
    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("bob@example.invalid"),
        "an Officer must not receive participant email through the finance ledger either");
  }
}
