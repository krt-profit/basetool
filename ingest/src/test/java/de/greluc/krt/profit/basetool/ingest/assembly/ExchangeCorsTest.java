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

package de.greluc.krt.profit.basetool.ingest.assembly;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.ingest.edge.CorrelationIdFilter;
import de.greluc.krt.profit.basetool.ingest.edge.RequestLoggingFilter;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeIdempotency;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import org.hamcrest.Matchers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Browser-extension CORS through the gateway's filter chain: approved extension origins only, on
 * {@code /exchange/**} only, never with credentials (REQ-XCH-027, ADR-0303).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ExchangeTestSupport.ProbeRoutes.class)
class ExchangeCorsTest {

  /** The approved Chromium extension's origin, as the test profile configures it. */
  private static final String CHROME = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";

  private static final String FIREFOX = "moz-extension://0b5c4a52-7f5e-4f0a-9d3e-2c1b0a9f8e7d";
  private static final String SHIP_CHANGES = "/exchange/v1/me/ships/changes";

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;
  @MockitoBean private ExchangeQuotas quotas;
  @MockitoBean private ExchangeIdempotency idempotency;
  @MockitoBean private ExchangeBudget budget;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                context.getBean(CorrelationIdFilter.class),
                context.getBean(RequestLoggingFilter.class))
            .apply(springSecurity())
            .build();
  }

  @Test
  void anApprovedChromiumExtensionPassesThePreflightOfAWrite() throws Exception {
    preflight(SHIP_CHANGES, CHROME, "POST")
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CHROME))
        .andExpect(
            header()
                .string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, Matchers.containsString("POST")))
        .andExpect(
            header()
                .string(
                    HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                    Matchers.allOf(
                        Matchers.containsStringIgnoringCase("dpop"),
                        Matchers.containsStringIgnoringCase("idempotency-key"),
                        Matchers.containsStringIgnoringCase("exchange-client-agent"))))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  @Test
  void anyFirefoxExtensionInstallationPassesThePreflightOfARead() throws Exception {
    preflight("/exchange/v1/me/ships", FIREFOX, "GET")
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FIREFOX));
  }

  @Test
  void anUnapprovedExtensionOrAWebsiteIsRefused() throws Exception {
    preflight(SHIP_CHANGES, "chrome-extension://cccccccccccccccccccccccccccccccc", "POST")
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    preflight(SHIP_CHANGES, "https://profit-base.online", "POST")
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  @Test
  void anApprovedExtensionIsRefusedOutsideTheExchange() throws Exception {
    preflight("/v1/refinery-orders", CHROME, "POST")
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  @Test
  void aRefusedRequestStillShowsTheExtensionItsNonceAndChallenge() throws Exception {
    mockMvc
        .perform(get("/exchange/v1").header(HttpHeaders.ORIGIN, CHROME))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CHROME))
        .andExpect(
            header()
                .string(
                    HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                    Matchers.allOf(
                        Matchers.containsString("DPoP-Nonce"),
                        Matchers.containsString("WWW-Authenticate"),
                        Matchers.containsString("RateLimit-Policy"),
                        Matchers.containsString("Retry-After"))))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  /**
   * Sends a CORS preflight.
   *
   * @param path the request path
   * @param origin the {@code Origin}
   * @param method the method the preflight asks for
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions preflight(
      @NotNull String path, @NotNull String origin, @NotNull String method) throws Exception {
    return mockMvc.perform(
        options(path)
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
            .header(
                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                "authorization,dpop,content-type,idempotency-key,exchange-client-agent"));
  }
}
