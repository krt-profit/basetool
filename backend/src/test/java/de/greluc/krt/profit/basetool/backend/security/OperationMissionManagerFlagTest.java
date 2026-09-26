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

package de.greluc.krt.profit.basetool.backend.security;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the DB mission-manager flag, resolved by {@link
 * CustomJwtGrantedAuthoritiesConverter}, passes the {@code hasRole('MISSION_MANAGER')} gates of
 * {@link de.greluc.krt.profit.basetool.backend.controller.OperationController}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OperationMissionManagerFlagTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private UserRepository userRepository;

  @Autowired private OperationRepository operationRepository;

  @Autowired private SquadronRepository squadronRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;

  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void dbFlagAlone_grantsCreateOperation() throws Exception {
    User manager = newUser("dbflag-create-manager", true);
    Collection<GrantedAuthority> authorities = authoritiesFor(manager);
    assertTrue(
        authorities.stream().anyMatch(a -> "ROLE_MISSION_MANAGER".equals(a.getAuthority())),
        "Converter must promote the DB flag to ROLE_MISSION_MANAGER");

    mockMvc
        .perform(
            post("/api/v1/operations")
                .with(jwtFor(manager, authorities))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Op DBFlag Create\",\"status\":\"PLANNED\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void dbFlagAlone_grantsUpdateOperation() throws Exception {
    User manager = newUser("dbflag-update-manager", true);
    Operation existing = newOperation("Pre-existing Op");

    Collection<GrantedAuthority> authorities = authoritiesFor(manager);

    String body =
        "{\"name\":\"Renamed via DB-flag manager\",\"status\":\"PLANNED\",\"version\":"
            + existing.getVersion()
            + "}";

    mockMvc
        .perform(
            put("/api/v1/operations/" + existing.getId())
                .with(jwtFor(manager, authorities))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void noFlagAndNoKeycloakRole_isRejectedFromCreateOperation() throws Exception {
    User plainUser = newUser("plain-member", false);
    Collection<GrantedAuthority> authorities = authoritiesFor(plainUser);
    assertTrue(
        authorities.stream().noneMatch(a -> "ROLE_MISSION_MANAGER".equals(a.getAuthority())),
        "Converter must NOT grant ROLE_MISSION_MANAGER without flag or role");

    mockMvc
        .perform(
            post("/api/v1/operations")
                .with(jwtFor(plainUser, authorities))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Should be blocked\",\"status\":\"PLANNED\"}"))
        .andExpect(status().isForbidden());
  }

  private User newUser(String username, boolean missionManager) {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername(username);
    u = userRepository.save(u);
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(u.getId(), Squadron.IRIDIUM_ID));
    m.setUser(u);
    m.setJoinedAt(Instant.now());
    m.setMissionManager(missionManager);
    orgUnitMembershipRepository.save(m);
    return u;
  }

  private Operation newOperation(String name) {
    Operation op = new Operation();
    op.setName(name);
    op.setStatus(OperationStatus.PLANNED);
    op.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
    return operationRepository.save(op);
  }

  private Collection<GrantedAuthority> authoritiesFor(User user) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", user.getId().toString())
            .claim("preferred_username", user.getUsername())
            .build();
    Collection<GrantedAuthority> resolved = converter.convert(jwt);
    return resolved != null ? resolved : Collections.emptyList();
  }

  /** Builds the MockMvc JWT post-processor carrying the authorities resolved by the converter. */
  private org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(
      User user, Collection<GrantedAuthority> authorities) {
    return jwt()
        .jwt(
            builder ->
                builder
                    .subject(user.getId().toString())
                    .claim("preferred_username", user.getUsername()))
        .authorities(authorities);
  }
}
