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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
class FeatureExpansionTest {

  @Autowired private SquadronRepository squadronRepository;

  private Squadron iridium;

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private LocationRepository locationRepository;

  @Autowired private ShipRepository shipRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private MissionParticipantRepository missionParticipantRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @Autowired private ManufacturerRepository manufacturerRepository;

  @Autowired private StarSystemRepository starSystemRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User officerUser;
  private User normalUser;
  private User otherUser;
  private ShipType fighter;
  private Location stanton;

  @BeforeEach
  void setUp() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    officerUser = new User();
    officerUser.setId(UUID.randomUUID());
    officerUser.setUsername("officerExp");
    userRepository.save(officerUser);
    saveIridiumMembership(officerUser);

    normalUser = new User();
    normalUser.setId(UUID.randomUUID());
    normalUser.setUsername("normalExp");
    userRepository.save(normalUser);
    saveIridiumMembership(normalUser);

    otherUser = new User();
    otherUser.setId(UUID.randomUUID());
    otherUser.setUsername("otherExp");
    userRepository.save(otherUser);
    saveIridiumMembership(otherUser);

    Manufacturer aegis = new Manufacturer();
    aegis.setName("Aegis");
    aegis.setAbbreviation("AGS");
    manufacturerRepository.save(aegis);

    fighter = new ShipType();
    fighter.setName("FighterExp");
    fighter.setManufacturer(aegis);
    fighter = shipTypeRepository.save(fighter);

    StarSystem stantonSys = new StarSystem();
    stantonSys.setName("Stanton System");
    stantonSys = starSystemRepository.save(stantonSys);

    stanton = new Location();
    stanton.setName("Stanton");
    stanton = locationRepository.save(stanton);
  }

  /**
   * Post-R9 D3 (V101): the user's home Staffel lives in org_unit_membership — anchoring the fixture
   * to IRIDIUM via a membership row is the only way for the owner resolver to find a Staffel link.
   */
  private void saveIridiumMembership(User u) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(u.getId(), Squadron.IRIDIUM_ID));
    m.setUser(u);
    m.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(m);
  }

  @Test
  void testLocationCrud_Officer_Forbidden() throws Exception {
    Location seeded = new Location();
    seeded.setName("Terra");
    seeded = locationRepository.save(seeded);
    locationRepository.flush();
    UUID seededId = seeded.getId();
    long countBefore = locationRepository.count();

    Location toCreate = new Location();
    toCreate.setName("Terra Prime");
    mockMvc
        .perform(
            post("/api/v1/locations")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(toCreate)))
        .andExpect(status().isForbidden());
    assertEquals(countBefore, locationRepository.count());

    Location updatePayload = new Location();
    updatePayload.setId(seededId);
    updatePayload.setName("Terra Prime");
    mockMvc
        .perform(
            put("/api/v1/locations/" + seededId)
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatePayload)))
        .andExpect(status().isForbidden());
    Location afterPut = locationRepository.findById(seededId).orElseThrow();
    assertEquals("Terra", afterPut.getName());

    mockMvc
        .perform(
            delete("/api/v1/locations/" + seededId)
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(officerUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
    assertTrue(locationRepository.findById(seededId).isPresent());
  }

  @Test
  void testShipWithLocation() throws Exception {
    ShipRequestDto req =
        new ShipRequestDto(
            "Located Ship", fighter.getId(), "LTI", stanton.getId(), false, null, null);

    String response =
        mockMvc
            .perform(
                post("/api/v1/hangar/ships")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(normalUser.getId().toString()))
                            .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ShipDto savedShip = objectMapper.readValue(response, ShipDto.class);
    assertNotNull(savedShip.location());
    assertEquals(stanton.getId(), savedShip.location().id());
  }

  @Test
  void testSubMission() throws Exception {
    Mission parent = new Mission();
    parent.setOwningOrgUnit(iridium);
    parent.setName("Parent Mission");
    parent = missionRepository.save(parent);

    String subJson =
        String.format("{\"name\": \"Sub Mission\", \"status\": \"PLANNED\", \"version\": 0}");

    String response =
        mockMvc
            .perform(
                post("/api/v1/missions/" + parent.getId() + "/sub-missions")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(officerUser.getId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(subJson))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    de.greluc.krt.profit.basetool.backend.model.dto.MissionDto savedSub =
        objectMapper.readValue(
            response, de.greluc.krt.profit.basetool.backend.model.dto.MissionDto.class);
    Mission fromDb = missionRepository.findById(savedSub.id()).orElseThrow();
    assertEquals(parent.getId(), fromDb.getParent().getId());
  }

  @Test
  void testMissionFinance() throws Exception {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Finance Mission");
    mission = missionRepository.save(mission);

    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(normalUser);
    participant = missionParticipantRepository.save(participant);

    de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto req =
        new de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto(
            mission.getId(),
            participant.getId(),
            "Fuel",
            FinanceType.EXPENSE,
            new BigDecimal("50.00"));

    String response =
        mockMvc
            .perform(
                post("/api/v1/finance-entries")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(normalUser.getId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto entryDto =
        objectMapper.readValue(
            response, de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto.class);
    assertEquals(new BigDecimal("50.00"), entryDto.amount());
    assertEquals(FinanceType.EXPENSE, entryDto.type());

    mockMvc
        .perform(
            delete("/api/v1/finance-entries/" + entryDto.id())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(normalUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void testMissionFinance_OtherUser_Forbidden() throws Exception {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Finance Mission 2");
    mission = missionRepository.save(mission);

    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(normalUser);
    participant = missionParticipantRepository.save(participant);

    MissionParticipant otherParticipant = new MissionParticipant();
    otherParticipant.setMission(mission);
    otherParticipant.setUser(otherUser);
    otherParticipant = missionParticipantRepository.save(otherParticipant);

    de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto req =
        new de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto(
            mission.getId(),
            participant.getId(),
            "Fuel",
            FinanceType.EXPENSE,
            new BigDecimal("50.00"));

    de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto reqOther =
        new de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto(
            mission.getId(),
            otherParticipant.getId(),
            "Snacks",
            FinanceType.EXPENSE,
            new BigDecimal("10.00"));

    String response =
        mockMvc
            .perform(
                post("/api/v1/finance-entries")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(otherUser.getId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(reqOther)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto entryDto =
        objectMapper.readValue(
            response, de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto.class);

    mockMvc
        .perform(
            delete("/api/v1/finance-entries/" + entryDto.id())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(normalUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }
}
