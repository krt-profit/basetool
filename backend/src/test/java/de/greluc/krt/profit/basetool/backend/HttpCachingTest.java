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
// REQ-SEC-052: every route these cases exercise requires a login now, so the class carries a
// principal. What each case asserts is unchanged — only the caller is.
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

    // Conditional GET with If-None-Match
    mockMvc
        .perform(get("/api/v1/job-types").header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }

  @Test
  void protectedEndpoint_unauthenticatedConditionalGet_isClientErrorNot304() throws Exception {
    // L-9: the ShallowEtagHeaderFilter sits at the front of the chain, but it computes the 304 from
    // the *generated* body. For an unauthenticated caller on a protected endpoint that body is
    // Spring Security's 401, not the resource — so a fabricated If-None-Match can never short-
    // circuit to a 304 of protected content. is4xxClientError() asserts the denial (401/403) and,
    // by construction, excludes the 304 (3xx) that an ETag oracle would need.
    mockMvc
        .perform(get("/api/v1/users").header("If-None-Match", "\"fabricated-etag\""))
        .andExpect(status().is4xxClientError());
  }

  /**
   * REQ-SEC-031 turned this endpoint's guarantee from "no usable oracle" into "no oracle at all".
   *
   * <p>The test previously obtained a real ETag from {@code /api/v1/users} as an authorized member,
   * confirmed the intended 304 on replay, and then showed that an unauthenticated replay is denied
   * rather than answered with a 304 (finding L-9). Since the member families carry {@code
   * no-store}, Spring's {@code ShallowEtagHeaderFilter} deliberately emits no ETag for them at all
   * — so the cross-principal comparison it was guarding against cannot even be attempted. The 304
   * half is unchanged and still covered on a non-sensitive family by {@link
   * #etagAndConditionalGet_ShouldReturn304_OnMatch()}.
   *
   * <p>The cost is real and accepted: this family no longer benefits from conditional requests and
   * transfers its body every time.
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

    // A fabricated ETag must still be denied rather than short-circuited to a 304 of protected
    // content: security answers before any comparison, exactly as it did before.
    mockMvc
        .perform(get("/api/v1/users").header("If-None-Match", "\"fabricated-etag\""))
        .andExpect(status().is4xxClientError());
  }
}
