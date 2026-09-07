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

package de.greluc.krt.profit.basetool.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.AddCrewRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.AddExternalParticipantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateParticipantRequest;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
// REQ-SEC-052: the participant writes these cases exercise require a login. The rows they
// create are EXTERNAL participants now (ADR-0159, decision D4) — a named person without an
// account, recorded by a member who can see the Einsatz — which is the same row shape and a
// different author.
@org.springframework.security.test.context.support.WithMockUser(roles = "KRT_MEMBER")
class MissionValidationTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private ShipRepository shipRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Autowired private MissionService missionService;

  @Autowired private JobTypeRepository jobTypeRepository;

  @Autowired private SquadronRepository squadronRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private Squadron iridium;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User officerUser;
  private Mission mission;
  private MissionUnit missionShip;
  private JobType taskJobType;
  private JobType crewJobType;
  private Squadron testSquadron;

  @BeforeEach
  void setUp() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officer1");
    userRepository.save(officerUser);
    saveIridiumMembership(officerUser);

    testSquadron = new Squadron();
    testSquadron.setName("Test Sq");
    testSquadron.setShorthand("TS");
    testSquadron = squadronRepository.save(testSquadron);

    ShipType st = new ShipType();
    st.setName("Fighter");
    shipTypeRepository.save(st);

    Ship ship = new Ship();

    ship.setOwningOrgUnit(iridium);
    ship.setName("Test Ship");
    ship.setInsurance("10");
    ship.setOwner(officerUser);
    ship.setShipType(st);
    shipRepository.save(ship);

    mission = new Mission();

    mission.setOwningOrgUnit(iridium);
    mission.setName("Test Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    missionService.addParticipant(mission.getId(), officerUser.getId());
    missionService.addUnitToMission(
        mission.getId(), "Test Unit", st.getId(), ship.getId(), false, null, null, null);
    mission = missionRepository.findById(mission.getId()).orElseThrow();
    missionShip = mission.getAssignedUnits().iterator().next();

    taskJobType = new JobType();
    taskJobType.setName("Task Job");
    taskJobType.setArchetype(JobTypeArchetype.MISSION); // Incorrect archetype for crew
    taskJobType = jobTypeRepository.save(taskJobType);

    crewJobType = new JobType();
    crewJobType.setName("Crew Job");
    crewJobType.setArchetype(JobTypeArchetype.CREW); // Incorrect archetype for participant fields
    crewJobType = jobTypeRepository.save(crewJobType);
  }

  /** Post-R9 D3 (V101): home Staffel via membership row. */
  private void saveIridiumMembership(User u) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(u.getId(), Squadron.IRIDIUM_ID));
    m.setUser(u);
    m.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(m);
  }

  private UUID getParticipantId(User user) {
    Mission m = missionRepository.findById(mission.getId()).orElseThrow();
    return m.getParticipants().stream()
        .filter(p -> p.getUser() != null && p.getUser().getId().equals(user.getId()))
        .findFirst()
        .orElseThrow()
        .getId();
  }

  @Test
  void testAddCrewWithInvalidJobTypeArchetype_ShouldReturn400() throws Exception {
    AddCrewRequest request =
        new AddCrewRequest(getParticipantId(officerUser), Set.of(taskJobType.getId()));

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/units/" + missionShip.getId() + "/crew")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest()); // Expecting 400
  }

  @Test
  void testUpdateParticipant_WithCrewArchetypeAsDesired_ShouldReturn400() throws Exception {
    UpdateParticipantRequest request =
        new UpdateParticipantRequest(
            crewJobType.getId(), null, "Invalid Update", null, null, null, null, null, 0L);

    mockMvc
        .perform(
            put("/api/v1/missions/"
                    + mission.getId()
                    + "/participants/"
                    + getParticipantId(officerUser))
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testUpdateParticipant_WithCrewArchetypeAsPlanned_ShouldReturn400() throws Exception {
    UpdateParticipantRequest request =
        new UpdateParticipantRequest(
            null, crewJobType.getId(), "Invalid Update", null, null, null, null, null, 0L);

    mockMvc
        .perform(
            put("/api/v1/missions/"
                    + mission.getId()
                    + "/participants/"
                    + getParticipantId(officerUser))
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testAddParticipantPublic_NameOfAnotherMember_ShouldReturn403() throws Exception {
    // A free-text name that resolves to a registered member is LINKED to that member, which makes
    // this a request to sign somebody else up — and that needs canManageMission. It answered 400
    // ("Guest name is already taken") while the caller could be anonymous and the branch was
    // spoofing protection; with the anonymous caller gone (REQ-SEC-052) one rule covers both
    // spellings of "somebody else", by id and by resolved name.
    AddExternalParticipantRequest request =
        new AddExternalParticipantRequest(null, "officer1", null, null, null, null);

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/participants/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isForbidden());
  }

  @Test
  void testAddParticipantPublic_GuestNameUnique_ShouldReturn200() throws Exception {
    AddExternalParticipantRequest request =
        new AddExternalParticipantRequest(
            null,
            "UniqueGuestName",
            taskJobType.getId(),
            "Comment",
            java.util.List.of(testSquadron.getId()),
            null);

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/participants/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());
  }

  /**
   * Reproduces the bug where an authenticated squadron member types a registered user's exact name
   * (e.g. "lord_adley") without picking it from the autocomplete dropdown. The backend must resolve
   * the name to the matching user instead of rejecting the request with "Guest name is already
   * taken.".
   */
  @Test
  void testAddParticipantPublic_AuthenticatedFreetextNameMatchesCaller_resolvesToSelf()
      throws Exception {
    // Original "freetext name is resolved" scenario, narrowed to self-enrol per audit finding H-1
    // (2026-05-20): the caller may always look themselves up by name; adding *another* registered
    // user requires {@code canManageMission}. The test below exercises the self-resolve branch —
    // the caller types their own name (mixed case + whitespace) instead of using the autocomplete
    // dropdown, and the backend must transparently link them.
    User caller = new User();
    caller.setId(UUID.randomUUID());
    caller.setUsername("lord_adley");
    userRepository.save(caller);
    saveIridiumMembership(caller);

    AddExternalParticipantRequest request =
        new AddExternalParticipantRequest(null, "  Lord_Adley  ", null, null, null, null);

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/participants/add")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(caller.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    Mission refreshed = missionRepository.findById(mission.getId()).orElseThrow();
    boolean linked =
        refreshed.getParticipants().stream()
            .anyMatch(p -> p.getUser() != null && p.getUser().getId().equals(caller.getId()));
    org.junit.jupiter.api.Assertions.assertTrue(
        linked, "Freetext participant name must be resolved to the matching registered user.");
  }

  /**
   * Audit finding H-1 (2026-05-20): an authenticated squadron member typing the name of ANOTHER
   * registered member must not silently end up signing that member up — only mission managers may
   * add foreign users as participants (the {@code addParticipantSlim} branch already enforced this;
   * the legacy {@code /participants/add} path used to let any authenticated caller through).
   */
  @Test
  void testAddParticipantPublic_AuthenticatedNonManager_addingOtherMember_isForbidden()
      throws Exception {
    User target = new User();
    target.setId(UUID.randomUUID());
    target.setUsername("lord_adley");
    userRepository.save(target);
    saveIridiumMembership(target);

    User caller = new User();
    caller.setId(UUID.randomUUID());
    caller.setUsername("caller");
    userRepository.save(caller);
    saveIridiumMembership(caller);

    AddExternalParticipantRequest request =
        new AddExternalParticipantRequest(null, "Lord_Adley", null, null, null, null);

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/participants/add")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(caller.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isForbidden());
  }

  /**
   * Two registered users share the same displayName. A free-text participant entry matching that
   * name is ambiguous and must be rejected with 409 Conflict – never silently assigned.
   */
  @Test
  void testAddParticipantPublic_AmbiguousFreetextName_ShouldReturn409() throws Exception {
    User u1 = new User();
    u1.setId(UUID.randomUUID());
    u1.setUsername("ambig_a");
    u1.setDisplayName("Shared Alias");
    userRepository.save(u1);
    saveIridiumMembership(u1);

    User u2 = new User();
    u2.setId(UUID.randomUUID());
    u2.setUsername("ambig_b");
    u2.setDisplayName("Shared Alias");
    userRepository.save(u2);
    saveIridiumMembership(u2);

    User caller = new User();
    caller.setId(UUID.randomUUID());
    caller.setUsername("caller2");
    userRepository.save(caller);
    saveIridiumMembership(caller);

    AddExternalParticipantRequest request =
        new AddExternalParticipantRequest(null, "Shared Alias", null, null, null, null);

    mockMvc
        .perform(
            post("/api/v1/missions/" + mission.getId() + "/participants/add")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(caller.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isConflict());
  }
}
