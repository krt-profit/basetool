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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins BE-PERF-06: the slim picker searches {@code /api/v1/users/search/references} and {@code
 * /api/v1/users/search-bank/references} carry exactly the role gates of their full-DTO twins
 * ({@code /search}, {@code /search-bank}) and return only the reference projection — no e-mail, no
 * roles, no membership flags, so nothing a peer view would have to redact.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserReferenceSearchTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest(name = "{0} as {1} -> {2}")
  @CsvSource({
    "/api/v1/users/search/references, ROLE_NO_ROLE, 403",
    "/api/v1/users/search/references, ROLE_BANK_EMPLOYEE, 403",
    "/api/v1/users/search/references, ROLE_KRT_MEMBER, 200",
    "/api/v1/users/search/references, ROLE_OFFICER, 200",
    "/api/v1/users/search-bank/references, ROLE_NO_ROLE, 403",
    "/api/v1/users/search-bank/references, ROLE_BANK_EMPLOYEE, 200",
    "/api/v1/users/search-bank/references, ROLE_KRT_MEMBER, 200",
    "/api/v1/users/search-bank/references, ROLE_OFFICER, 200",
  })
  void gatesMatchTheFullDtoTwins(String path, String role, int expected) throws Exception {
    mockMvc
        .perform(
            get(path).param("query", "x").with(jwt().authorities(new SimpleGrantedAuthority(role))))
        .andExpect(status().is(expected));
  }

  @Test
  void anonymousIsUnauthorised() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/search/references").param("query", "x"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/users/search-bank/references").param("query", "x"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void returnsTheReferenceProjectionOnly() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("refsearch-" + tag);
    user.setDisplayName("Ref Search " + tag);
    user.setEmail("refsearch-" + tag + "@example.invalid");
    userRepository.saveAndFlush(user);

    mockMvc
        .perform(
            get("/api/v1/users/search/references")
                .param("query", tag)
                .param("size", "5")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(user.getId().toString()))
        .andExpect(jsonPath("$.content[0].username").value("refsearch-" + tag))
        .andExpect(jsonPath("$.content[0].effectiveName").value("Ref Search " + tag))
        .andExpect(jsonPath("$.content[0].email").doesNotExist())
        .andExpect(jsonPath("$.content[0].roles").doesNotExist())
        .andExpect(jsonPath("$.content[0].squadrons").doesNotExist());
  }
}
