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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipInsuranceTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private ShipRepository shipRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private SquadronRepository squadronRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User user;
  private ShipType shipType;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("pilot1");
    userRepository.save(user);
    OrgUnitMembership iridiumMembership = new OrgUnitMembership();
    iridiumMembership.setId(new OrgUnitMembershipId(user.getId(), Squadron.IRIDIUM_ID));
    iridiumMembership.setUser(user);
    iridiumMembership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(iridiumMembership);

    shipType = new ShipType();
    shipType.setName("Test Ship Type");
    shipTypeRepository.save(shipType);
  }

  private ShipRequestDto createShipWithInsurance(String insurance) {
    return new ShipRequestDto("Test Ship", shipType.getId(), insurance, null, false, null, null);
  }

  @Test
  void testCreateShip_WithLTI_Allowed() throws Exception {
    ShipRequestDto ship = createShipWithInsurance("LTI");

    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ship)))
        .andExpect(status().isOk());
  }

  @Test
  void testCreateShip_WithPositiveInteger_Allowed() throws Exception {
    ShipRequestDto ship = createShipWithInsurance("120");

    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ship)))
        .andExpect(status().isOk());
  }

  @Test
  void testCreateShip_WithZero_Allowed() throws Exception {
    ShipRequestDto ship = createShipWithInsurance("0");

    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ship)))
        .andExpect(status().isOk());
  }

  @Test
  void testCreateShip_WithInvalidString_Forbidden() throws Exception {
    ShipRequestDto ship = createShipWithInsurance("Standard");

    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ship)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testCreateShip_WithNegativeInteger_Forbidden() throws Exception {
    ShipRequestDto ship = createShipWithInsurance("-10");

    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ship)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testCreateShip_WithDecimal_Forbidden() throws Exception {
    ShipRequestDto ship = createShipWithInsurance("10.5");

    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ship)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testCreateShip_WithTooHighInteger_Forbidden() throws Exception {
    ShipRequestDto ship = createShipWithInsurance("121");

    mockMvc
        .perform(
            post("/api/v1/hangar/ships")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ship)))
        .andExpect(status().isBadRequest());
  }
}
