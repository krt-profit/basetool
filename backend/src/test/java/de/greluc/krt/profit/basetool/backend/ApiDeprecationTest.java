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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(ApiDeprecationTest.TestDeprecationController.class)
class ApiDeprecationTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @RestController
  @RequestMapping("/api/test-deprecation")
  static class TestDeprecationController {

    @GetMapping("/v1/resource")
    @ApiDeprecation(sunset = "2026-12-31", replacement = "/api/test-deprecation/v2/resource")
    public String getResourceV1() {
      return "v1";
    }

    @GetMapping("/v2/resource")
    public String getResourceV2() {
      return "v2";
    }

    @GetMapping("/v1/deprecated-java")
    @Deprecated
    public String getDeprecatedJava() {
      return "deprecated-java";
    }
  }

  @Test
  void testDeprecatedEndpoint_ReturnsSunsetAndLinkHeaders() throws Exception {
    mockMvc
        .perform(get("/api/test-deprecation/v1/resource").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(header().string("Deprecation", "true"))
        .andExpect(header().string("Sunset", "Thu, 31 Dec 2026 00:00:00 GMT"))
        .andExpect(
            header().string("Link", "</api/test-deprecation/v2/resource>; rel=\"alternate\""));
  }

  @Test
  void testNewEndpoint_DoesNotReturnDeprecationHeaders() throws Exception {
    mockMvc
        .perform(get("/api/test-deprecation/v2/resource").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Deprecation"))
        .andExpect(header().doesNotExist("Sunset"))
        .andExpect(header().doesNotExist("Link"));
  }

  @Test
  void testJavaDeprecatedEndpoint_ReturnsDeprecationHeader() throws Exception {
    mockMvc
        .perform(get("/api/test-deprecation/v1/deprecated-java").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(header().string("Deprecation", "true"))
        .andExpect(header().doesNotExist("Sunset"))
        .andExpect(header().doesNotExist("Link"));
  }
}
