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

package de.greluc.krt.profit.basetool.backend.refinery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies against real Postgres that the refinery's order reads issue the same number of SQL
 * statements whether a member's orders are linked to a few or to many missions (REQ-DATA-003, plan
 * §7.5 P3-8b), and that each order still carries its mission's reference: the missions come in per
 * page, never per order.
 */
@SpringBootTest
@Transactional
class RefineryOrderReadNoNPlusOneTest {

  private static final String ORDERS = "/api/v1/refinery-orders";

  private static final int FEW = 3;

  private static final int MANY = 25;

  /** Ceiling per read; a per-order mission lookup would exceed it at {@value #MANY} orders. */
  private static final int STATEMENT_BOUND = 15;

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private RefiningMethodRepository refiningMethodRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository missionParticipantRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private ObjectMapper objectMapper;

  private MockMvc mockMvc;

  private Squadron iridium;

  private Location location;

  private RefiningMethod method;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    String tag = UUID.randomUUID().toString().substring(0, 8);
    location = new Location();
    location.setName("Refinery Depot " + tag);
    location = locationRepository.save(location);
    method = new RefiningMethod();
    method.setName("Method " + tag);
    method = refiningMethodRepository.save(method);
  }

  @Test
  void theOrderReadsStayStatementBoundedWhateverTheMissions() throws Exception {
    Seed few = seed(FEW);
    Seed many = seed(MANY);
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    long fewList = listStatements(stats, few);
    long manyList = listStatements(stats, many);
    assertThat(manyList)
        .as("my-orders statements for " + MANY + " missions against " + FEW + " (N+1)")
        .isEqualTo(fewList)
        .isLessThanOrEqualTo(STATEMENT_BOUND);

    UUID orderId = many.orders().getFirst();
    clear(stats);
    JsonNode detail = read(get(ORDERS + "/" + orderId), many.owner());
    assertThat(detail.get("mission").get("id").asString())
        .isEqualTo(many.missions().getFirst().toString());
    assertThat(detail.get("mission").get("name").asString()).startsWith("Op ");
    assertThat(stats.getPrepareStatementCount())
        .as("detail statements")
        .isLessThanOrEqualTo(STATEMENT_BOUND);
  }

  private long listStatements(Statistics stats, Seed seed) throws Exception {
    clear(stats);
    JsonNode page = read(get(ORDERS + "/my-orders").param("size", "50"), seed.owner());
    long statements = stats.getPrepareStatementCount();
    JsonNode content = page.get("content");
    assertThat(content.size()).isEqualTo(seed.orders().size());
    for (JsonNode row : content) {
      assertThat(row.get("mission").get("id").asString()).isNotBlank();
      assertThat(row.get("mission").get("name").asString()).startsWith("Op ");
    }
    return statements;
  }

  private void clear(Statistics stats) {
    entityManager.flush();
    entityManager.clear();
    stats.clear();
  }

  private JsonNode read(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, User user)
      throws Exception {
    String body =
        mockMvc
            .perform(request.with(as(user)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  /**
   * One member with one order per mission.
   *
   * @param owner the member
   * @param missions the missions, one per order
   * @param orders the orders, in mission order
   */
  private record Seed(User owner, List<UUID> missions, List<UUID> orders) {}

  private Seed seed(int count) {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("refinery-" + tag);
    owner = userRepository.save(owner);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(owner.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(owner);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    List<UUID> missions = new ArrayList<>();
    List<UUID> orders = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Mission mission = new Mission();
      mission.setOwningOrgUnit(iridium);
      mission.setName("Op " + tag + " " + i);
      mission = missionRepository.save(mission);
      MissionParticipant participant = new MissionParticipant();
      participant.setMission(mission);
      participant.setUser(owner);
      missionParticipantRepository.save(participant);
      RefineryOrder order = new RefineryOrder();
      order.setOwner(owner);
      order.setOwningOrgUnit(iridium);
      order.setLocation(location);
      order.setRefiningMethod(method);
      order.setStartedAt(Instant.now().minusSeconds(i));
      order.setDurationMinutes(60L);
      order.setExpenses(100.0);
      order.setMissionId(mission.getId());
      order = refineryOrderRepository.save(order);
      missions.add(mission.getId());
      orders.add(order.getId());
    }
    return new Seed(owner, missions, orders);
  }

  private static RequestPostProcessor as(User user) {
    return jwt()
        .jwt(builder -> builder.subject(user.getId().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }
}
