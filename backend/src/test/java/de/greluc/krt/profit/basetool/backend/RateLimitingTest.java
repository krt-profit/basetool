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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "app.rate-limit.enabled=true",
      "app.rate-limit.paths=/**",
      "app.rate-limit.capacity=2",
      "app.rate-limit.refillTokens=2",
      "app.rate-limit.refillPeriod=1h"
    })
class RateLimitingTest {

  @Autowired private WebApplicationContext context;

  @Autowired
  private de.greluc.krt.profit.basetool.backend.filter.RateLimitingFilter rateLimitingFilter;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .addFilters(rateLimitingFilter)
            .build();
  }

  @Test
  void adminRoles_ShouldBeRateLimited_AfterQuota() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/roles")
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_MANAGE"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "USER_MANAGE"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "MISSION_MANAGE")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Rate-Limit-Limit", "2"));

    mockMvc
        .perform(
            get("/api/v1/admin/roles")
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_MANAGE"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "USER_MANAGE"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "MISSION_MANAGE")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Rate-Limit-Limit", "2"))
        .andExpect(header().string("X-Rate-Limit-Remaining", "0"));

    mockMvc
        .perform(
            get("/api/v1/admin/roles")
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_MANAGE"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "USER_MANAGE"),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "MISSION_MANAGE")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isTooManyRequests())
        .andExpect(
            header()
                .string("X-Rate-Limit-Retry-After-Seconds", org.hamcrest.Matchers.notNullValue()))
        .andExpect(content().contentType("application/problem+json"));
  }
}
