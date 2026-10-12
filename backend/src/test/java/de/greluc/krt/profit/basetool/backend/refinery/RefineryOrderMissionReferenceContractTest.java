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

package de.greluc.krt.profit.basetool.backend.refinery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins what the mission reference of a refinery-order request does to the order's link, in the six
 * cases of create and update: reference absent, reference present without an id, reference with an
 * id (plan §7.5 P3-8b). The link is read and written through SQL, so the test holds across the
 * association becoming an id.
 */
@SpringBootTest
@Transactional
class RefineryOrderMissionReferenceContractTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private SpaceStationRepository spaceStationRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository missionParticipantRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private MockMvc mockMvc;

  private User owner;

  private Location station;

  private Material ore;

  private Mission mission;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("ref-case-" + UUID.randomUUID().toString().substring(0, 6));
    owner = userRepository.save(owner);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(owner.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(owner);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);

    SpaceStation spaceStation = new SpaceStation();
    spaceStation.setName("Case Station " + UUID.randomUUID());
    spaceStation.setHasRefineryTerminal(true);
    spaceStationRepository.save(spaceStation);
    station = new Location();
    station.setName("Case Depot " + UUID.randomUUID());
    station.setSpaceStation(spaceStation);
    station = locationRepository.save(station);

    ore = new Material();
    ore.setName("Case Ore " + UUID.randomUUID());
    ore.setType(MaterialType.RAW);
    ore = materialRepository.save(ore);

    mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Case Mission " + UUID.randomUUID());
    mission = missionRepository.save(mission);
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(owner);
    missionParticipantRepository.save(participant);
  }

  @Test
  void create_withoutAReference_leavesTheOrderUnlinked() throws Exception {
    assertThat(linkAfterCreate(null)).isNull();
  }

  @Test
  void create_withAReferenceWithoutAnId_leavesTheOrderUnlinked() throws Exception {
    assertThat(linkAfterCreate(reference(null))).isNull();
  }

  @Test
  void create_withAReferenceWithAnId_linksTheMission() throws Exception {
    assertThat(linkAfterCreate(reference(mission.getId()))).isEqualTo(mission.getId());
  }

  @Test
  void update_withoutAReference_unlinksTheMission() throws Exception {
    UUID orderId = linkedOrder();

    update(orderId, null);

    assertThat(link(orderId)).isNull();
  }

  @Test
  void update_withAReferenceWithoutAnId_leavesTheLinkAsItWas() throws Exception {
    UUID orderId = linkedOrder();

    update(orderId, reference(null));

    assertThat(link(orderId)).isEqualTo(mission.getId());
  }

  @Test
  void update_withAReferenceWithoutAnId_leavesAnUnlinkedOrderUnlinked() throws Exception {
    UUID orderId = order();

    update(orderId, reference(null));

    assertThat(link(orderId)).isNull();
  }

  @Test
  void update_withAReferenceWithAnId_linksTheMission() throws Exception {
    UUID orderId = order();

    update(orderId, reference(mission.getId()));

    assertThat(link(orderId)).isEqualTo(mission.getId());
  }

  private ObjectNode reference(UUID id) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("name", "Case Mission");
    if (id != null) {
      node.put("id", id.toString());
    }
    return node;
  }

  private ObjectNode body(ObjectNode missionReference) {
    ObjectNode body = objectMapper.createObjectNode();
    body.putObject("location").put("id", station.getId().toString());
    if (missionReference != null) {
      body.set("mission", missionReference);
    }
    body.put("durationMinutes", 60);
    ObjectNode good = body.putArray("goods").addObject();
    good.putObject("inputMaterial").put("id", ore.getId().toString());
    good.put("inputQuantity", 10);
    good.putObject("outputMaterial").put("id", ore.getId().toString());
    good.put("outputQuantity", 10);
    good.put("quality", 100);
    return body;
  }

  private UUID linkAfterCreate(ObjectNode missionReference) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/refinery-orders")
                    .with(asOwner())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(missionReference).toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID orderId = UUID.fromString(objectMapper.readTree(response).get("id").asString());
    entityManager.flush();
    return link(orderId);
  }

  private void update(UUID orderId, ObjectNode missionReference) throws Exception {
    mockMvc
        .perform(
            put("/api/v1/refinery-orders/" + orderId)
                .with(asOwner())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(missionReference).toString()))
        .andExpect(status().isOk());
    entityManager.flush();
  }

  private UUID order() {
    RefineryOrder order = new RefineryOrder();
    order.setOwner(owner);
    order.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
    order.setLocation(station);
    order.setStartedAt(Instant.now());
    order.setDurationMinutes(60L);
    order = refineryOrderRepository.saveAndFlush(order);
    entityManager.clear();
    return order.getId();
  }

  private UUID linkedOrder() {
    UUID orderId = order();
    jdbcTemplate.update(
        "update refinery_order set mission_id = ? where id = ?", mission.getId(), orderId);
    entityManager.clear();
    return orderId;
  }

  private UUID link(UUID orderId) {
    entityManager.flush();
    return jdbcTemplate.queryForObject(
        "select mission_id from refinery_order where id = ?", UUID.class, orderId);
  }

  private RequestPostProcessor asOwner() {
    return jwt()
        .jwt(builder -> builder.subject(owner.getId().toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("MISSION_READ"),
            new SimpleGrantedAuthority("REFINERY_READ"),
            new SimpleGrantedAuthority("REFINERY_WRITE"));
  }
}
