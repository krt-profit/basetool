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

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
class HangarIntegrationTest {

  @Autowired private SquadronRepository squadronRepository;

  private Squadron iridium;

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private ShipRepository shipRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private MissionUnitRepository missionUnitRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @Autowired private SpecialCommandRepository specialCommandRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User user1;
  private User user2;
  private User adminUser;
  private ShipType fighter;

  @BeforeEach
  void setUp() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    user1 = new User();
    user1.setId(UUID.randomUUID());
    user1.setUsername("user1");
    userRepository.save(user1);
    saveIridiumMembership(user1);

    user2 = new User();
    user2.setId(UUID.randomUUID());
    user2.setUsername("user2");
    userRepository.save(user2);
    saveIridiumMembership(user2);

    adminUser = new User();
    adminUser.setId(UUID.randomUUID());
    adminUser.setUsername("admin");
    userRepository.save(adminUser);
    saveIridiumMembership(adminUser);

    fighter = new ShipType();
    fighter.setName("Fighter");
    fighter = shipTypeRepository.save(fighter);
  }

  /** Post-R9 D3 (V101): home Staffel via membership row. */
  private void saveIridiumMembership(User u) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(u.getId(), Squadron.IRIDIUM_ID));
    m.setUser(u);
    m.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(m);
  }

  @Test
  void testUserManageOwnHangar() throws Exception {
    ShipRequestDto shipReq =
        new ShipRequestDto("My Fighter", fighter.getId(), "LTI", null, true, null, null);

    String response =
        mockMvc
            .perform(
                post("/api/v1/hangar/ships")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(user1.getId().toString()))
                            .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(shipReq)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ShipDto savedShip = objectMapper.readValue(response, ShipDto.class);
    assertNotNull(savedShip.id());
    assertEquals("My Fighter", savedShip.name());

    ShipRequestDto updateReq =
        new ShipRequestDto(
            "Updated Fighter", fighter.getId(), "LTI", null, true, savedShip.version(), null);
    mockMvc
        .perform(
            put("/api/v1/hangar/ships/" + savedShip.id())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateReq)))
        .andExpect(status().isOk());

    Ship updated = shipRepository.findById(savedShip.id()).orElseThrow();
    assertEquals("Updated Fighter", updated.getName());

    mockMvc
        .perform(
            delete("/api/v1/hangar/ships/" + savedShip.id())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"))))
        .andExpect(status().isOk());

    assertTrue(shipRepository.findById(savedShip.id()).isEmpty());
  }

  @Test
  void testUserCannotManageOtherHangar() throws Exception {
    Ship ship = new Ship();
    ship.setOwningOrgUnit(iridium);
    ship.setName("User1 Ship");
    ship.setShipType(fighter);
    ship.setOwner(user1);
    ship.setInsurance("LTI");
    ship = shipRepository.save(ship);

    ShipRequestDto upReq =
        new ShipRequestDto("Hacked", fighter.getId(), "LTI", null, false, 0L, null);
    mockMvc
        .perform(
            put("/api/v1/hangar/ships/" + ship.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user2.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(upReq)))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            delete("/api/v1/hangar/ships/" + ship.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user2.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void testAdminManageUserHangar() throws Exception {
    ShipRequestDto req =
        new ShipRequestDto("Admin Gift", fighter.getId(), "LTI", null, false, null, null);

    String response =
        mockMvc
            .perform(
                post("/api/v1/hangar/users/" + user1.getId() + "/ships")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(adminUser.getId().toString()))
                            .authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("ROLE_MANAGE"),
                                new SimpleGrantedAuthority("USER_MANAGE"),
                                new SimpleGrantedAuthority("MISSION_MANAGE"),
                                new SimpleGrantedAuthority("HANGAR_MANAGE"),
                                new SimpleGrantedAuthority("REFINERY_MANAGE")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ShipDto savedShip = objectMapper.readValue(response, ShipDto.class);
    assertEquals(user1.getId(), savedShip.owner().id());

    ShipRequestDto upReq =
        new ShipRequestDto(
            "Admin Updated", fighter.getId(), "LTI", null, false, savedShip.version(), null);
    mockMvc
        .perform(
            put("/api/v1/hangar/users/" + user1.getId() + "/ships/" + savedShip.id())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_MANAGE"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(upReq)))
        .andExpect(status().isOk());

    Ship updated = shipRepository.findById(savedShip.id()).orElseThrow();
    assertEquals("Admin Updated", updated.getName());

    mockMvc
        .perform(
            delete("/api/v1/hangar/users/" + user1.getId() + "/ships/" + savedShip.id())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_MANAGE"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"))))
        .andExpect(status().isOk());

    assertTrue(shipRepository.findById(savedShip.id()).isEmpty());
  }

  @Test
  void testAdminResetAllFittedStatus() throws Exception {
    Ship ship1 = new Ship();
    ship1.setOwningOrgUnit(iridium);
    ship1.setName("Fitted Ship 1");
    ship1.setShipType(fighter);
    ship1.setOwner(user1);
    ship1.setInsurance("LTI");
    ship1.setFitted(true);
    shipRepository.save(ship1);

    Ship ship2 = new Ship();

    ship2.setOwningOrgUnit(iridium);
    ship2.setName("Fitted Ship 2");
    ship2.setShipType(fighter);
    ship2.setOwner(user2);
    ship2.setInsurance("LTI");
    ship2.setFitted(true);
    shipRepository.save(ship2);

    mockMvc
        .perform(
            post("/api/v1/hangar/ships/reset-fitted")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
        .andExpect(status().isOk());

    Ship savedShip1 = shipRepository.findById(ship1.getId()).orElseThrow();
    Ship savedShip2 = shipRepository.findById(ship2.getId()).orElseThrow();

    assertFalse(savedShip1.isFitted());
    assertFalse(savedShip2.isFitted());
  }

  @Test
  void testUserCannotResetAllFittedStatus() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/hangar/ships/reset-fitted")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void testSquadronOverview() throws Exception {
    Ship ship1 = new Ship();
    ship1.setOwningOrgUnit(iridium);
    ship1.setName("Fitted Ship 1");
    ship1.setShipType(fighter);
    ship1.setOwner(user1);
    ship1.setInsurance("LTI");
    ship1.setFitted(true);
    shipRepository.save(ship1);

    Ship ship2 = new Ship();

    ship2.setOwningOrgUnit(iridium);
    ship2.setName("Unfitted Ship 2");
    ship2.setShipType(fighter);
    ship2.setOwner(user2);
    ship2.setInsurance("LTI");
    ship2.setFitted(false);
    shipRepository.save(ship2);

    String response =
        mockMvc
            .perform(
                get("/api/v1/hangar/squadron-overview")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(user1.getId().toString()))
                            .authorities(new SimpleGrantedAuthority("HANGAR_READ"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(response.contains("\"count\":2"));
    assertTrue(response.contains("\"fittedCount\":1"));
    assertFalse(response.contains("\"details\":[{\"ownerName\""));
  }

  @Test
  void testSquadronOverviewAsAdmin() throws Exception {
    Ship ship1 = new Ship();
    ship1.setOwningOrgUnit(iridium);
    ship1.setName("Admin Fitted Ship");
    ship1.setShipType(fighter);
    ship1.setOwner(user1);
    ship1.setInsurance("LTI");
    ship1.setFitted(true);
    shipRepository.save(ship1);

    String response =
        mockMvc
            .perform(
                get("/api/v1/hangar/squadron-overview")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(adminUser.getId().toString()))
                            .authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("HANGAR_READ"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(response.contains("\"count\":1"));
    assertTrue(response.contains("\"fittedCount\":1"));
    assertTrue(response.contains("\"details\":[{\"ownerName\":\"user1\""));
  }

  @Test
  void testSquadronOverviewPaginatesAndFiltersAcrossAllTypes() throws Exception {
    ShipType cutlass = new ShipType();
    cutlass.setName("Cutlass Black");
    cutlass = shipTypeRepository.save(cutlass);
    ShipType cutter = new ShipType();
    cutter.setName("Cutter");
    cutter = shipTypeRepository.save(cutter);

    for (ShipType type : java.util.List.of(fighter, cutlass, cutter)) {
      Ship ship = new Ship();
      ship.setOwningOrgUnit(iridium);
      ship.setName("Ship " + type.getName());
      ship.setShipType(type);
      ship.setOwner(user1);
      ship.setInsurance("LTI");
      ship.setFitted(false);
      shipRepository.save(ship);
    }
    Ship second = new Ship();
    second.setOwningOrgUnit(iridium);
    second.setName("Second Cutlass");
    second.setShipType(cutlass);
    second.setOwner(user2);
    second.setInsurance("LTI");
    second.setFitted(false);
    shipRepository.save(second);

    String paged =
        mockMvc
            .perform(
                get("/api/v1/hangar/squadron-overview")
                    .param("page", "0")
                    .param("size", "2")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(user1.getId().toString()))
                            .authorities(new SimpleGrantedAuthority("HANGAR_READ"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(paged.contains("\"totalElements\":3"));
    assertTrue(paged.contains("\"totalPages\":2"));

    String filtered =
        mockMvc
            .perform(
                get("/api/v1/hangar/squadron-overview")
                    .param("search", "cut")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(user1.getId().toString()))
                            .authorities(new SimpleGrantedAuthority("HANGAR_READ"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(filtered.contains("\"totalElements\":2"));
    assertTrue(filtered.contains("Cutlass Black"));
    assertTrue(filtered.contains("Cutter"));
    assertFalse(filtered.contains("Fighter"));
  }

  /**
   * Regression for the hangar squadron-overview scope leak: an admin pinned to a squadron must NOT
   * see the owner-detail rows of a ship owned by a different OrgUnit (here a Spezialkommando the
   * admin's pinned squadron has nothing to do with), even when that foreign ship shares its ship
   * type with the pinned squadron. Before the fix the aggregated counts were squadron-scoped but
   * the per-owner breakdown was loaded by ship type alone, so a member who belonged only to an SK
   * leaked into the pinned squadron's overview.
   */
  @Test
  void testSquadronOverviewAsPinnedAdmin_doesNotLeakForeignSkShipsOfSharedType() throws Exception {
    Ship iridiumShip = new Ship();
    iridiumShip.setOwningOrgUnit(iridium);
    iridiumShip.setName("Iridium Fighter");
    iridiumShip.setShipType(fighter);
    iridiumShip.setOwner(user1);
    iridiumShip.setInsurance("LTI");
    iridiumShip.setFitted(true);
    shipRepository.save(iridiumShip);

    SpecialCommand sk = new SpecialCommand();
    sk.setName("Leak Test SK");
    sk.setShorthand("LTSK");
    sk = specialCommandRepository.save(sk);

    User skUser = new User();
    skUser.setId(UUID.randomUUID());
    skUser.setUsername("skuser");
    userRepository.save(skUser);
    OrgUnitMembership skMembership = new OrgUnitMembership();
    skMembership.setId(new OrgUnitMembershipId(skUser.getId(), sk.getId()));
    skMembership.setUser(skUser);
    skMembership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(skMembership);

    Ship skShip = new Ship();
    skShip.setOwningOrgUnit(sk);
    skShip.setName("SK Fighter");
    skShip.setShipType(fighter);
    skShip.setOwner(skUser);
    skShip.setInsurance("LTI");
    skShip.setFitted(false);
    shipRepository.save(skShip);

    String response =
        mockMvc
            .perform(
                get("/api/v1/hangar/squadron-overview")
                    .header("X-Active-Org-Unit-Id", Squadron.IRIDIUM_ID.toString())
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(adminUser.getId().toString()))
                            .authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("HANGAR_READ"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(response.contains("\"count\":1"), "pinned admin must count only Iridium's ship");
    assertTrue(
        response.contains("\"ownerName\":\"user1\""),
        "the pinned squadron's own ship must still appear in the breakdown");
    assertFalse(
        response.contains("skuser"),
        "an SK-only member's ship must not leak into the pinned squadron's hangar overview");
  }

  @Test
  void testDeleteShipInMission() throws Exception {
    Ship ship = new Ship();
    ship.setOwningOrgUnit(iridium);
    ship.setName("Mission Ship");
    ship.setShipType(fighter);
    ship.setOwner(user1);
    ship.setInsurance("LTI");
    ship = shipRepository.save(ship);

    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Test Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    MissionUnit unit = new MissionUnit();
    unit.setMission(mission);
    unit.setName("Alpha 1");
    unit.setShip(ship);
    missionUnitRepository.save(unit);

    mockMvc
        .perform(
            delete("/api/v1/hangar/ships/" + ship.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"))))
        .andExpect(status().isOk());

    assertTrue(shipRepository.findById(ship.getId()).isEmpty());

    MissionUnit updatedUnit = missionUnitRepository.findById(unit.getId()).orElseThrow();
    assertNull(updatedUnit.getShip());
  }

  @Test
  void testDeleteAllShips_ReturnsNoContent() throws Exception {
    Ship ship1 = new Ship();
    ship1.setOwningOrgUnit(iridium);
    ship1.setShipType(fighter);
    ship1.setOwner(user1);
    ship1.setInsurance("LTI");
    shipRepository.save(ship1);

    Ship ship2 = new Ship();

    ship2.setOwningOrgUnit(iridium);
    ship2.setShipType(fighter);
    ship2.setOwner(user1);
    ship2.setInsurance("0");
    shipRepository.save(ship2);

    mockMvc
        .perform(
            delete("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"))))
        .andExpect(status().isNoContent());

    assertTrue(shipRepository.findByOwnerId(user1.getId()).isEmpty());
  }

  @Test
  void testDeleteAllShips_Unauthenticated_Returns401() throws Exception {
    mockMvc.perform(delete("/api/v1/hangar/ships")).andExpect(status().isUnauthorized());
  }

  @Test
  void testDeleteAllShips_NoHangarWriteAuthority_Returns403() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_READ"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void testDeleteAllShips_WithLinkedMissionUnit_UnlinksBeforeDelete() throws Exception {
    Ship ship = new Ship();
    ship.setOwningOrgUnit(iridium);
    ship.setShipType(fighter);
    ship.setOwner(user1);
    ship.setInsurance("LTI");
    ship = shipRepository.save(ship);

    Mission mission = new Mission();

    mission.setOwningOrgUnit(iridium);
    mission.setName("Test Mission for Delete All");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    MissionUnit unit = new MissionUnit();
    unit.setMission(mission);
    unit.setName("Bravo 1");
    unit.setShip(ship);
    missionUnitRepository.save(unit);

    mockMvc
        .perform(
            delete("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"))))
        .andExpect(status().isNoContent());

    assertTrue(shipRepository.findById(ship.getId()).isEmpty());
    MissionUnit updatedUnit = missionUnitRepository.findById(unit.getId()).orElseThrow();
    assertNull(updatedUnit.getShip());

    Ship user2Ship = new Ship();
    user2Ship.setOwningOrgUnit(iridium);
    user2Ship.setShipType(fighter);
    user2Ship.setOwner(user2);
    user2Ship.setInsurance("LTI");
    shipRepository.save(user2Ship);
    assertFalse(shipRepository.findByOwnerId(user2.getId()).isEmpty());
  }
}
