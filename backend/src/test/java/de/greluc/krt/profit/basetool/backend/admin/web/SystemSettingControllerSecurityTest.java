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

package de.greluc.krt.profit.basetool.backend.admin.web;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.admin.internal.SystemSettingDto;
import de.greluc.krt.profit.basetool.backend.admin.internal.SystemSettingService;
import de.greluc.krt.profit.basetool.backend.platform.internal.ApiCacheControlFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Pins the gate of the generic settings reads through the real security chain (REQ-SEC-083): a
 * signed-in member without {@code ADMIN} is forbidden on both reads, an admin is served and the
 * answer is {@code no-store} (REQ-SEC-031).
 */
@SpringBootTest
class SystemSettingControllerSecurityTest {

  /** The key of the one setting the stubbed service knows. */
  private static final String KEY = "job_order.age_yellow_days";

  @Autowired private WebApplicationContext context;

  @Autowired private ApiCacheControlFilter apiCacheControlFilter;

  @Autowired private ShallowEtagHeaderFilter shallowEtagHeaderFilter;

  @MockitoBean private SystemSettingService systemSettingService;

  private MockMvc mockMvc;

  /** Builds MockMvc with the real security filter chain and a service that answers one key. */
  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(shallowEtagHeaderFilter, apiCacheControlFilter)
            .apply(springSecurity())
            .build();
    SystemSettingDto setting = new SystemSettingDto(KEY, "30", 1L);
    when(systemSettingService.getSetting(KEY)).thenReturn(setting);
    when(systemSettingService.getAllSettings()).thenReturn(List.of(setting));
  }

  /**
   * Signs in as a caller holding one role.
   *
   * @param role the authority name, e.g. {@code ROLE_ADMIN}
   * @return the token post-processor
   */
  private static RequestPostProcessor as(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority(role));
  }

  /**
   * Both reads refuse every signed-in role below ADMIN.
   *
   * @param role the authority of the caller
   * @throws Exception if the request could not be performed
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ROLE_KRT_MEMBER",
        "ROLE_LOGISTICIAN",
        "ROLE_MISSION_MANAGER",
        "ROLE_OFFICER",
        "ROLE_BANK_EMPLOYEE"
      })
  @DisplayName("a signed-in caller below ADMIN is forbidden on both generic reads")
  void aCallerBelowAdminIsForbidden(String role) throws Exception {
    mockMvc
        .perform(get("/api/v1/settings/" + KEY).with(as(role)))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/settings").with(as(role))).andExpect(status().isForbidden());
  }

  /**
   * Both reads serve an admin.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @DisplayName("an admin reads one setting and the list")
  void anAdminReadsBothReads() throws Exception {
    mockMvc
        .perform(get("/api/v1/settings/" + KEY).with(as("ROLE_ADMIN")))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/settings").with(as("ROLE_ADMIN"))).andExpect(status().isOk());
  }

  /**
   * Both reads answer {@code private, no-store} to an admin and carry no {@code ETag}, because the
   * admin-only key store is an administration tree.
   *
   * @throws Exception if the request could not be performed
   */
  @Test
  @DisplayName("an admin's read of the key store is no-store and carries no ETag")
  void theAdminReadsAreNoStore() throws Exception {
    for (String path : List.of("/api/v1/settings/" + KEY, "/api/v1/settings")) {
      mockMvc
          .perform(get(path).with(as("ROLE_ADMIN")))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
          .andExpect(header().doesNotExist(HttpHeaders.ETAG));
    }
  }
}
