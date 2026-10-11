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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.operation.internal.Operation;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationRepository;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationStatus;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins how a refinery order and its mission relate on the wire and in the database (plan §7.5
 * P3-8b): the mission's finance totals count the linked order, the operation's finance views embed
 * it with its mission reference, and deleting the mission unlinks the order and moves its row
 * version. The link is read and written through SQL, so the test holds across the association
 * becoming an id.
 */
@SpringBootTest
@Transactional
class RefineryMissionLinkContractTest {

  private static final double SALES = 1000.0;

  private static final double EXPENSES = 200.0;

  private static final double OTHER_EXPENSES = 50.0;

  private static final double PROFIT = 750.0;

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private OperationRepository operationRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private MockMvc mockMvc;

  private Squadron home;

  private User admin;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString().substring(0, 6);
    home = new Squadron();
    home.setName("LinkHome-" + tag);
    home.setShorthand("L" + tag);
    home = squadronRepository.save(home);
    admin = user("link-admin");
  }

  @Test
  void theMissionFinanceTotalsCountTheLinkedOrder() throws Exception {
    Mission mission = mission(null);
    order(mission);

    JsonNode totals = read("/api/v1/missions/" + mission.getId() + "/finance-entries/summary");

    assertThat(totals.get("total").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(PROFIT));
    assertThat(totals.get("expenseSum").decimalValue())
        .isEqualByComparingTo(BigDecimal.valueOf(EXPENSES));
    assertThat(totals.get("expenseCount").asInt()).isEqualTo(1);
    assertThat(read("/api/v1/missions/" + mission.getId() + "/finance-entries/sum").decimalValue())
        .isEqualByComparingTo(BigDecimal.valueOf(PROFIT));
  }

  @Test
  void theOperationFinanceViewsEmbedTheOrderWithItsMissionReference() throws Exception {
    Operation operation = operation();
    Mission mission = mission(operation);
    UUID orderId = order(mission);
    String base = "/api/v1/operations/" + operation.getId();

    JsonNode detail = read(base + "/finances/" + mission.getId());
    assertThat(detail.get("totalSum").decimalValue())
        .isEqualByComparingTo(BigDecimal.valueOf(PROFIT));
    JsonNode embedded = detail.get("refineryOrders").get(0);
    assertThat(embedded.get("id").asString()).isEqualTo(orderId.toString());
    assertThat(embedded.get("mission").get("id").asString()).isEqualTo(mission.getId().toString());
    assertThat(embedded.get("mission").get("name").asString()).isEqualTo(mission.getName());
    assertThat(embedded.get("mission").get("status").asString()).isEqualTo("PLANNED");

    JsonNode full = read(base + "/finances");
    assertThat(full.get("totalSum").decimalValue())
        .isEqualByComparingTo(BigDecimal.valueOf(PROFIT));
    assertThat(
            full.get("missions")
                .get(0)
                .get("refineryOrders")
                .get(0)
                .get("mission")
                .get("id")
                .asString())
        .isEqualTo(mission.getId().toString());

    JsonNode summary = read(base + "/finance-summary");
    assertThat(summary.get("totalSum").decimalValue())
        .isEqualByComparingTo(BigDecimal.valueOf(PROFIT));
  }

  @Test
  void deletingTheMissionUnlinksTheOrderAndMovesItsVersion() throws Exception {
    Mission mission = mission(null);
    UUID orderId = order(mission);
    long versionBefore = version(orderId);

    mockMvc
        .perform(delete("/api/v1/missions/" + mission.getId()).with(asAdmin()))
        .andExpect(status().isNoContent());
    entityManager.flush();
    entityManager.clear();

    assertThat(refineryOrderRepository.existsById(orderId)).isTrue();
    assertThat(linkedMission(orderId)).isNull();
    assertThat(version(orderId)).isGreaterThan(versionBefore);
  }

  private JsonNode read(String path) throws Exception {
    String body =
        mockMvc
            .perform(get(path).with(asAdmin()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  private long version(UUID orderId) {
    Long version =
        jdbcTemplate.queryForObject(
            "select version from refinery_order where id = ?", Long.class, orderId);
    return version == null ? -1L : version;
  }

  private UUID linkedMission(UUID orderId) {
    return jdbcTemplate.queryForObject(
        "select mission_id from refinery_order where id = ?", UUID.class, orderId);
  }

  private UUID order(Mission mission) {
    Location location = new Location();
    location.setName("Link Depot " + UUID.randomUUID());
    location = locationRepository.save(location);
    RefineryOrder order = new RefineryOrder();
    order.setOwner(admin);
    order.setOwningOrgUnit(home);
    order.setLocation(location);
    order.setStartedAt(Instant.now());
    order.setDurationMinutes(60L);
    order.setOreSales(SALES);
    order.setExpenses(EXPENSES);
    order.setOtherExpenses(OTHER_EXPENSES);
    order = refineryOrderRepository.saveAndFlush(order);
    jdbcTemplate.update(
        "update refinery_order set mission_id = ? where id = ?", mission.getId(), order.getId());
    entityManager.clear();
    return order.getId();
  }

  private Operation operation() {
    Operation operation = new Operation();
    operation.setName("Link Op " + UUID.randomUUID());
    operation.setStatus(OperationStatus.ACTIVE);
    operation.setOwningOrgUnit(home);
    return operationRepository.saveAndFlush(operation);
  }

  private Mission mission(Operation operation) {
    Mission mission = new Mission();
    mission.setName("Link Mission " + UUID.randomUUID());
    mission.setStatus("PLANNED");
    mission.setOwningOrgUnit(home);
    if (operation != null) {
      mission.setOperationId(operation.getId());
    }
    mission = missionRepository.saveAndFlush(mission);
    if (operation != null) {
      operation.getMissions().add(mission);
    }
    return mission;
  }

  private User user(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 6));
    user = userRepository.save(user);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), home.getId()));
    membership.setUser(user);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return user;
  }

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject(admin.getId().toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("ROLE_MISSION_MANAGER"),
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }
}
