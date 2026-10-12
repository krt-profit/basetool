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

package de.greluc.krt.profit.basetool.backend.blueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
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
 * Pins the blueprint module's externally visible contract: adding, updating and removing a personal
 * blueprint record their audit events in the command's transaction (REQ-AUDIT-001), another member
 * reaches none of them, and a plain member is refused on the org-unit blueprint overview.
 */
@SpringBootTest
@Transactional
class BlueprintModuleContractTest {

  private static final String OWN = "/api/v1/personal-blueprints";

  private static final String MEMBER = "ROLE_KRT_MEMBER";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private BlueprintNameNormalizer normalizer;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private MockMvc mockMvc;

  private User owner;

  private User other;

  private String productKey;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    owner = user("bp-owner");
    other = user("bp-other");
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("bp_" + UUID.randomUUID());
    blueprint.setOutputName("Contract Rifle " + UUID.randomUUID().toString().substring(0, 8));
    blueprint.setIsAvailableByDefault(false);
    productKey = normalizer.normalize(blueprintRepository.saveAndFlush(blueprint).getOutputName());
  }

  @Test
  void thePersonalBlueprintCommandsRecordTheirAuditEvents() throws Exception {
    UUID id = add();
    assertThat(events(id)).contains("BLUEPRINT_ADDED");

    mockMvc.perform(update(id, "Kiste 3").with(as(owner))).andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("BLUEPRINT_UPDATED");

    mockMvc.perform(delete(OWN + "/" + id).with(as(owner))).andExpect(status().is2xxSuccessful());
    assertThat(events(id)).contains("BLUEPRINT_REMOVED");
  }

  @Test
  void anotherMemberReachesNoneOfTheOwnersBlueprints() throws Exception {
    UUID id = add();

    for (MockHttpServletRequestBuilder request :
        List.of(update(id, "fremd"), delete(OWN + "/" + id), get(OWN + "/" + id + "/recipe"))) {
      mockMvc.perform(request.with(as(other))).andExpect(status().isNotFound());
    }
    assertThat(events(id)).containsExactly("BLUEPRINT_ADDED");
  }

  @Test
  void aPlainMemberIsRefusedOnTheOrgUnitOverview() throws Exception {
    for (String path : List.of(OWN + "/overview", OWN + "/overview/owners?productKey=x")) {
      mockMvc.perform(get(path).with(as(other))).andExpect(status().isForbidden());
    }
  }

  private UUID add() throws Exception {
    String response =
        mockMvc
            .perform(
                post(OWN)
                    .with(as(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"productKey\":\"%s\"}".formatted(productKey)))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    entityManager.flush();
    return UUID.fromString(objectMapper.readTree(response).get("id").asString());
  }

  private MockHttpServletRequestBuilder update(UUID id, String note) {
    entityManager.flush();
    long version =
        jdbc.queryForObject("SELECT version FROM personal_blueprint WHERE id = ?", Long.class, id);
    return put(OWN + "/" + id)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"note\":\"%s\",\"version\":%d}".formatted(note, version));
  }

  private List<String> events(UUID id) {
    entityManager.flush();
    return jdbc.queryForList(
        "SELECT event_type FROM audit_event WHERE subject_id = ? ORDER BY occurred_at",
        String.class,
        id);
  }

  private User user(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 6));
    return userRepository.save(user);
  }

  private static RequestPostProcessor as(User user) {
    return jwt()
        .jwt(builder -> builder.subject(user.getId().toString()))
        .authorities(new SimpleGrantedAuthority(MEMBER));
  }
}
