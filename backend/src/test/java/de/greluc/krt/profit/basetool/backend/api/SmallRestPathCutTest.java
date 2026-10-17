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

package de.greluc.krt.profit.basetool.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Holds wave 6 of the REST API cut (the small rest: announcement, Materialbörse requests, the
 * deleted Fleetview import and the Lager deactivation) to the running application: the new paths of
 * the map are served, no handler answers an old path any more, the new paths demand authentication
 * and keep the gates of the operations they replace, and the old ones answer {@code 410 404}; with
 * the app gone there is no retired-path answer any more (ADR-0269).
 */
@SpringBootTest
class SmallRestPathCutTest {

  private static final String SAMPLE_UUID = "00000000-0000-4000-8000-00000000cafe";

  /** One wave-6 line of the map: verb, old path, new path or {@code null}, frozen or not. */
  private record Moved(String verb, String oldPath, String newPath, boolean frozen) {}

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  /** Builds MockMvc with the real security filter chain. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @DisplayName("the wave has 15 operations, 9 of them frozen, and one of them is deleted")
  void theWaveIsTheOneTheMapNames() throws IOException {
    List<Moved> moved = wave6();

    assertThat(moved).hasSize(15);
    assertThat(moved).filteredOn(Moved::frozen).hasSize(9);
    assertThat(moved).filteredOn(m -> m.newPath() == null).hasSize(1);
  }

  @Test
  @DisplayName("every new path of wave 6 is served and no handler is left on an old one")
  void theNewPathsAreServedAndTheOldAreNot() throws IOException {
    RequestMappingHandlerMapping mapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    Set<String> served = new TreeSet<>();
    mapping
        .getHandlerMethods()
        .keySet()
        .forEach(
            info -> {
              for (String verb : verbs(info)) {
                for (String pattern : patterns(info)) {
                  served.add(verb + " " + pattern);
                }
              }
            });

    Set<String> expectedNew = new TreeSet<>();
    Set<String> expectedOld = new TreeSet<>();
    for (Moved m : wave6()) {
      if (m.newPath() != null) {
        expectedNew.add(m.verb() + " " + m.newPath());
      }
      expectedOld.add(m.verb() + " " + m.oldPath());
    }
    assertThat(served).containsAll(expectedNew);
    assertThat(served).doesNotContainAnyElementsOf(expectedOld);
    assertThat(served)
        .as("the typed settings, the ship import and nothing of the Fleetview alias")
        .contains(
            "GET /api/v1/orders/settings",
            "GET /api/v1/refinery-orders/settings",
            "POST /api/v1/hangar/import/ships")
        .doesNotContain("POST /api/v1/hangar/import/fleetview");
  }

  @Test
  @DisplayName("every new path demands authentication")
  void everyNewPathDemandsAuthentication() throws Exception {
    for (Moved m : wave6()) {
      if (m.newPath() == null) {
        continue;
      }
      mockMvc
          .perform(request(HttpMethod.valueOf(m.verb()), concrete(m.newPath())).with(csrf()))
          .andExpect(status().isUnauthorized());
    }
    for (String typed : List.of("/api/v1/orders/settings", "/api/v1/refinery-orders/settings")) {
      mockMvc.perform(get(typed)).andExpect(status().isUnauthorized());
    }
  }

  @Test
  @DisplayName("every old path is gone, frozen or not")
  void theOldPathsAreGone() throws Exception {
    for (Moved m : wave6()) {
      HttpMethod method = HttpMethod.valueOf(m.verb());
      mockMvc
          .perform(
              request(method, concrete(m.oldPath()))
                  .with(user("member").roles("ADMIN"))
                  .with(csrf()))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  @DisplayName("the announcement read keeps its public answer and the writes keep their gate")
  void theAnnouncementKeepsItsGates() throws Exception {
    MvcResult read =
        mockMvc
            .perform(get("/api/v1/announcements/current").with(user("m").roles("KRT_MEMBER")))
            .andReturn();
    assertThat(read.getResponse().getStatus()).isIn(200, 204);

    mockMvc
        .perform(get("/api/v1/announcements/current/admin").with(user("m").roles("KRT_MEMBER")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/announcements/current")
                .with(user("m").roles("KRT_MEMBER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\",\"version\":0}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/announcements/current/admin").with(user("a").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("the request board keeps the member gate of the Materialbörse")
  void theRequestBoardKeepsItsGate() throws Exception {
    mockMvc
        .perform(get("/api/v1/material-exchange/requests").with(user("b").roles("BANK_EMPLOYEE")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/material-exchange/requests").with(user("m").roles("KRT_MEMBER")))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/v1/material-exchange/requests/counts").with(user("m").roles("KRT_MEMBER")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("the Lager deactivation takes the row as a query parameter")
  void theDeactivationTakesTheRowAsAQueryParameter() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/material-exchange/offers/deactivate")
                .with(user("m").roles("KRT_MEMBER"))
                .with(csrf()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/material-exchange/offers/deactivate")
                .param("inventoryItemId", SAMPLE_UUID)
                .with(user("b").roles("BANK_EMPLOYEE"))
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("the typed settings answer every authenticated caller with numbers and a mode")
  void theTypedSettingsAreOpenToEveryAuthenticatedCaller() throws Exception {
    mockMvc
        .perform(get("/api/v1/orders/settings").with(user("b").roles("BANK_EMPLOYEE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ageYellowDays").isNumber())
        .andExpect(jsonPath("$.ageRedDays").isNumber());
    mockMvc
        .perform(get("/api/v1/refinery-orders/settings").with(user("b").roles("BANK_EMPLOYEE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roundingMode").isString());
  }

  private static Set<String> verbs(RequestMappingInfo info) {
    Set<String> verbs = new TreeSet<>();
    info.getMethodsCondition().getMethods().forEach(method -> verbs.add(method.name()));
    return verbs;
  }

  private static Set<String> patterns(RequestMappingInfo info) {
    Set<String> patterns = new TreeSet<>();
    if (info.getPathPatternsCondition() != null) {
      info.getPathPatternsCondition()
          .getPatterns()
          .forEach(p -> patterns.add(p.getPatternString()));
    }
    return patterns;
  }

  private static String concrete(String template) {
    return template.replaceAll("\\{[A-Za-z]+}", SAMPLE_UUID);
  }

  private static List<Moved> wave6() throws IOException {
    List<Moved> moved = new ArrayList<>();
    try (InputStream in = SmallRestPathCutTest.class.getResourceAsStream("/api/api-cut-map.txt")) {
      assertThat(in).as("api/api-cut-map.txt on the test classpath").isNotNull();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        String[] f = line.trim().split(" ");
        if (f.length == 6 && "6".equals(f[0])) {
          moved.add(new Moved(f[1], f[2], "-".equals(f[3]) ? null : f[3], "T1".equals(f[4])));
        }
      }
    }
    return moved;
  }
}
