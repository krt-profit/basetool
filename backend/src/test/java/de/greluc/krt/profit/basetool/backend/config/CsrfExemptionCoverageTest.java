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

import de.greluc.krt.profit.basetool.backend.config.PathControlInventory.Endpoint;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves every write mapping lies inside the CSRF exemption of the real, armed {@link CsrfFilter},
 * and that no mapping exists outside the served surface (REQ-SEC-078, ADR-0144).
 *
 * <p>Re-arms CSRF via {@code app.security.csrf.armed-in-test}, because the {@code test} profile
 * otherwise removes the filter this test evaluates.
 */
@SpringBootTest(properties = "app.security.csrf.armed-in-test=true")
class CsrfExemptionCoverageTest {

  /** The roots every mapping lives under. */
  private static final List<String> SURFACE_ROOTS = List.of("/api", "/internal", "/actuator");

  /**
   * Framework mappings allowed outside the roots: the error page and springdoc's two OpenAPI
   * spellings, which are {@code ADMIN}-only and disabled under the {@code prod} profile.
   */
  private static final Set<String> SURFACE_EXTRAS =
      Set.of("/error", "/v3/api-docs", "/v3/api-docs.yaml");

  /**
   * Write mappings reviewed as outside the exemption, each with its reason.
   *
   * <p>{@code /error} answers every verb but is reached only by the container's error dispatch,
   * after the original request has already passed the chain.
   */
  private static final Map<String, String> REVIEWED_OUTSIDE_EXEMPTION =
      Map.of("/error", "reached only by the error dispatch, never routed a write directly");

  /** The number of write mappings today; a smaller selection means the inventory broke. */
  private static final int WRITE_MAPPING_FLOOR = 331;

  /** The number of mappings of every registry today. */
  private static final int MAPPING_FLOOR = 583;

  @Autowired private WebApplicationContext context;

  @Autowired private FilterChainProxy springSecurityFilterChain;

  /** Asserts CSRF is armed on the main chain, so no check below is vacuous. */
  @BeforeEach
  void csrfIsArmed() {
    assertThat(
            springSecurityFilterChain.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .filter(CsrfFilter.class::isInstance)
                .count())
        .as("CSRF must be armed in this context, or every check below is vacuous")
        .isPositive();
  }

  /**
   * Sends a token-less, cookie-less write through the CSRF filter of the chain that serves it,
   * chosen the way {@link FilterChainProxy} chooses.
   *
   * @param path the request path
   * @return {@code true} when that chain's CSRF filter, if any, let the request through
   * @throws Exception when the filter fails
   */
  private boolean passesWithoutToken(@NotNull String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), path);
    request.setRequestURI(path);
    SecurityFilterChain chain =
        springSecurityFilterChain.getFilterChains().stream()
            .filter(candidate -> candidate.matches(request))
            .findFirst()
            .orElseThrow();
    Filter csrf =
        chain.getFilters().stream().filter(CsrfFilter.class::isInstance).findFirst().orElse(null);
    if (csrf == null) {
      return true;
    }
    AtomicBoolean passed = new AtomicBoolean();
    csrf.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(true));
    return passed.get();
  }

  @Test
  @DisplayName("every write mapping lies inside the CSRF exemption of the real filter")
  void everyWriteMappingIsCsrfExempt() throws Exception {
    List<Endpoint> writes =
        PathControlInventory.everyRegistry(context).stream().filter(Endpoint::isWrite).toList();
    List<String> refused = new ArrayList<>();
    for (Endpoint write : writes) {
      if (REVIEWED_OUTSIDE_EXEMPTION.containsKey(write.pattern())) {
        continue;
      }
      if (!passesWithoutToken(write.concretePath())) {
        refused.add(write.toString());
      }
    }

    assertThat(refused)
        .as(
            "a bearer-only write outside the CSRF exemption is refused with 403 in production while"
                + " every test passes, because the test profile disables CSRF. Keep it under"
                + " /api/v1 or /internal, or extend SecurityConfig.CSRF_EXEMPT_PATHS (ADR-0144)")
        .isEmpty();
    assertThat(writes.size())
        .as("write mappings found")
        .isGreaterThanOrEqualTo(WRITE_MAPPING_FLOOR);
  }

  @Test
  @DisplayName("the real filter refuses a planted write outside the exemption")
  void thePlantedWriteIsRefused() throws Exception {
    assertThat(passesWithoutToken("/legacy/planted-write")).isFalse();
    assertThat(passesWithoutToken("/api/v2/planted-write")).isFalse();
    assertThat(passesWithoutToken("/api/v1/missions")).isTrue();
  }

  @Test
  @DisplayName("no mapping exists outside /api, /internal, the actuator and the framework pages")
  void noMappingOutsideTheSurface() {
    List<Endpoint> endpoints = PathControlInventory.everyRegistry(context);

    assertThat(endpoints.size()).as("mappings found").isGreaterThanOrEqualTo(MAPPING_FLOOR);
    assertThat(PathControlInventory.outsideSurface(endpoints, SURFACE_ROOTS, SURFACE_EXTRAS))
        .as(
            "a mapping outside the served surface escapes the CSRF exemption, the API vhost's"
                + " rules and every /api-scoped filter")
        .isEmpty();
  }

  @Test
  @DisplayName("the reviewed exceptions are mappings that still exist")
  void theReviewedExceptionsStillExist() {
    assertThat(
            PathControlInventory.unmappedPaths(
                REVIEWED_OUTSIDE_EXEMPTION.keySet(), PathControlInventory.everyRegistry(context)))
        .isEmpty();
  }
}
