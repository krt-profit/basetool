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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the dashboard widget catalogue (REQ-UI-034): the endpoint answers every authenticated caller
 * and its body equals the golden file {@code api/dashboard-catalogue.json}, which the frontend's
 * tests read as well. After an intended change regenerate the file with {@code
 * -Ddashboard.catalogue.update=true} and review the diff against the REQ-UI-034 matrix.
 */
@SpringBootTest
class NavigationDashboardCatalogueControllerTest {

  private static final String PATH = "/api/v1/navigation/dashboard-widgets";
  private static final String UPDATE_PROPERTY = "dashboard.catalogue.update";
  private static final Path GOLDEN =
      Paths.get("src", "test", "resources", "api", "dashboard-catalogue.json");

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void anonymousCallersAreRefused() throws Exception {
    mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
  }

  @Test
  void aMemberReadsTheCatalogueTheGoldenFilePins() throws Exception {
    String body =
        mockMvc
            .perform(
                get(PATH)
                    .with(
                        jwt()
                            .jwt(token -> token.subject("catalogue-reader"))
                            .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonMapper mapper = JsonMapper.builder().build();
    JsonNode actual = mapper.readTree(body);
    if (Boolean.getBoolean(UPDATE_PROPERTY)) {
      Files.writeString(
          GOLDEN, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
    }
    assertThat(actual).isEqualTo(mapper.readTree(Files.readString(GOLDEN)));
  }
}
