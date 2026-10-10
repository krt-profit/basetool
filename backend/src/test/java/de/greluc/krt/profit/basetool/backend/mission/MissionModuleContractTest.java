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

package de.greluc.krt.profit.basetool.backend.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the mission module's externally visible contract: the mission commands record their audit
 * events in the command's transaction (REQ-AUDIT-001), an internal mission of another org unit is
 * refused on every per-mission read entry point and every mission of another org unit on the write
 * entry points, even for a mission manager (REQ-ORG-009), while a public mission stays readable.
 */
@SpringBootTest
@Transactional
class MissionModuleContractTest {

  private static final String MISSIONS = "/api/v1/missions";

  private static final String MEMBER = "ROLE_KRT_MEMBER";

  private static final String MANAGER = "ROLE_MISSION_MANAGER";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private MockMvc mockMvc;

  private Squadron home;

  private User insider;

  private User outsider;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString().substring(0, 6);
    home = squadron("MisHome-" + tag, "M" + tag);
    insider = member("mis-insider", home);
    outsider = member("mis-outsider", squadron("MisForeign-" + tag, "N" + tag));
  }

  @Test
  void theMissionCommandsRecordTheirAuditEvents() throws Exception {
    UUID id = create(true);
    assertThat(events(id)).contains("MISSION_CREATED");

    mockMvc
        .perform(
            post(MISSIONS + "/" + id + "/steps/slim")
                .with(as(insider, MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Sammeln\",\"stepsVersion\":" + section(id, "steps") + "}"))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("MISSION_STEP_ADDED");

    mockMvc
        .perform(
            patch(MISSIONS + "/" + id + "/core")
                .with(as(insider, MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Vertrag geaendert\",\"version\":" + section(id, "core") + "}"))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("MISSION_UPDATED");

    mockMvc
        .perform(post(MISSIONS + "/" + id + "/join").with(as(insider, MEMBER)))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("MISSION_PARTICIPANT_ADDED");

    mockMvc
        .perform(delete(MISSIONS + "/" + id).with(as(insider, MEMBER, "ROLE_ADMIN")))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("MISSION_DELETED");
  }

  @Test
  void anInternalForeignMissionIsRefusedOnEveryReadEntryPoint() throws Exception {
    UUID id = create(true);
    String mission = MISSIONS + "/" + id;
    RequestPostProcessor reader = as(outsider, MEMBER);

    for (String path :
        List.of(
            mission,
            mission + "/finance-entries",
            mission + "/finance-entries/sum",
            mission + "/finance-entries/summary")) {
      mockMvc.perform(get(path).with(reader)).andExpect(status().isForbidden());
    }
    mockMvc.perform(post(mission + "/join").with(reader)).andExpect(status().isForbidden());
  }

  @Test
  void aForeignMissionManagerIsRefusedOnTheWriteEntryPoints() throws Exception {
    UUID id = create(false);
    String mission = MISSIONS + "/" + id;
    RequestPostProcessor manager = as(outsider, MEMBER, MANAGER);

    mockMvc.perform(get(mission).with(as(outsider, MEMBER))).andExpect(status().isOk());
    mockMvc
        .perform(
            patch(mission + "/core")
                .with(manager)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"fremd\",\"version\":" + section(id, "core") + "}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(mission + "/steps/slim")
                .with(manager)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"fremd\",\"stepsVersion\":" + section(id, "steps") + "}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get(mission + "/participants/unassigned").with(manager))
        .andExpect(status().isForbidden());
  }

  @Test
  void aMemberOfTheOwningUnitReadsAnInternalMission() throws Exception {
    UUID id = create(true);
    mockMvc.perform(get(MISSIONS + "/" + id).with(as(insider, MEMBER))).andExpect(status().isOk());
  }

  private UUID create(boolean internal) throws Exception {
    String body =
        """
        {"name":"Vertrag","isInternal":%s,"owningOrgUnitId":"%s"}
        """
            .formatted(internal, home.getId());
    String response =
        mockMvc
            .perform(
                post(MISSIONS)
                    .with(as(insider, MEMBER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    entityManager.flush();
    return UUID.fromString(objectMapper.readTree(response).get("id").asString());
  }

  private long section(UUID id, String section) {
    entityManager.flush();
    return jdbc.queryForObject(
        "SELECT " + section + "_version FROM mission WHERE id = ?", Long.class, id);
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
