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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import de.greluc.krt.profit.basetool.backend.config.PathControlInventory.Endpoint;
import de.greluc.krt.profit.basetool.backend.filter.ApiCacheControlFilter;
import de.greluc.krt.profit.basetool.backend.filter.NoStoreApiScopes;
import de.greluc.krt.profit.basetool.backend.filter.NoStoreApiScopes.Caching;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Proves every API family is classified as {@code no-store} or revalidatable, and that every {@code
 * GET} mapping answers with its family's directive through the real filters (REQ-SEC-031, D-18).
 *
 * <p>Requests are sent without a token, so the directive is asserted on the {@code 401} the chain
 * answers; one authenticated request per {@code no-store} family asserts it also survives the
 * handler.
 */
@SpringBootTest
class ApiCacheFamilyCoverageTest {

  /** Header value of a {@code no-store} family. */
  private static final String NO_STORE = "private, no-store";

  /** Header value of a revalidatable family. */
  private static final String REVALIDATE = "no-cache, must-revalidate";

  /** The number of {@code /api} mappings today; a smaller selection means the inventory broke. */
  private static final int API_MAPPING_FLOOR = 572;

  /** The number of {@code /api} {@code GET} mappings today. */
  private static final int API_GET_FLOOR = 244;

  /** The number of {@code no-store} families with a {@code GET} mapping today. */
  private static final int NO_STORE_READ_FAMILY_FLOOR = 26;

  /**
   * Orders mappings by fewest path variables, then by pattern, to pick a family's representative.
   */
  private static final Comparator<Endpoint> SIMPLEST_FIRST =
      Comparator.comparingLong((Endpoint e) -> e.pattern().chars().filter(c -> c == '{').count())
          .thenComparing(Endpoint::pattern);

  @Autowired private WebApplicationContext context;

  @Autowired private ApiCacheControlFilter apiCacheControlFilter;

  @Autowired private ShallowEtagHeaderFilter shallowEtagHeaderFilter;

  private MockMvc mockMvc;

  /** Builds MockMvc with both cache filters and the real security chain. */
  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(shallowEtagHeaderFilter, apiCacheControlFilter)
            .apply(springSecurity())
            .build();
  }

  /**
   * The {@code /api} mappings of the dispatcher.
   *
   * @return every mapping under {@code /api}
   */
  @NotNull
  private List<Endpoint> apiEndpoints() {
    return PathControlInventory.dispatcher(context).stream()
        .filter(e -> PathControlInventory.isUnder(e.pattern(), "/api"))
        .toList();
  }

  /**
   * The {@code GET} mappings under {@code /api}.
   *
   * @return every {@code /api} mapping that answers {@code GET}
   */
  @NotNull
  private List<Endpoint> apiReads() {
    return apiEndpoints().stream().filter(e -> e.answers(HttpMethod.GET)).toList();
  }

  /**
   * The directive a path must answer with.
   *
   * @param path a concrete request path
   * @return the header value its family prescribes
   */
  @NotNull
  private static String expectedDirective(@NotNull String path) {
    return NoStoreApiScopes.classify(PathContainer.parsePath(path)) == Caching.REVALIDATE
        ? REVALIDATE
        : NO_STORE;
  }

  /**
   * Issues one {@code GET} and returns its {@code Cache-Control} header.
   *
   * @param path the request path
   * @param caller the caller to install, or {@code null} for no token
   * @return the header value, or {@code null} when absent
   * @throws Exception when the request could not be performed
   */
  private String cacheControlOf(@NotNull String path, RequestPostProcessor caller)
      throws Exception {
    var request = MockMvcRequestBuilders.get(path);
    if (caller != null) {
      request = request.with(caller);
    }
    return mockMvc.perform(request).andReturn().getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
  }

  @Test
  @DisplayName("every API mapping belongs to a classified family")
  void everyApiMappingIsClassified() {
    List<Endpoint> endpoints = apiEndpoints();

    assertThat(endpoints.size()).as("API mappings found").isGreaterThanOrEqualTo(API_MAPPING_FLOOR);
    assertThat(PathControlInventory.unclassifiedApiEndpoints(endpoints))
        .as(
            "classify the new family in NoStoreApiScopes as no-store (personal or member-specific"
                + " data) or revalidatable (shared or reference data), and list it in REQ-SEC-031")
        .isEmpty();
  }

  @Test
  @DisplayName("every classified family names at least one real mapping")
  void everyFamilyNamesARealMapping() {
    List<String> families = new ArrayList<>(NoStoreApiScopes.noStoreFamilies());
    families.addAll(NoStoreApiScopes.revalidateFamilies());

    assertThat(PathControlInventory.deadPatterns(families, List.of(), apiEndpoints()))
        .as("a family that names nothing is a stale entry or a path that moved without it")
        .isEmpty();
  }

  @Test
  @DisplayName("every GET answers its family's directive through the real filters")
  void everyReadAnswersItsFamilysDirective() throws Exception {
    List<Endpoint> reads = apiReads();
    List<String> wrong = new ArrayList<>();
    for (Endpoint read : reads) {
      String path = read.concretePath();
      String expected = expectedDirective(path);
      String actual = cacheControlOf(path, null);
      if (!expected.equals(actual)) {
        wrong.add(read + " -> " + actual + " (expected " + expected + ")");
      }
    }

    assertThat(reads.size()).as("API reads found").isGreaterThanOrEqualTo(API_GET_FLOOR);
    assertThat(wrong).as("REQ-SEC-031: a sensitive read must never be storable").isEmpty();
  }

  @Test
  @DisplayName("the sweep tells the two directives apart, so a uniform header would fail it")
  void theSweepDistinguishesTheDirectives() throws Exception {
    assertThat(cacheControlOf("/api/v1/missions", null)).isEqualTo(REVALIDATE);
    assertThat(cacheControlOf("/api/v1/users", null)).isEqualTo(NO_STORE);
    assertThat(expectedDirective("/api/v1/planted-family")).isEqualTo(NO_STORE);
  }

  @Test
  @DisplayName("one authenticated read per no-store family keeps no-store past its handler")
  void noStoreSurvivesTheHandler() throws Exception {
    Map<String, Endpoint> representatives = new TreeMap<>();
    for (Endpoint read : apiReads()) {
      PathContainer path = PathContainer.parsePath(read.concretePath());
      if (NoStoreApiScopes.classify(path) != Caching.NO_STORE) {
        continue;
      }
      representatives.merge(
          NoStoreApiScopes.familyOf(path),
          read,
          (a, b) -> SIMPLEST_FIRST.compare(a, b) <= 0 ? a : b);
    }
    RequestPostProcessor admin =
        jwt()
            .jwt(token -> token.subject(PathControlInventory.NIL_UUID))
            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    List<String> wrong = new ArrayList<>();
    for (Endpoint read : representatives.values()) {
      String actual = cacheControlOf(read.concretePath(), admin);
      if (!NO_STORE.equals(actual)) {
        wrong.add(read + " -> " + actual);
      }
    }

    assertThat(representatives.size())
        .as("no-store families with a read")
        .isGreaterThanOrEqualTo(NO_STORE_READ_FAMILY_FLOOR);
    assertThat(wrong).as("a handler must not weaken its family's no-store").isEmpty();
  }
}
