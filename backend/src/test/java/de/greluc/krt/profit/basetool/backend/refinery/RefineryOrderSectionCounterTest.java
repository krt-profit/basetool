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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryGood;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two section counters of a refinery order through the order endpoints (REQ-REFINERY-029): each
 * write names the parts it writes, is refused only when a section it writes changed since its
 * render, and raises the counter of each section it wrote. Every request starts from a cleared
 * persistence context, as a separate request would.
 */
@SpringBootTest
@Transactional
class RefineryOrderSectionCounterTest {

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

  private UUID orderId;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("ref-sect-" + UUID.randomUUID().toString().substring(0, 6));
    owner = userRepository.save(owner);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(owner.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(owner);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);

    SpaceStation spaceStation = new SpaceStation();
    spaceStation.setName("Section Station " + UUID.randomUUID());
    spaceStation.setHasRefineryTerminal(true);
    spaceStationRepository.save(spaceStation);
    station = new Location();
    station.setName("Section Depot " + UUID.randomUUID());
    station.setSpaceStation(spaceStation);
    station = locationRepository.save(station);

    ore = new Material();
    ore.setName("Section Ore " + UUID.randomUUID());
    ore.setType(MaterialType.RAW);
    ore = materialRepository.save(ore);

    mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Section Mission " + UUID.randomUUID());
    mission = missionRepository.save(mission);
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(owner);
    missionParticipantRepository.save(participant);

    RefineryOrder order = new RefineryOrder();
    order.setOwner(owner);
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setStartedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    order.setDurationMinutes(60L);
    RefineryGood good = new RefineryGood();
    good.setInputMaterial(ore);
    good.setInputQuantity(10);
    good.setOutputMaterial(ore);
    good.setOutputQuantity(10);
    good.setQuality(100);
    good.setRefineryOrder(order);
    order.getGoods().add(good);
    orderId = refineryOrderRepository.saveAndFlush(order).getId();
    entityManager.clear();
  }

  @Test
  void aNewOrderStartsWithBothCountersAtZeroAndTheReadCarriesThem() throws Exception {
    mockMvc
        .perform(get("/api/v1/refinery-orders/" + orderId).with(asOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDataVersion").value(0))
        .andExpect(jsonPath("$.financesVersion").value(0));
  }

  @Test
  void aFinanceEditAndATimeEditFromTheSameRenderBothApply() throws Exception {
    ObjectNode financeEdit = body(120L, 0d);
    financeEdit.put("expenses", 500d);
    parts(financeEdit, "FINANCES");
    financeEdit.put("financesVersion", 0);
    send(financeEdit)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.financesVersion").value(1))
        .andExpect(jsonPath("$.gameDataVersion").value(0));

    ObjectNode timeEdit = body(120L, 0d);
    parts(timeEdit, "GAME_DATA");
    timeEdit.put("gameDataVersion", 0);
    send(timeEdit)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDataVersion").value(1))
        .andExpect(jsonPath("$.financesVersion").value(1));

    Map<String, Object> row = row();
    assertThat(row.get("expenses")).isEqualTo(500d);
    assertThat(row.get("duration_minutes")).isEqualTo(120L);
  }

  @Test
  void twoGoodsEditsFromTheSameRenderConflict() throws Exception {
    ObjectNode first = body(60L, 0d);
    first.withArray("goods").get(0).asObject().put("quality", 300);
    parts(first, "GAME_DATA");
    first.put("gameDataVersion", 0);
    send(first).andExpect(status().isOk());

    ObjectNode second = body(60L, 0d);
    second.withArray("goods").get(0).asObject().put("quality", 400);
    parts(second, "GAME_DATA");
    second.put("gameDataVersion", 0);
    send(second).andExpect(status().isConflict());
  }

  @Test
  void aSectionWriteWithoutItsCounterIsRefused() throws Exception {
    ObjectNode financeEdit = body(60L, 0d);
    financeEdit.put("expenses", 300d);
    parts(financeEdit, "FINANCES");
    send(financeEdit).andExpect(status().isConflict());
  }

  @Test
  void aStaleCounterOfASectionTheWriteDoesNotTouchIsNotChecked() throws Exception {
    ObjectNode timeEdit = body(90L, 0d);
    parts(timeEdit, "GAME_DATA");
    timeEdit.put("gameDataVersion", 0);
    send(timeEdit).andExpect(status().isOk());

    ObjectNode financeEdit = body(60L, 0d);
    financeEdit.put("oreSales", 900d);
    parts(financeEdit, "FINANCES");
    financeEdit.put("gameDataVersion", 0);
    financeEdit.put("financesVersion", 0);
    send(financeEdit).andExpect(status().isOk());

    Map<String, Object> row = row();
    assertThat(row.get("duration_minutes")).isEqualTo(90L);
    assertThat(row.get("ore_sales")).isEqualTo(900d);
  }

  @Test
  void aMissionLinkChangeMovesNeitherCounter() throws Exception {
    ObjectNode link = body(60L, 0d);
    link.putObject("mission").put("id", mission.getId().toString());
    parts(link, "MISSION_LINK");
    send(link)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDataVersion").value(0))
        .andExpect(jsonPath("$.financesVersion").value(0));

    assertThat(row().get("mission_id")).isEqualTo(mission.getId());
  }

  @Test
  void aWriteWithoutPartsReplacesBothSectionsAndRaisesBothCounters() throws Exception {
    ObjectNode legacy = body(45L, 0d);
    legacy.put("expenses", 70d);
    send(legacy)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameDataVersion").value(1))
        .andExpect(jsonPath("$.financesVersion").value(1));

    Map<String, Object> row = row();
    assertThat(row.get("duration_minutes")).isEqualTo(45L);
    assertThat(row.get("expenses")).isEqualTo(70d);
  }

  @Test
  void aWriteWithoutPartsStillChecksTheOrderVersion() throws Exception {
    ObjectNode legacy = body(45L, 0d);
    legacy.put("version", 99);
    send(legacy).andExpect(status().isConflict());
  }

  @Test
  void cancelRaisesTheGameDataCounterSoAStaleGameDataEditConflicts() throws Exception {
    mockMvc
        .perform(delete("/api/v1/refinery-orders/" + orderId).with(asOwner()))
        .andExpect(status().isOk());
    entityManager.flush();
    entityManager.clear();

    assertThat(row().get("game_data_version")).isEqualTo(1L);
    assertThat(row().get("finances_version")).isEqualTo(0L);

    ObjectNode stale = body(60L, 0d);
    parts(stale, "GAME_DATA");
    stale.put("gameDataVersion", 0);
    send(stale).andExpect(status().isConflict());
  }

  @Test
  void storeRaisesTheGameDataCounter() throws Exception {
    ObjectNode store = objectMapper.createObjectNode();
    ObjectNode item = store.putArray("items").addObject();
    item.put("materialId", ore.getId().toString());
    item.put("locationId", station.getId().toString());
    item.put("quality", 100);
    item.put("amount", 10);
    mockMvc
        .perform(
            post("/api/v1/refinery-orders/" + orderId + "/store")
                .with(asOwner())
                .contentType(MediaType.APPLICATION_JSON)
                .content(store.toString()))
        .andExpect(status().isOk());
    entityManager.flush();
    entityManager.clear();

    assertThat(row().get("game_data_version")).isEqualTo(1L);
    assertThat(row().get("finances_version")).isEqualTo(0L);
  }

  private ObjectNode body(long durationMinutes, double expenses) {
    ObjectNode body = objectMapper.createObjectNode();
    body.putObject("location").put("id", station.getId().toString());
    body.put("durationMinutes", durationMinutes);
    body.put("expenses", expenses);
    ArrayNode goods = body.putArray("goods");
    ObjectNode good = goods.addObject();
    good.putObject("inputMaterial").put("id", ore.getId().toString());
    good.put("inputQuantity", 10);
    good.putObject("outputMaterial").put("id", ore.getId().toString());
    good.put("outputQuantity", 10);
    good.put("quality", 100);
    return body;
  }

  private static void parts(ObjectNode body, String... parts) {
    ArrayNode array = body.putArray("parts");
    for (String part : parts) {
      array.add(part);
    }
  }

  private ResultActions send(ObjectNode body) throws Exception {
    ResultActions result =
        mockMvc.perform(
            put("/api/v1/refinery-orders/" + orderId)
                .with(asOwner())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    entityManager.flush();
    entityManager.clear();
    return result;
  }

  private Map<String, Object> row() {
    entityManager.flush();
    return jdbcTemplate.queryForMap(
        "select expenses, ore_sales, duration_minutes, mission_id, game_data_version,"
            + " finances_version from refinery_order where id = ?",
        orderId);
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
