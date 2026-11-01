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

package de.greluc.krt.profit.basetool.backend.navigation.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Web-layer tests of {@link NavigationDashboardController}: the authentication gate, the admin mode
 * gate, the cap of twelve, the 409 per layout and per instance and the owner derived from the token
 * (REQ-UI-034).
 */
@SpringBootTest
@Transactional
class NavigationDashboardControllerTest {

  private static final String MEMBER = "/api/v1/navigation/dashboards/member";
  private static final String ADMIN = "/api/v1/navigation/dashboards/admin";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;

  private MockMvc mockMvc;
  private UUID owner;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("dash-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    owner = userRepository.saveAndFlush(user).getId();
  }

  private RequestPostProcessor asMember() {
    return jwt()
        .jwt(token -> token.subject(owner.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(token -> token.subject(owner.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static String layout(long version, int instances) {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < instances; i++) {
      items.append(i == 0 ? "" : ",").append("{\"type\":\"UPCOMING_DAYS\",\"column\":\"WIDE\"}");
    }
    return "{\"widgets\":[" + items + "],\"version\":" + version + "}";
  }

  @Test
  void anonymousCallersAreRefused() throws Exception {
    mockMvc.perform(get(MEMBER)).andExpect(status().isUnauthorized());
  }

  @Test
  void readsTheMemberDefaultByTheLowerCasePath() throws Exception {
    mockMvc
        .perform(get(MEMBER).with(asMember()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("MEMBER"))
        .andExpect(jsonPath("$.customised").value(false))
        .andExpect(jsonPath("$.widgets.length()").value(4));
  }

  @Test
  void theAdminDashboardIsForAdministrators() throws Exception {
    mockMvc.perform(get(ADMIN).with(asMember())).andExpect(status().isForbidden());
    mockMvc.perform(get(ADMIN).with(asAdmin())).andExpect(status().isOk());
  }

  @Test
  void storesTwelveInstancesAndReturnsTheNextVersion() throws Exception {
    mockMvc
        .perform(
            put(MEMBER + "/layout")
                .with(asMember())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(layout(0, 12)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.widgets.length()").value(12))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void refusesAThirteenthInstance() throws Exception {
    mockMvc
        .perform(
            put(MEMBER + "/layout")
                .with(asMember())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(layout(0, 13)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aStaleLayoutVersionIsAConflict() throws Exception {
    mockMvc
        .perform(
            put(MEMBER + "/layout")
                .with(asMember())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(layout(0, 1)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put(MEMBER + "/layout")
                .with(asMember())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(layout(0, 1)))
        .andExpect(status().isConflict());
  }

  @Test
  void resetsToTheDefault() throws Exception {
    mockMvc
        .perform(
            put(MEMBER + "/layout")
                .with(asMember())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(layout(0, 1)))
        .andExpect(status().isOk());

    mockMvc
        .perform(delete(MEMBER).param("version", "1").with(asMember()).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customised").value(false))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.widgets.length()").value(4));
  }

  @Test
  void anUnknownInstanceIsNotFound() throws Exception {
    mockMvc
        .perform(
            put(MEMBER + "/widgets/" + UUID.randomUUID() + "/options")
                .with(asMember())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\":7,\"rows\":5,\"version\":0}"))
        .andExpect(status().isNotFound());
  }
}
