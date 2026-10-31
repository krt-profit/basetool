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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Holds wave 4 of the REST API cut (the org-unit bank prefix) to the running application: the
 * controller serves exactly the new paths of the map, no handler answers the old prefix any more,
 * the new paths demand authentication, and the old ones answer {@code 404} (frozen operations) or
 * {@code 404} (the five that were never frozen).
 */
@SpringBootTest
class OrgUnitBankPathCutTest {

  private static final String OLD_PREFIX = "/api/v1/org-units/bank";
  private static final String NEW_PREFIX = "/api/v1/bank/org-units";
  private static final String SAMPLE_UUID = "00000000-0000-4000-8000-00000000cafe";

  /**
   * Operations of the controller that are not in the cut map because they never had an old path.
   */
  private static final Set<String> NOT_IN_THE_CUT_MAP =
      Set.of("GET " + NEW_PREFIX + "/accounts/visible");

  /** One wave-4 line of the map: verb, old path, new path, whether the operation is frozen. */
  private record Moved(String verb, String oldPath, String newPath, boolean frozen) {}

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  /** Builds MockMvc with the real security filter chain. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @DisplayName(
      "the controller serves exactly the new paths of wave 4 plus the operations outside the map,"
          + " and nothing under the old")
  void theControllerServesExactlyTheNewPaths() throws IOException {
    List<Moved> moved = wave4();
    assertThat(moved).as("the 29 operations of wave 4").hasSize(29);

    RequestMappingHandlerMapping mapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    Set<String> served = new TreeSet<>();
    Set<String> underOldPrefix = new TreeSet<>();
    mapping
        .getHandlerMethods()
        .forEach((info, handler) -> {
          for (String verb : verbs(info)) {
            for (String pattern : patterns(info)) {
              if (OrgUnitBankController.class.equals(beanType(handler))) {
                served.add(verb + " " + pattern);
              }
              if (pattern.startsWith(OLD_PREFIX)) {
                underOldPrefix.add(verb + " " + pattern);
              }
            }
          }
        });

    Set<String> expected = new TreeSet<>(NOT_IN_THE_CUT_MAP);
    moved.forEach(m -> expected.add(m.verb() + " " + m.newPath()));
    assertThat(served).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(underOldPrefix).as("handlers left under %s", OLD_PREFIX).isEmpty();
  }

  @Test
  @DisplayName("every new path demands authentication")
  void everyNewPathDemandsAuthentication() throws Exception {
    for (Moved m : wave4()) {
      mockMvc
          .perform(request(HttpMethod.valueOf(m.verb()), concrete(m.newPath())).with(csrf()))
          .andExpect(status().isUnauthorized());
    }
    for (String operation : NOT_IN_THE_CUT_MAP) {
      String[] verbAndPath = operation.split(" ", 2);
      mockMvc
          .perform(request(HttpMethod.valueOf(verbAndPath[0]), verbAndPath[1]).with(csrf()))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  @DisplayName("every old path is gone, frozen or not")
  void theOldPathsAreGone() throws Exception {
    for (Moved m : wave4()) {
      HttpMethod method = HttpMethod.valueOf(m.verb());
      mockMvc
          .perform(
              request(method, concrete(m.oldPath()))
                  .with(user("member").roles("ADMIN"))
                  .with(csrf()))
          .andExpect(status().isNotFound());
    }
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
    return template
        .replace("{enabled}", "true")
        .replace("{roleCode}", "LOGISTICIAN")
        .replaceAll("\\{[A-Za-z]+}", SAMPLE_UUID);
  }

  private static List<Moved> wave4() throws IOException {
    List<Moved> moved = new ArrayList<>();
    try (InputStream in =
        OrgUnitBankPathCutTest.class.getResourceAsStream("/api/api-cut-map.txt")) {
      assertThat(in).as("api/api-cut-map.txt on the test classpath").isNotNull();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        String[] f = line.trim().split(" ");
        if (f.length == 6 && "4".equals(f[0])) {
          assertThat(f[2]).startsWith(OLD_PREFIX);
          assertThat(f[3]).startsWith(NEW_PREFIX);
          moved.add(new Moved(f[1], f[2], f[3], "T1".equals(f[4])));
        }
      }
    }
    return moved;
  }
}
