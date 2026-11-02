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

package de.greluc.krt.profit.basetool.backend.mission.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * HTTP regression tests of {@code GET /api/v1/missions/next/summary}: a mission whose ancestor the
 * caller may not read never appears, whichever branch (own unit, signed up, unit-less public
 * fallback) selected it, and a Mission Manager grant on an ancestor unit reaches a signed-up
 * internal mission below it (REQ-MISSION-031, REQ-MISSION-008).
 */
@SpringBootTest
@Transactional
class MissionNextSummaryVisibilityTest {

  private static final String SUMMARY = "/api/v1/missions/next/summary";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository missionParticipantRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private BereichRepository bereichRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private MockMvc mockMvc;
  private Squadron unitB;
  private Mission internalParent;
  private Mission publicChild;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString().substring(0, 8);
    unitB = newSquadron("Summary-B-" + tag, "SB" + tag, null);
    internalParent = newMission("Internal parent", unitB, true, null, 1);
    publicChild = newMission("Public child", unitB, false, internalParent, 2);
  }

  @Test
  void aSignedUpMemberOfAnotherUnitDoesNotSeeAPublicChildOfAnUnreadableInternalParent()
      throws Exception {
    User caller = newUser("summary-signed-up");
    join(caller, Squadron.IRIDIUM_ID);
    signUp(publicChild, caller);

    mockMvc
        .perform(get(SUMMARY).with(member(caller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", not(hasItem(publicChild.getId().toString()))));
  }

  @Test
  void aUnitlessMemberDoesNotSeeAPublicChildOfAnUnreadableInternalParent() throws Exception {
    User caller = newUser("summary-unitless");

    mockMvc
        .perform(get(SUMMARY).with(member(caller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", not(hasItem(publicChild.getId().toString()))));
  }

  @Test
  void aMemberOfTheOwningUnitSeesThePublicChildBecauseItReadsTheParentToo() throws Exception {
    User caller = newUser("summary-owner-unit");
    join(caller, unitB.getId());

    mockMvc
        .perform(get(SUMMARY).with(member(caller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", hasItem(publicChild.getId().toString())));
  }

  @Test
  void aMissionManagerGrantOnTheBereichReachesASignedUpInternalMissionBelow() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Bereich bereich = new Bereich();
    bereich.setName("Summary-Bereich-" + tag);
    bereich.setShorthand("SBE" + tag);
    bereich = bereichRepository.save(bereich);
    Squadron below = newSquadron("Summary-Below-" + tag, "SW" + tag, bereich);
    Mission internal = newMission("Internal below", below, true, null, 3);
    User caller = newUser("summary-grantee");
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(caller.getId(), bereich.getId()));
    membership.setUser(caller);
    membership.setKind(OrgUnitKind.BEREICH);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    signUp(internal, caller);

    mockMvc
        .perform(
            get(SUMMARY)
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(caller.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("ROLE_MISSION_MANAGER"),
                            new OrgUnitContextualAuthority("MISSION_MANAGER", bereich.getId()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", hasItem(internal.getId().toString())));
  }

  private static RequestPostProcessor member(User caller) {
    return jwt()
        .jwt(builder -> builder.subject(caller.getId().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private Squadron newSquadron(String name, String shorthand, Bereich parent) {
    Squadron squadron = new Squadron();
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    squadron.setParent(parent);
    return squadronRepository.save(squadron);
  }

  private User newUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + user.getId().toString().substring(0, 8));
    return userRepository.save(user);
  }

  private void join(User user, UUID unitId) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unitId));
    membership.setUser(user);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
  }

  private Mission newMission(
      String name, Squadron owner, boolean internal, Mission parent, int hoursAhead) {
    Mission mission = new Mission();
    mission.setName(name);
    mission.setOwningOrgUnit(owner);
    mission.setIsInternal(internal);
    mission.setParent(parent);
    mission.setStatus("PLANNED");
    mission.setPlannedStartTime(Instant.now().plus(hoursAhead, ChronoUnit.HOURS));
    return missionRepository.save(mission);
  }

  private void signUp(Mission mission, User user) {
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(user);
    missionParticipantRepository.save(participant);
  }
}
