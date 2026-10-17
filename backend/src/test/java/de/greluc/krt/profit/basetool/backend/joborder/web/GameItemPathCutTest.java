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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.refinery.web.RefineryLocationYieldController;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Holds wave 5 of the REST API cut (job orders and game items) to the running application: the new
 * paths of the map are served, no handler answers an old path any more, the new paths demand
 * authentication and keep the gates of the operations they replace, and the old ones answer {@code
 * 404}; with the app gone there is no retired-path answer any more (ADR-0269).
 */
@SpringBootTest
class GameItemPathCutTest {

  private static final String SAMPLE_UUID = "00000000-0000-4000-8000-00000000cafe";

  /** One wave-5 line of the map: verb, old path, new path, whether the operation is frozen. */
  private record Moved(String verb, String oldPath, String newPath, boolean frozen) {}

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  /** Builds MockMvc with the real security filter chain. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @DisplayName("every new path of wave 5 is served and no handler is left on an old one")
  void theNewPathsAreServedAndTheOldAreNot() throws IOException {
    List<Moved> moved = wave5();
    assertThat(moved).as("the 7 operations of wave 5").hasSize(7);

    RequestMappingHandlerMapping mapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    Set<String> served = new TreeSet<>();
    Set<String> byGameItemController = new TreeSet<>();
    Set<String> byYieldController = new TreeSet<>();
    mapping
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              for (String verb : verbs(info)) {
                for (String pattern : patterns(info)) {
                  served.add(verb + " " + pattern);
                  if (GameItemController.class.equals(beanType(handler))) {
                    byGameItemController.add(verb + " " + pattern);
                  }
                  if (RefineryLocationYieldController.class.equals(beanType(handler))) {
                    byYieldController.add(verb + " " + pattern);
                  }
                }
              }
            });

    Set<String> expectedNew = new TreeSet<>();
    Set<String> expectedOld = new TreeSet<>();
    moved.forEach(
        m -> {
          expectedNew.add(m.verb() + " " + m.newPath());
          expectedOld.add(m.verb() + " " + m.oldPath());
        });
    assertThat(served).containsAll(expectedNew);
    assertThat(served).doesNotContainAnyElementsOf(expectedOld);
    assertThat(byGameItemController)
        .containsExactlyInAnyOrder(
            "GET /api/v1/game-items",
            "GET /api/v1/game-items/{gameItemId}/blueprints",
            "GET /api/v1/blueprints/{id}/derivation");
    assertThat(byYieldController)
        .containsExactly("GET /api/v1/locations/{locationId}/refinery-yields");
  }

  @Test
  @DisplayName("every new path demands authentication")
  void everyNewPathDemandsAuthentication() throws Exception {
    for (Moved m : wave5()) {
      mockMvc
          .perform(request(HttpMethod.valueOf(m.verb()), concrete(m.newPath())).with(csrf()))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  @DisplayName("every old path is gone, and none of them answers the retired 410 any more")
  void theOldPathsAreGone() throws Exception {
    for (Moved m : wave5()) {
      HttpMethod method = HttpMethod.valueOf(m.verb());
      int status =
          mockMvc
              .perform(
                  request(method, concrete(m.oldPath()))
                      .with(user("member").roles("ADMIN"))
                      .with(csrf()))
              .andReturn()
              .getResponse()
              .getStatus();

      assertThat(status)
          .as(
              "%s %s must be gone: 404, or 400 where a sibling pattern now reads the old segment"
                  + " as its path variable. The retired-path 410 ended with the app (ADR-0269)",
              m.verb(), m.oldPath())
          .isIn(400, 404);
    }
  }

  @Test
  @DisplayName(
      "the folded catalogue keeps the role rule of the inventory twin and the open order twin")
  void theFoldedCatalogueKeepsBothGates() throws Exception {
    mockMvc
        .perform(get("/api/v1/game-items").with(user("member").roles("KRT_MEMBER")))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/game-items").with(user("bank").roles("BANK_EMPLOYEE")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/game-items")
                .param("orderable", "true")
                .with(user("bank").roles("BANK_EMPLOYEE")))
        .andExpect(status().isOk());
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

  private static Class<?> beanType(HandlerMethod handler) {
    return handler.getBeanType();
  }

  private static String concrete(String template) {
    return template.replaceAll("\\{[A-Za-z]+}", SAMPLE_UUID);
  }

  private static List<Moved> wave5() throws IOException {
    List<Moved> moved = new ArrayList<>();
    try (InputStream in = GameItemPathCutTest.class.getResourceAsStream("/api/api-cut-map.txt")) {
      assertThat(in).as("api/api-cut-map.txt on the test classpath").isNotNull();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        String[] f = line.trim().split(" ");
        if (f.length == 6 && "5".equals(f[0])) {
          moved.add(new Moved(f[1], f[2], f[3], "T1".equals(f[4])));
        }
      }
    }
    return moved;
  }
}
