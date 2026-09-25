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

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser
class HttpCachingTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired
  private de.greluc.krt.profit.basetool.backend.filter.ApiCacheControlFilter apiCacheControlFilter;

  @Autowired private ShallowEtagHeaderFilter shallowEtagHeaderFilter;

  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setup() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(shallowEtagHeaderFilter, apiCacheControlFilter)
            .apply(springSecurity())
            .build();
  }

  @Test
  void etagAndConditionalGet_ShouldReturn304_OnMatch() throws Exception {
    String etag =
        mockMvc
            .perform(get("/api/v1/job-types"))
            .andExpect(status().isOk())
            .andExpect(
                header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")))
            .andExpect(header().string("ETag", notNullValue()))
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    mockMvc
        .perform(get("/api/v1/job-types").header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }

  @Test
  void protectedEndpoint_unauthenticatedConditionalGet_isClientErrorNot304() throws Exception {
    mockMvc
        .perform(get("/api/v1/users").header("If-None-Match", "\"fabricated-etag\""))
        .andExpect(status().is4xxClientError());
  }

  /**
   * Verifies that a sensitive {@code no-store} endpoint emits no ETag and still denies an
   * unauthenticated replay (REQ-SEC-031).
   */
  @Test
  void protectedSensitiveEndpoint_emitsNoEtagAndStillDeniesAnUnauthenticatedReplay()
      throws Exception {
    SimpleGrantedAuthority member = new SimpleGrantedAuthority("ROLE_KRT_MEMBER");

    mockMvc
        .perform(get("/api/v1/users").with(jwt().authorities(member)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(header().doesNotExist("ETag"));

    mockMvc
        .perform(get("/api/v1/users").header("If-None-Match", "\"fabricated-etag\""))
        .andExpect(status().is4xxClientError());
  }

  /**
   * Verifies that a family skipped by the ETag filter, and therefore unbuffered, still delivers its
   * full body.
   */
  @Test
  void unbufferedSensitiveFamily_stillDeliversItsBody() throws Exception {
    SimpleGrantedAuthority member = new SimpleGrantedAuthority("ROLE_KRT_MEMBER");

    String body =
        mockMvc
            .perform(get("/api/v1/users").with(jwt().authorities(member)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(body).isNotBlank();
    org.assertj.core.api.Assertions.assertThat(body.charAt(0)).isIn('[', '{');
  }
}
