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

package de.greluc.krt.profit.basetool.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the inventory module's externally visible contract: the Lager commands record their audit
 * events in the command's transaction (REQ-AUDIT-001), and a member of another org unit is refused
 * on every per-row write entry point of a row owned by the home unit (REQ-ORG-002, REQ-ORG-011).
 */
@SpringBootTest
@Transactional
class InventoryModuleContractTest {

  private static final String INVENTORY = "/api/v1/inventory";

  private static final String MEMBER = "ROLE_KRT_MEMBER";

  private static final String LOGISTICIAN = "ROLE_LOGISTICIAN";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private MockMvc mockMvc;

  private Squadron home;

  private User insider;

  private User outsider;

  private Material ore;

  private Location hangar;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString().substring(0, 6);
    home = squadron("InvHome-" + tag, "I" + tag);
    insider = member("inv-insider", home);
    outsider = member("inv-outsider", squadron("InvForeign-" + tag, "J" + tag));
    ore = new Material();
    ore.setName("Inv Ore " + tag);
    ore.setType(MaterialType.REFINED);
    ore.setQuantityType(QuantityType.SCU);
    ore = materialRepository.save(ore);
    hangar = new Location();
    hangar.setName("Inv Hangar " + tag);
    hangar = locationRepository.save(hangar);
  }

  @Test
  void theLagerCommandsRecordTheirAuditEvents() throws Exception {
    UUID id = create();
    assertThat(events(id)).contains("INVENTORY_ITEM_CREATED");

    mockMvc
        .perform(
            json(put(INVENTORY + "/" + id + "/note"), "{\"note\":\"Kiste 3\",\"version\":%d}", id)
                .with(as(insider, MEMBER)))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("INVENTORY_ITEM_NOTE_UPDATED");

    mockMvc
        .perform(
            json(
                    post(INVENTORY + "/" + id + "/personal-rebook"),
                    "{\"amount\":3,\"version\":%d}",
                    id)
                .with(as(insider, MEMBER)))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("INVENTORY_ITEM_PERSONALIZED");

    mockMvc
        .perform(
            json(
                    post(INVENTORY + "/" + id + "/book-out"),
                    "{\"amount\":2,\"type\":\"DISCARD\",\"version\":%d}",
                    id)
                .with(as(insider, MEMBER)))
        .andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("INVENTORY_ITEM_CONSUMED");
  }

  @Test
  void aForeignMemberIsRefusedOnEveryPerRowWriteEntryPoint() throws Exception {
    UUID id = create();
    String row = INVENTORY + "/" + id;
    RequestPostProcessor writer = as(outsider, MEMBER, LOGISTICIAN);

    for (MockHttpServletRequestBuilder request :
        List.of(
            json(post(row + "/book-out"), "{\"amount\":1,\"type\":\"DISCARD\",\"version\":%d}", id),
            json(post(row + "/personal-rebook"), "{\"amount\":1,\"version\":%d}", id),
            json(put(row + "/note"), "{\"note\":\"fremd\",\"version\":%d}", id),
            json(post(row + "/org-unit"), "{\"targetOwningOrgUnitId\":null,\"version\":%d}", id),
            json(post(row + "/stolen"), "{\"stolen\":true,\"version\":%d}", id),
            json(patch(row + "/delivered"), delivered(), id),
            json(post(row + "/allocation"), allocation(), id),
            json(patch(row + "/allocation"), allocation(), id),
            json(delete(row + "/allocation"), allocation(), id))) {
      mockMvc.perform(request.with(writer)).andExpect(status().isForbidden());
    }
  }

  private static String delivered() {
    return "{\"delivered\":true,\"jobOrderId\":\"" + UUID.randomUUID() + "\",\"version\":%d}";
  }

  private static String allocation() {
    return "{\"field\":\"JOB_ORDER\",\"targetId\":\""
        + UUID.randomUUID()
        + "\",\"amount\":1,\"version\":%d}";
  }

  private UUID create() throws Exception {
    String body =
        """
        {"materialId":"%s","locationId":"%s","quality":500,"amount":10,"owningOrgUnitId":"%s"}
        """
            .formatted(ore.getId(), hangar.getId(), home.getId());
    String response =
        mockMvc
            .perform(
                post(INVENTORY)
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

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body, UUID id) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body.formatted(version(id)));
  }

  private long version(UUID id) {
    entityManager.flush();
    return jdbc.queryForObject("SELECT version FROM inventory_item WHERE id = ?", Long.class, id);
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
