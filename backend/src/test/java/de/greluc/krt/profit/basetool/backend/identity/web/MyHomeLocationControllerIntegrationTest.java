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

package de.greluc.krt.profit.basetool.backend.identity.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the member's own home-location setting over the full stack (REQ-PATCH-030): the read and
 * the write of the caller's own row, the refusal of a location the profile does not offer and of a
 * stale version.
 */
@SpringBootTest
class MyHomeLocationControllerIntegrationTest {

  private static final String PATH = "/api/v1/users/me/home-location";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private UUID member;
  private UUID home;
  private UUID port;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("home-pref-web-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    member = userRepository.saveAndFlush(user).getId();
    home = location("Pref web home ", true);
    port = location("Pref web port ", false);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    for (UUID location : new UUID[] {home, port}) {
      UUID city =
          jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, location);
      jdbc.update("DELETE FROM location WHERE id = ?", location);
      jdbc.update("DELETE FROM city WHERE id = ?", city);
    }
  }

  @Test
  void aMemberReadsAndChoosesTheirOwnHomeLocation() throws Exception {
    mockMvc
        .perform(get(PATH).with(asMember()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.homeLocationId").isEmpty())
        .andExpect(jsonPath("$.version").value(0));

    mockMvc
        .perform(
            put(PATH)
                .with(asMember())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(home, 0)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.homeLocationId").value(home.toString()))
        .andExpect(jsonPath("$.version").value(1));

    mockMvc
        .perform(get(PATH).with(asMember()))
        .andExpect(jsonPath("$.homeLocationId").value(home.toString()))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void aLocationTheProfileDoesNotOfferIsRefused() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(asMember())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(port, 0)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aStaleVersionIsAConflict() throws Exception {
    mockMvc.perform(
        put(PATH).with(asMember()).contentType(MediaType.APPLICATION_JSON).content(body(home, 0)));

    mockMvc
        .perform(
            put(PATH)
                .with(asMember())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(home, 0)))
        .andExpect(status().isConflict());
  }

  /**
   * Authenticates as the test's member.
   *
   * @return the request post-processor
   */
  private @NotNull RequestPostProcessor asMember() {
    return jwt()
        .jwt(token -> token.subject(member.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  /**
   * Builds the write body.
   *
   * @param location the chosen location
   * @param version the version the caller read
   * @return the JSON body
   */
  private static @NotNull String body(@NotNull UUID location, long version) {
    return "{\"homeLocationId\":\"" + location + "\",\"version\":" + version + "}";
  }

  /**
   * Inserts a location on a city of its own.
   *
   * @param prefix the name's prefix
   * @param curatedHome whether it is a curated home location
   * @return its id
   */
  private @NotNull UUID location(@NotNull String prefix, boolean curatedHome) {
    UUID id = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, id, prefix + id);
    jdbc.update("UPDATE location SET is_home_location = ? WHERE id = ?", curatedHome, id);
    return id;
  }
}
