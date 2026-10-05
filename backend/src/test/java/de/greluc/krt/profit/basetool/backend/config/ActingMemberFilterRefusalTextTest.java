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
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.exchange.api.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests that a refusal on the exchange layer speaks of an exchange request and one elsewhere says
 * that acting for a member is only possible through the exchange routes, in both languages, with
 * the shipped message bundles.
 */
class ActingMemberFilterRefusalTextTest {

  private static final String MEMBER = "44444444-4444-4444-4444-444444444444";

  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Builds the filter over the real {@code messages} bundles.
   *
   * @return the filter
   */
  private ActingMemberFilter filter() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasename("messages");
    messages.setFallbackToSystemLocale(false);
    return new ActingMemberFilter(
        new IngestGatewayProperties(List.of("test-ingest-gateway")),
        mock(ActingMemberAuthorities.class),
        messages,
        new ProblemResponseFactory(BoundProperties.defaults(AppProblemProperties.class)),
        objectMapper,
        new SimpleMeterRegistry());
  }

  /**
   * A gateway request naming a member, without any exchange relay header.
   *
   * @param uri the request path
   * @param locale the caller's language
   * @return the request
   */
  private static MockHttpServletRequest gatewayRequest(String uri, Locale locale) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.addPreferredLocale(locale);
    request.addHeader(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER);
    Jwt token =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("55555555-5555-5555-5555-555555555555")
            .claim("azp", "test-ingest-gateway")
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(token, List.of()));
    return request;
  }

  /**
   * Runs the filter and parses the problem it wrote.
   *
   * @param request the request to refuse
   * @return the problem document
   * @throws Exception when the filter or the parse fails
   */
  private JsonNode refuse(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter().doFilter(request, response, mock(FilterChain.class));
    assertThat(response.getStatus()).isEqualTo(403);
    return objectMapper.readTree(response.getContentAsByteArray());
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** An exchange request without a client is refused as an unattributed exchange request. */
  @Test
  void anExchangeRefusalSpeaksOfAnExchangeRequest() throws Exception {
    JsonNode problem = refuse(gatewayRequest("/api/v1/exchange/me/stock", Locale.ENGLISH));

    assertThat(problem.get("title").asString()).isEqualTo("Request not attributed");
    assertThat(problem.get("detail").asString())
        .contains("exchange request")
        .doesNotContain("import");
  }

  /** The German exchange refusal speaks of an exchange request too. */
  @Test
  void theGermanExchangeRefusalSpeaksOfAnExchangeRequest() throws Exception {
    JsonNode problem = refuse(gatewayRequest("/api/v1/exchange/me/stock", Locale.GERMAN));

    assertThat(problem.get("detail").asString())
        .contains("Austausch-Anfrage")
        .doesNotContain("Import");
  }

  /** An exchange path the relay does not serve still gets the exchange wording. */
  @Test
  void anUnlistedExchangePathGetsTheExchangeWording() throws Exception {
    JsonNode problem = refuse(gatewayRequest("/api/v1/exchange/me/unknown", Locale.ENGLISH));

    assertThat(problem.get("detail").asString()).contains("exchange request");
  }

  /** A refusal outside the exchange layer says that acting for a member needs the exchange. */
  @Test
  void aRefusalOutsideTheExchangePointsToTheExchangeRoutes() throws Exception {
    MockHttpServletRequest request =
        gatewayRequest("/api/v1/refinery-orders/import-extract", Locale.ENGLISH);
    request.addHeader(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, "versekit");

    JsonNode problem = refuse(request);

    assertThat(problem.get("title").asString()).isEqualTo("Only through the exchange routes");
    assertThat(problem.get("detail").asString())
        .isEqualTo(
            "The request was refused. Acting for a member is only possible through the exchange"
                + " routes.")
        .doesNotContain("import");
  }

  /** The German refusal outside the exchange layer says the same. */
  @Test
  void theGermanRefusalOutsideTheExchangePointsToTheExchangeRoutes() throws Exception {
    JsonNode problem =
        refuse(gatewayRequest("/api/v1/refinery-orders/import-extract", Locale.GERMAN));

    assertThat(problem.get("title").asString()).isEqualTo("Nur \u00fcber die Austausch-Routen");
    assertThat(problem.get("detail").asString())
        .contains("nur \u00fcber die Austausch-Routen m\u00f6glich")
        .doesNotContain("Import");
  }
}
