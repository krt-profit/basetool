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

package de.greluc.krt.profit.basetool.backend.joborder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
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
 * Pins the job-order module's externally visible contract: the order commands record their audit
 * events in the command's transaction (REQ-AUDIT-001), and a member of another profit-eligible org
 * unit is refused on every per-order read and write entry point (REQ-ORDERS-*, REQ-ORG-003).
 */
@SpringBootTest
@Transactional
class JobOrderModuleContractTest {

  private static final String ORDERS = "/api/v1/orders";

  private static final String MEMBER = "ROLE_KRT_MEMBER";

  private static final String LOGISTICIAN = "ROLE_LOGISTICIAN";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private MockMvc mockMvc;

  private Squadron home;

  private User insider;

  private User outsider;

  private Material ore;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString().substring(0, 6);
    home = squadron("JobHome-" + tag, "J" + tag);
    insider = member("job-insider", home);
    outsider = member("job-outsider", squadron("JobForeign-" + tag, "K" + tag));
    ore = new Material();
    ore.setName("Job Ore " + tag);
    ore.setType(MaterialType.REFINED);
    ore.setQuantityType(QuantityType.SCU);
    ore = materialRepository.save(ore);
  }

  @Test
  void theOrderCommandsRecordTheirAuditEvents() throws Exception {
    JsonNode created = create();
    UUID id = UUID.fromString(created.get("id").asString());
    assertThat(events(id)).contains("JOB_ORDER_CREATED");

    mockMvc
        .perform(
            put(ORDERS + "/" + id + "/priority")
                .param("priority", "3")
                .with(as(insider, MEMBER, LOGISTICIAN)))
        .andExpect(status().isOk());
    assertThat(events(id)).contains("JOB_ORDER_PRIORITY_CHANGED");

    mockMvc
        .perform(
            post(ORDERS + "/" + id + "/assignees/" + insider.getId()).with(as(insider, MEMBER)))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("JOB_ORDER_ASSIGNEE_ADDED");

    long version = version(id);
    mockMvc
        .perform(
            put(ORDERS + "/" + id + "/status")
                .with(as(insider, MEMBER, LOGISTICIAN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":" + version + "}"))
        .andExpect(status().isOk());
    assertThat(events(id)).contains("JOB_ORDER_STATUS_CHANGED");

    mockMvc
        .perform(delete(ORDERS + "/" + id).with(as(insider, MEMBER, "ROLE_ADMIN")))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("JOB_ORDER_DELETED");
  }

  @Test
  void aForeignMemberIsRefusedOnEveryPerOrderEntryPoint() throws Exception {
    UUID id = UUID.fromString(create().get("id").asString());
    String order = ORDERS + "/" + id;
    RequestPostProcessor reader = as(outsider, MEMBER);
    RequestPostProcessor writer = as(outsider, MEMBER, LOGISTICIAN);

    for (String path :
        List.of(
            order,
            order + "/item-stock",
            order + "/material-collection",
            order + "/claims",
            order + "/inventory/orphaned",
            order + "/item-blueprint-owners")) {
      mockMvc.perform(get(path).with(reader)).andExpect(status().isForbidden());
    }
    mockMvc
        .perform(put(order + "/priority").param("priority", "4").with(writer))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(order + "/status")
                .with(writer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post(order + "/assignees/" + outsider.getId()).with(reader))
        .andExpect(status().isForbidden());
  }

  @Test
  void aMemberOfTheResponsibleUnitReadsTheOrder() throws Exception {
    UUID id = UUID.fromString(create().get("id").asString());
    mockMvc.perform(get(ORDERS + "/" + id).with(as(insider, MEMBER))).andExpect(status().isOk());
  }

  private JsonNode create() throws Exception {
    String body =
        """
        {"responsibleOrgUnitId":"%s","requestingOrgUnitId":"%s","handle":"contract",
         "materials":[{"materialId":"%s","amount":10}]}
        """
            .formatted(home.getId(), home.getId(), ore.getId());
    String response =
        mockMvc
            .perform(
                post(ORDERS)
                    .with(as(insider, MEMBER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    entityManager.flush();
    return objectMapper.readTree(response);
  }

  private long version(UUID id) {
    entityManager.flush();
    return jdbc.queryForObject("SELECT version FROM job_order WHERE id = ?", Long.class, id);
  }

  private List<String> events(UUID id) {
    entityManager.flush();
    return jdbc.queryForList(
        "SELECT event_type FROM audit_event WHERE subject_id = ?", String.class, id);
  }

  private Squadron squadron(String name, String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    squadron.setProfitEligible(true);
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
