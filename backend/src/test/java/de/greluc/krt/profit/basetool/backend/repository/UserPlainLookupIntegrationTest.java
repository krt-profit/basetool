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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins BE-PERF-12 (REQ-DATA-003): the graph-free {@link UserRepository#findPlainById(UUID)} leaves
 * {@code roles} lazy, and a request path that now resolves its user through it still returns the
 * user's roles — loaded lazily inside the handler's own transaction.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional}: a test transaction would hold one session
 * open across the whole method, which is exactly the condition a real request does not have and
 * would hide a {@code LazyInitializationException} (the 2026-09-06 lesson recorded in the vault's
 * Backend note). The seed commits, the request runs in its own transaction, and the rows are
 * removed afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserPlainLookupIntegrationTest {

  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private UUID userId;
  private UUID shipTypeId;
  private String roleName;

  @BeforeEach
  void seed() {
    transactionTemplate.executeWithoutResult(
        status -> {
          Role role = roleRepository.findAllWithPermissions().getFirst();
          roleName = role.getName();
          User user = new User();
          user.setId(UUID.randomUUID());
          user.setUsername("plain-lookup-" + UUID.randomUUID());
          user.getRoles().add(role);
          userId = userRepository.save(user).getId();
          ShipType type = new ShipType();
          type.setName("Plain lookup " + UUID.randomUUID());
          shipTypeId = shipTypeRepository.save(type).getId();
        });
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM ship WHERE owner_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    jdbcTemplate.update("DELETE FROM ship_type WHERE id = ?", shipTypeId);
  }

  @Test
  void plainLookupLeavesRolesLazy_graphedLookupFetchesThem() {
    User plain = userRepository.findPlainById(userId).orElseThrow();
    User graphed = userRepository.findById(userId).orElseThrow();

    assertThat(Hibernate.isInitialized(plain.getRoles())).isFalse();
    assertThat(Hibernate.isInitialized(graphed.getRoles())).isTrue();
    assertThat(graphed.getRoles()).extracting(Role::getName).contains(roleName);
  }

  @Test
  void plainLookupOfAnUnknownIdIsEmpty() {
    assertThat(userRepository.findPlainById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void addShipThroughThePlainLookup_stillReturnsTheOwnersRoles() throws Exception {
    // HangarService.addShip resolves the owner through findPlainById now; the ShipDto maps the
    // owner's roles inside the controller's transaction. Outside of one this would be a
    // LazyInitializationException and a 500.
    ShipRequestDto request =
        new ShipRequestDto("Plain lookup ship", shipTypeId, "LTI", null, false, null, null);
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    mockMvc
        .perform(
            post("/api/v1/hangar/users/" + userId + "/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(userId.toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owner.id").value(userId.toString()))
        .andExpect(jsonPath("$.owner.roles[0]").value(roleName));
  }
}
