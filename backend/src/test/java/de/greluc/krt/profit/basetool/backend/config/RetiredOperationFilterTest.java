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

import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests the {@code APP_UPDATE_REQUIRED} answer of a retired operation (REQ-API-020) with a
 * test-only entry: the exact verb and path answer {@code 410}, everything else passes through
 * untouched.
 */
class RetiredOperationFilterTest {

  /** A test-only retired operation; no such path has ever existed. */
  private static final String RETIRED = "GET /api/v1/retired-for-test/{id}";

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  /**
   * Builds the filter over the given list.
   *
   * @param retired the retired operations
   * @return the filter
   */
  RetiredOperationFilter filter(RetiredOperations retired) {
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.app_update_required.title", Locale.ENGLISH, "Update required");
    messages.addMessage("problem.app_update_required.detail", Locale.ENGLISH, "Install it.");
    return new RetiredOperationFilter(
        retired,
        messages,
        new ProblemResponseFactory(BoundProperties.defaults(AppProblemProperties.class)),
        new ObjectMapper(),
        meterRegistry);
  }

  /**
   * Runs one request through the filter.
   *
   * @param filter the filter
   * @param method the verb
   * @param uri the request URI
   * @param chain the chain behind the filter
   * @return the response
   * @throws Exception if the filter throws
   */
  static MockHttpServletResponse run(
      RetiredOperationFilter filter, String method, String uri, MockFilterChain chain)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.addPreferredLocale(Locale.ENGLISH);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  @DisplayName("a retired operation answers 410 with the APP_UPDATE_REQUIRED problem")
  void aRetiredOperationAnswersTheWall() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response =
        run(
            filter(RetiredOperations.parse(List.of(RETIRED))),
            "GET",
            "/api/v1/retired-for-test/0b3c",
            chain);

    assertThat(chain.getRequest()).as("the request must not reach anything behind").isNull();
    assertThat(response.getStatus()).isEqualTo(410);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String content = response.getContentAsString();
    JsonNode body = new ObjectMapper().readTree(content);
    assertThat(content).contains("\"code\":\"APP_UPDATE_REQUIRED\"");
    assertThat(body.path("status").asInt()).isEqualTo(410);
    assertThat(body.path("type").asString()).endsWith("app-update-required");
    assertThat(body.path("title").asString()).isEqualTo("Update required");
    String correlationId = response.getHeader(RetiredOperationFilter.CORRELATION_ID_HEADER);
    assertThat(correlationId).isNotBlank();
    assertThat(content).contains("\"correlationId\":\"" + correlationId + "\"");
    assertThat(
            meterRegistry
                .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, "APP_UPDATE_REQUIRED")
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  @DisplayName("another verb, a longer path or a sibling passes through untouched")
  void everythingElsePassesThrough() throws Exception {
    RetiredOperationFilter filter = filter(RetiredOperations.parse(List.of(RETIRED)));
    for (String[] call :
        List.of(
            new String[] {"POST", "/api/v1/retired-for-test/0b3c"},
            new String[] {"HEAD", "/api/v1/retired-for-test/0b3c"},
            new String[] {"GET", "/api/v1/retired-for-test/0b3c/more"},
            new String[] {"GET", "/api/v1/retired-for-test"},
            new String[] {"GET", "/api/v1/app/version-policy"})) {
      MockFilterChain chain = new MockFilterChain();
      MockHttpServletResponse response = run(filter, call[0], call[1], chain);
      assertThat(chain.getRequest()).as("%s %s must pass", call[0], call[1]).isNotNull();
      assertThat(response.getStatus()).isEqualTo(200);
    }
    assertThat(meterRegistry.find(MetricNames.HTTP_ERROR).counters()).isEmpty();
  }

  @Test
  @DisplayName("an empty list skips the filter entirely")
  void anEmptyListSkipsTheFilter() throws Exception {
    RetiredOperationFilter filter = filter(RetiredOperations.none());
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/retired-for-test/0b3c");

    assertThat(filter.shouldNotFilter(request)).isTrue();
    MockFilterChain chain = new MockFilterChain();
    run(filter, "GET", "/api/v1/retired-for-test/0b3c", chain);
    assertThat(chain.getRequest()).isNotNull();
  }
}
