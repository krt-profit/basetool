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

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class SecurityHardeningIntegrationTest {

  @Autowired private WebApplicationContext context;

  @Autowired private UserRepository userRepository;

  private MockMvc mockMvc;
  private User regularUser;
  private User adminUser;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    regularUser = new User();
    regularUser.setId(UUID.randomUUID());
    regularUser.setUsername("regular_user");
    userRepository.save(regularUser);

    adminUser = new User();
    adminUser.setId(UUID.randomUUID());
    adminUser.setUsername("admin_user");
    userRepository.save(adminUser);
  }

  @Test
  void testUserSearch_RegularUser_Forbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/search")
                .param("query", "test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void testUserSearch_Admin_Ok() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/search")
                .param("query", "test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void testInventoryAggregated_RegularUser_Forbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/aggregated")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void testInventoryMyInventory_RegularUser_Ok() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/my-inventory")
                .with(
                    jwt()
                        .jwt(j -> j.subject(regularUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  @Test
  void testMissionCreate_RegularUser_Ok() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/missions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Member Mission\"}")
                .with(
                    jwt()
                        .jwt(b -> b.subject(java.util.UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  @Test
  void testMissionGet_Anonymous_Refused() throws Exception {
    // Answered 200 until REQ-SEC-052. The mission list was the widest anonymous read the tool had.
    mockMvc.perform(get("/api/v1/missions")).andExpect(status().isUnauthorized());
  }

  @Test
  void testFinanceEntries_Anonymous_Unauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/missions/" + UUID.randomUUID() + "/finance-entries"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void testFinanceEntriesSum_Anonymous_Unauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/missions/" + UUID.randomUUID() + "/finance-entries/sum"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void testRefineryOrders_Anonymous_Unauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/refinery-orders/mission/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
