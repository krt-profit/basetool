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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests the {@code APP_UPDATE_REQUIRED} answer in the real security chain (REQ-API-020) with a
 * test-only retired entry: it answers ahead of authentication, so a caller with no token or a token
 * that no longer validates still meets the wall, and it answers nothing else.
 */
@SpringBootTest
class RetiredOperationChainTest {

  /** A test-only retired path; no such operation has ever existed. */
  private static final String RETIRED_PATH = "/api/v1/retired-for-test/0b3c";

  /** Replaces the committed list with one test-only entry. */
  @TestConfiguration
  static class TestOnlyEntry {

    /**
     * Provides the test-only list.
     *
     * @return a list retiring {@code GET /api/v1/retired-for-test/{id}}
     */
    @Bean
    @Primary
    RetiredOperations testOnlyRetiredOperations() {
      return RetiredOperations.parse(List.of("GET /api/v1/retired-for-test/{id}"));
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private FilterChainProxy filterChainProxy;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @DisplayName("a caller without a token meets the wall")
  void anAnonymousCallerMeetsTheWall() throws Exception {
    mockMvc
        .perform(get(RETIRED_PATH))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("APP_UPDATE_REQUIRED"));
  }

  @Test
  @DisplayName("a caller whose token no longer validates still meets the wall")
  void anInvalidTokenStillMeetsTheWall() throws Exception {
    mockMvc
        .perform(get(RETIRED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("APP_UPDATE_REQUIRED"));
  }

  @Test
  @DisplayName("another verb on the same path is still refused by authentication")
  void anotherVerbIsNotAnswered() throws Exception {
    mockMvc
        .perform(post(RETIRED_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a longer path under the retired one is still refused by authentication")
  void aLongerPathIsNotAnswered() throws Exception {
    mockMvc.perform(get(RETIRED_PATH + "/more")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the filter runs ahead of bearer-token authentication")
  void theFilterRunsAheadOfAuthentication() {
    List<String> names =
        filterChainProxy.getFilterChains().stream()
            .map(
                chain ->
                    chain.getFilters().stream()
                        .map(Filter::getClass)
                        .map(Class::getSimpleName)
                        .toList())
            .filter(chain -> chain.contains("RetiredOperationFilter"))
            .findFirst()
            .orElse(List.of());
    int retired = names.indexOf("RetiredOperationFilter");
    int bearer = names.indexOf("BearerTokenAuthenticationFilter");

    assertThat(retired).as("RetiredOperationFilter must be in the chain").isNotNegative();
    assertThat(bearer).as("BearerTokenAuthenticationFilter must be in the chain").isNotNegative();
    assertThat(retired).isLessThan(bearer);
  }
}
