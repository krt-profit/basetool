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

package de.greluc.krt.profit.basetool.backend.hangar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the hangar module's externally visible contract: creating, updating and deleting a ship
 * record their audit events in the command's transaction (REQ-AUDIT-001), and a member of another
 * org unit is refused on both per-ship write entry points of a ship owned by the home unit
 * (REQ-ORG-002, REQ-ORG-011).
 */
@SpringBootTest
@Transactional
class HangarModuleContractTest {

  private static final String SHIPS = "/api/v1/hangar/ships";

  private static final String MEMBER = "ROLE_KRT_MEMBER";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private MockMvc mockMvc;

  private Squadron home;

  private User insider;

  private User outsider;

  private ShipType type;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString().substring(0, 6);
    home = squadron("HgrHome-" + tag, "H" + tag);
    insider = member("hgr-insider", home);
    outsider = member("hgr-outsider", squadron("HgrForeign-" + tag, "G" + tag));
    type = new ShipType();
    type.setName("Hgr Cutlass " + tag);
    type = shipTypeRepository.saveAndFlush(type);
  }

  @Test
  void theShipCommandsRecordTheirAuditEvents() throws Exception {
    UUID id = create();
    assertThat(events(id)).contains("HANGAR_SHIP_CREATED");

    mockMvc
        .perform(json(put(SHIPS + "/" + id), "Umbenannt", id).with(as(insider)))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("HANGAR_SHIP_UPDATED");

    mockMvc
        .perform(delete(SHIPS + "/" + id).with(as(insider)))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("HANGAR_SHIP_DELETED");
  }

  @Test
  void aForeignMemberIsRefusedOnEveryPerShipWriteEntryPoint() throws Exception {
    UUID id = create();
    RequestPostProcessor foreign = as(outsider);

    for (MockHttpServletRequestBuilder request :
        List.of(json(put(SHIPS + "/" + id), "Fremd", id), delete(SHIPS + "/" + id))) {
      mockMvc.perform(request.with(foreign)).andExpect(status().isForbidden());
    }
    assertThat(events(id)).containsExactly("HANGAR_SHIP_CREATED");
  }

  private UUID create() throws Exception {
    String body =
        """
        {"name":"Erstflug","shipTypeId":"%s","insurance":"LTI","fitted":false,"owningOrgUnitId":"%s"}
        """
            .formatted(type.getId(), home.getId());
    String response =
        mockMvc
            .perform(
                post(SHIPS).with(as(insider)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    entityManager.flush();
    return UUID.fromString(objectMapper.readTree(response).get("id").asString());
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String name, UUID id) {
    String body =
        """
        {"name":"%s","shipTypeId":"%s","insurance":"LTI","fitted":true,"version":%d}
        """
            .formatted(name, type.getId(), version(id));
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private long version(UUID id) {
    entityManager.flush();
    return jdbc.queryForObject("SELECT version FROM ship WHERE id = ?", Long.class, id);
  }

  private List<String> events(UUID id) {
    entityManager.flush();
    return jdbc.queryForList(
        "SELECT event_type FROM audit_event WHERE subject_id = ? ORDER BY occurred_at",
        String.class,
        id);
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

  private static RequestPostProcessor as(User user) {
    return jwt()
        .jwt(builder -> builder.subject(user.getId().toString()))
        .authorities(new SimpleGrantedAuthority(MEMBER));
  }
}
