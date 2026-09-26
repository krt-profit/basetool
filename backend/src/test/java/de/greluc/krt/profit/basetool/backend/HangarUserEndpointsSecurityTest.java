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

package de.greluc.krt.profit.basetool.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HangarUserEndpointsSecurityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Autowired private SquadronRepository squadronRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @MockitoBean private JwtDecoder jwtDecoder;

  private User user;
  private ShipType shipType;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("hanger_user");
    userRepository.save(user);
    OrgUnitMembership iridiumMembership = new OrgUnitMembership();
    iridiumMembership.setId(new OrgUnitMembershipId(user.getId(), Squadron.IRIDIUM_ID));
    iridiumMembership.setUser(user);
    iridiumMembership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(iridiumMembership);

    shipType = new ShipType();
    shipType.setName("Aurora");
    shipTypeRepository.save(shipType);
  }

  @Test
  void testMyShips_ReadAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/hangar/my-ships")
                .with(
                    jwt()
                        .jwt(j -> j.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_READ"))))
        .andExpect(status().isOk());
  }

  @Test
  void testMyShips_NoReadAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/hangar/my-ships")
                .with(
                    jwt()
                        .jwt(j -> j.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  @Test
  void testAddShip_WriteAllowed() throws Exception {
    String body =
        "{"
            + "\"name\": \"MyShip\","
            + "\"shipTypeId\": \""
            + shipType.getId()
            + "\","
            + "\"insurance\": \"0\","
            + "\"fitted\": false"
            + "}";
    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(j -> j.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void testAddShip_NoWriteAllowed() throws Exception {
    String body =
        "{"
            + "\"name\": \"MyShip\","
            + "\"shipTypeId\": \""
            + shipType.getId()
            + "\","
            + "\"insurance\": \"0\","
            + "\"fitted\": false"
            + "}";
    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(j -> j.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_READ")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }
}
