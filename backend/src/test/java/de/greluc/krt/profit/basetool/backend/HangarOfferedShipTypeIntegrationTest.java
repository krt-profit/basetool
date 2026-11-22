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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipOrigin;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hangar offers and sets anew only a ship type the game files list, while a ship keeping
 * another type stays editable (REQ-P4K-080).
 */
@SpringBootTest
@Transactional
class HangarOfferedShipTypeIntegrationTest {

  private static final String NOT_OFFERED = "CATALOGUE_SHIP_TYPE_NOT_OFFERED";

  @Autowired private WebApplicationContext context;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private UserRepository userRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private MockMvc mockMvc;
  private User owner;
  private ShipType listed;
  private ShipType concept;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("offered-ship-owner");
    userRepository.save(owner);
    listed = shipType("Offered test listed ship", UUID.randomUUID());
    concept = shipType("Offered test concept ship", null);
  }

  private ShipType shipType(String name, UUID p4kUuid) {
    ShipType shipType = new ShipType();
    shipType.setName(name);
    shipType.setP4kUuid(p4kUuid);
    return shipTypeRepository.saveAndFlush(shipType);
  }

  private Ship ownedShip(ShipType shipType) {
    Ship ship = new Ship();
    ship.setName("Offered test ship");
    ship.setShipType(shipType);
    ship.setOwner(owner);
    ship.setInsurance("LTI");
    ship.setOrigin(ShipOrigin.PLEDGE);
    return shipRepository.saveAndFlush(ship);
  }

  private MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
    return request
        .with(
            jwt()
                .jwt(builder -> builder.subject(owner.getId().toString()))
                .authorities(new SimpleGrantedAuthority("HANGAR_WRITE")))
        .contentType(MediaType.APPLICATION_JSON);
  }

  private String body(ShipType shipType, Long version) {
    return objectMapper.writeValueAsString(
        new ShipRequestDto(
            "Offered test ship",
            shipType.getId(),
            "LTI",
            null,
            false,
            version,
            null,
            ShipOrigin.PLEDGE));
  }

  @Test
  void theShipTypeListOffersOnlyTheListedType() throws Exception {
    mockMvc
        .perform(
            asOwner(get("/api/v1/ship-types").param("query", "Offered test").param("size", "50")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(listed.getId().toString()))
        .andExpect(jsonPath("$.content[0].notInGame").value(false));
  }

  @Test
  void aNewShipOfATypeTheGameFilesDoNotList_isRefused() throws Exception {
    long before = shipRepository.count();

    mockMvc
        .perform(asOwner(post("/api/v1/hangar/ships")).content(body(concept, null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(NOT_OFFERED));

    assertThat(shipRepository.count()).isEqualTo(before);
  }

  @Test
  void aNewShipOfAListedType_isCreated() throws Exception {
    mockMvc
        .perform(asOwner(post("/api/v1/hangar/ships")).content(body(listed, null)))
        .andExpect(status().isOk());
  }

  @Test
  void aShipKeepingItsTypeTheGameFilesDoNotList_staysEditable() throws Exception {
    Ship ship = ownedShip(concept);

    mockMvc
        .perform(
            asOwner(put("/api/v1/hangar/ships/{id}", ship.getId()))
                .content(body(concept, ship.getVersion())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shipType.notInGame").value(true));
  }

  @Test
  void aShipChangedToATypeTheGameFilesDoNotList_isRefused() throws Exception {
    Ship ship = ownedShip(listed);

    mockMvc
        .perform(
            asOwner(put("/api/v1/hangar/ships/{id}", ship.getId()))
                .content(body(concept, ship.getVersion())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(NOT_OFFERED));

    assertThat(shipRepository.findById(ship.getId()).orElseThrow().getShipType().getId())
        .isEqualTo(listed.getId());
  }
}
