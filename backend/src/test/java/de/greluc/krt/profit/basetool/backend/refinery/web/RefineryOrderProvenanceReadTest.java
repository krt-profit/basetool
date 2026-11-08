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

import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderProvenance;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
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
 * Against real Postgres, the order reads carry the provenance the web shows (REQ-REFINERY-027,
 * REQ-REFINERY-028): the creating client, the last change and the pickup marker survive the round
 * trip through the new columns, and naming the clients of a page costs no statement per order
 * (REQ-DATA-003).
 */
@SpringBootTest
@Transactional
class RefineryOrderProvenanceReadTest {

  private static final String ORDERS = "/api/v1/refinery-orders";

  /** A client id no registry row carries, so the reads show the id itself. */
  private static final String CLIENT = "provenance-test-client";

  private static final Instant CREATED = Instant.parse("2026-11-01T10:00:00Z");

  private static final Instant PICKED_UP = Instant.parse("2026-11-01T12:00:00Z");

  @Autowired private WebApplicationContext context;
  @Autowired private CityRepository cityRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private ObjectMapper objectMapper;

  private MockMvc mockMvc;
  private Squadron iridium;
  private Location location;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    location = new Location();
    location.setName("Provenance Depot " + UUID.randomUUID().toString().substring(0, 8));
    location = locationRepository.save(TestPlaces.onOwnCity(location, cityRepository));
  }

  /** The list and the detail carry who created, changed and picked up each order. */
  @Test
  void theReadsCarryTheProvenance() throws Exception {
    User owner = member();
    UUID web = save(owner, RefineryOrderProvenance.createdInWeb(CREATED), null);
    UUID created = save(owner, RefineryOrderProvenance.createdByClient(CLIENT, CREATED), null);
    RefineryOrderProvenance pickup = RefineryOrderProvenance.createdByClient(CLIENT, CREATED);
    pickup.recordPickup(CLIENT, PICKED_UP);
    UUID pickedUp = save(owner, pickup, RefineryOrderStatus.COMPLETED);
    flushAndClear();

    Map<String, JsonNode> rows = new HashMap<>();
    for (JsonNode row :
        read(get(ORDERS + "/my-orders").param("size", "50"), owner).get("content")) {
      rows.put(row.get("id").asString(), row.get("provenance"));
    }

    assertThat(rows.get(web.toString()).get("createdByClient").isNull()).isTrue();
    assertThat(rows.get(web.toString()).get("lastChangedByClient").isNull()).isTrue();
    assertThat(rows.get(created.toString()).get("createdByClient").asString()).isEqualTo(CLIENT);
    JsonNode picked = rows.get(pickedUp.toString());
    assertThat(picked.get("pickedUpByClient").asString()).isEqualTo(CLIENT);
    assertThat(Instant.parse(picked.get("pickedUpAt").asString())).isEqualTo(PICKED_UP);
    assertThat(picked.get("storedAt").isNull()).isTrue();

    JsonNode detail = read(get(ORDERS + "/" + pickedUp), owner).get("provenance");
    assertThat(detail.get("createdByClient").asString()).isEqualTo(CLIENT);
    assertThat(Instant.parse(detail.get("lastChangedAt").asString())).isEqualTo(PICKED_UP);
  }

  /** A page of client orders costs as many statements as a page of web orders. */
  @Test
  void namingTheClientsCostsNoStatementPerOrder() throws Exception {
    User webOwner = member();
    User clientOwner = member();
    for (int i = 0; i < 20; i++) {
      save(webOwner, RefineryOrderProvenance.createdInWeb(CREATED), null);
      save(clientOwner, RefineryOrderProvenance.createdByClient(CLIENT + i % 2, CREATED), null);
    }
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    long webStatements = listStatements(stats, webOwner);
    long clientStatements = listStatements(stats, clientOwner);

    assertThat(clientStatements).isLessThanOrEqualTo(webStatements + 1);
  }

  private long listStatements(Statistics stats, User owner) throws Exception {
    flushAndClear();
    stats.clear();
    read(get(ORDERS + "/my-orders").param("size", "50"), owner);
    return stats.getPrepareStatementCount();
  }

  private UUID save(
      User owner, RefineryOrderProvenance provenance, RefineryOrderStatus statusOrNull) {
    RefineryOrder order = new RefineryOrder();
    order.setOwner(owner);
    order.setOwningOrgUnit(iridium);
    order.setLocation(location);
    order.setStartedAt(CREATED.minus(1, ChronoUnit.HOURS));
    order.setDurationMinutes(30L);
    if (statusOrNull != null) {
      order.setStatus(statusOrNull);
    }
    order.setProvenance(provenance);
    return refineryOrderRepository.save(order).getId();
  }

  private User member() {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("provenance-" + UUID.randomUUID().toString().substring(0, 8));
    owner = userRepository.save(owner);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(owner.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(owner);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return owner;
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
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

  private static RequestPostProcessor as(User user) {
    return jwt()
        .jwt(builder -> builder.subject(user.getId().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }
}
