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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the refinery module's externally visible contract: each order command records its audit
 * event in the command's transaction (REQ-AUDIT-001), and every per-order and on-behalf entry point
 * refuses a caller outside the order's org unit (REQ-ORG-011).
 */
@SpringBootTest
@Transactional
class RefineryModuleContractTest {

  private static final String ORDERS = "/api/v1/refinery-orders";

  private static final String MEMBER = "ROLE_KRT_MEMBER";

  private static final String LOGISTICIAN = "ROLE_LOGISTICIAN";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private MockMvc mockMvc;

  private User owner;

  private User outsider;

  private Location refinery;

  private Material ore;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString().substring(0, 6);
    owner = member("ref-owner", squadron("RefHome-" + tag, "R" + tag));
    outsider = member("ref-outsider", squadron("RefForeign-" + tag, "Q" + tag));

    City city = new City();
    city.setName("Refinery City " + tag);
    city.setHasRefineryTerminal(true);
    city = cityRepository.save(city);
    refinery = new Location();
    refinery.setName("Refinery " + tag);
    refinery.setCity(city);
    refinery = locationRepository.save(refinery);

    ore = new Material();
    ore.setName("Contract Ore " + tag);
    ore.setType(MaterialType.RAW);
    ore.setQuantityType(QuantityType.SCU);
    ore = materialRepository.save(ore);
  }

  @Test
  void createUpdateAndCancelEachRecordTheirAuditEvent() throws Exception {
    JsonNode created = create();
    UUID id = UUID.fromString(created.get("id").asString());
    assertThat(events(id)).containsExactly("REFINERY_ORDER_CREATED");

    mockMvc
        .perform(
            put(ORDERS + "/" + id)
                .with(as(owner, MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(created.get("version").asLong(), 120)))
        .andExpect(status().isOk());
    assertThat(events(id)).containsExactly("REFINERY_ORDER_CREATED", "REFINERY_ORDER_UPDATED");

    mockMvc.perform(delete(ORDERS + "/" + id).with(as(owner, MEMBER))).andExpect(status().isOk());
    assertThat(events(id))
        .containsExactly(
            "REFINERY_ORDER_CREATED", "REFINERY_ORDER_UPDATED", "REFINERY_ORDER_CANCELED");
  }

  @Test
  void aForeignMemberIsRefusedOnEveryPerOrderEntryPoint() throws Exception {
    JsonNode created = create();
    String order = ORDERS + "/" + created.get("id").asString();
    RequestPostProcessor caller = as(outsider, MEMBER);

    mockMvc.perform(get(order).with(caller)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(order)
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(created.get("version").asLong(), 90)))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(order).with(caller)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(order + "/store")
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(storeBody()))
        .andExpect(status().isForbidden());
  }

  @Test
  void aForeignLogisticianIsRefusedOnEveryOnBehalfEntryPoint() throws Exception {
    JsonNode created = create();
    String id = created.get("id").asString();
    String user = ORDERS + "/users/" + owner.getId();
    RequestPostProcessor caller = as(outsider, MEMBER, LOGISTICIAN);

    mockMvc.perform(get(user).with(caller)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(user).with(caller).contentType(MediaType.APPLICATION_JSON).content(body(null, 60)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(user + "/" + id)
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(created.get("version").asLong(), 90)))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(user + "/" + id).with(caller)).andExpect(status().isForbidden());
  }

  @Test
  void theOwnerReadsTheOrder() throws Exception {
    JsonNode created = create();
    mockMvc
        .perform(get(ORDERS + "/" + created.get("id").asString()).with(as(owner, MEMBER)))
        .andExpect(status().isOk());
  }

  private JsonNode create() throws Exception {
    String response =
        mockMvc
            .perform(
                post(ORDERS)
                    .with(as(owner, MEMBER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(null, 60)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    entityManager.flush();
    return objectMapper.readTree(response);
  }

  private String body(Long version, long durationMinutes) {
    return """
    {"location":{"id":"%s","name":"%s"},"durationMinutes":%d,%s
     "goods":[{"inputMaterial":{"id":"%s","name":"%s","type":"RAW"},
               "inputQuantity":10,"outputQuantity":5}]}
    """
        .formatted(
            refinery.getId(),
            refinery.getName(),
            durationMinutes,
            version == null ? "" : "\"version\":" + version + ",",
            ore.getId(),
            ore.getName());
  }

  private String storeBody() {
    return """
    {"items":[{"materialId":"%s","locationId":"%s","quality":500,"amount":1.0}]}
    """
        .formatted(ore.getId(), refinery.getId());
  }

  private List<String> events(UUID id) {
    entityManager.flush();
    return jdbc.queryForList(
        "SELECT event_type FROM audit_event WHERE subject_id = ? ORDER BY occurred_at, event_type",
        String.class,
        id);
  }

  private Squadron squadron(String name, String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    return squadronRepository.save(squadron);
  }

  private User member(String username, Squadron unit) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 6));
    user = userRepository.save(user);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return user;
  }

  private static RequestPostProcessor as(User user, String... authorities) {
    return jwt()
        .jwt(builder -> builder.subject(user.getId().toString()))
        .authorities(
            Arrays.stream(authorities).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
  }
}
