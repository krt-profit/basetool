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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
public class UserAccessControlTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @org.junit.jupiter.api.BeforeEach
  public void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void testSearchUsers_Anonymous_Forbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/search").param("query", "test"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void testSearchUsers_Authenticated_Forbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/search")
                .param("query", "test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void testSearchUsers_Officer_Allowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/search")
                .param("query", "test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isOk());
  }

  // The regular /search stays closed to bank staff (ADR-0086, #1193): a role-less bank employee
  // must
  // use the dedicated /search-bank twin, so the ordinary picker's authorization regime is
  // unchanged.
  @Test
  void testSearchUsers_BankEmployee_Forbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/search")
                .param("query", "test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void testSearchUsersForBank_Anonymous_Forbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/search-bank").param("query", "test"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void testSearchUsersForBank_Guest_Forbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/search-bank")
                .param("query", "test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void testSearchUsersForBank_Officer_Allowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/search-bank")
                .param("query", "test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isOk());
  }

  // The bank widening (ADR-0086, REQ-BANK-008/009/044): a bank employee/manager who holds no org
  // role can drive the bank pickers' server-side search via the dedicated /search-bank endpoint.
  @Test
  void testSearchUsersForBank_BankEmployee_Allowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/search-bank")
                .param("query", "test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isOk());
  }
}
