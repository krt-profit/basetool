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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import de.greluc.krt.profit.basetool.testsupport.web.Call;
import de.greluc.krt.profit.basetool.testsupport.web.EndpointEnumeration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Sweeps every mapping the dispatcher knows and asserts that none serves a caller who is not a
 * member (REQ-SEC-052).
 *
 * <p>Three passes: no token ({@code 401} except the exempt paths, with every {@code GET} repeated
 * as {@code HEAD}), a pending registration ({@code PENDING_APPROVAL}) and a token without an
 * application role ({@code NO_ROLE}, REQ-SEC-053). Any status below {@code 400} fails; path
 * variables are filled with placeholders, so no seeded data is needed.
 */
@SpringBootTest
class AnonymousSurfaceSweepTest {

  /** The mappings REQ-SEC-052 serves without a token, with the status each must answer. */
  private static final Set<String> ANONYMOUS_OK =
      Set.of(
          "GET /api/v1/app/version-policy",
          "GET /api/v1/terms/document",
          "GET /actuator/health",
          "GET /error");

  /**
   * Path prefixes excluded from the sweep, matched per path segment: {@code /internal}, which is
   * guarded by its own shared-secret check (REQ-SEC-022), and {@code /error}, whose direct call
   * answers {@code 500} by contract.
   */
  private static final List<String> NOT_SWEPT = List.of("/internal", "/error");

  /**
   * The three {@code /api} paths a refused but authenticated caller may still reach: the
   * registration status and the two anonymous reads.
   */
  private static final Set<String> REACHABLE_WHILE_REFUSED =
      Set.of(
          "/api/v1/users/me/registration-status",
          "/api/v1/app/version-policy",
          "/api/v1/terms/document");

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Returns every call this sweep issues: all mappings from {@link EndpointEnumeration} minus
   * {@link #NOT_SWEPT}.
   *
   * @return every call to sweep, in a stable order
   */
  private List<Call> allCalls() {
    return EndpointEnumeration.mappings(context).stream()
        .filter(
            call ->
                NOT_SWEPT.stream()
                    .noneMatch(root -> EndpointEnumeration.isUnder(call.path(), root)))
        .toList();
  }

  /**
   * Issues one call, adding a CSRF token and an empty JSON body to writes so the authorization
   * decision is what gets observed.
   *
   * @param call the call to issue
   * @param principal a post-processor installing the caller, or {@code null} for no token
   * @return the completed exchange
   * @throws Exception when the request could not be performed
   */
  private MvcResult issue(
      Call call, org.springframework.test.web.servlet.request.RequestPostProcessor principal)
      throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(call.method(), call.path())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .with(csrf());
    if (principal != null) {
      request = request.with(principal);
    }
    return mockMvc.perform(request).andReturn();
  }

  /**
   * The sweep is only meaningful if it found the application.
   *
   * <p>A context that failed to register its mappings would make every pass below vacuously green.
   * The number is a floor, not an assertion about the exact API size.
   */
  @Test
  @DisplayName("the sweep sees the whole dispatcher")
  void theSweepEnumeratesTheWholeApi() {
    Assertions.assertThat(allCalls())
        .as("mappings the dispatcher knows — a near-empty sweep passes for the wrong reason")
        .hasSizeGreaterThan(200);
  }

  /** Pass 1: no token at all. */
  @Test
  @DisplayName("nothing answers a caller with no token, except the four public paths")
  void noMappingAnswersAnAnonymousCaller() throws Exception {
    List<String> served = new ArrayList<>();
    for (Call call : allCalls()) {
      int status = issue(call, null).getResponse().getStatus();
      boolean expectedPublic = ANONYMOUS_OK.contains(call.toString());
      if (expectedPublic) {
        if (status >= 400) {
          served.add(call + " -> " + status + " (a REQ-SEC-052 public path must answer)");
        }
        continue;
      }
      if (status < 400) {
        served.add(call + " -> " + status);
      }
    }
    Assertions.assertThat(served)
        .as(
            "REQ-SEC-052: every mapping outside the four public paths must refuse an anonymous"
                + " caller. A 2xx or 3xx here is a path that serves the internet.")
        .isEmpty();
  }

  /**
   * Pass 1b: every {@code GET} is repeated as {@code HEAD}, which must be refused wherever the
   * {@code GET} is and on the two {@code GET}-scoped anonymous reads (REQ-SEC-032).
   */
  @Test
  @DisplayName("a HEAD is refused wherever the GET is, and on the two GET-scoped reads too")
  void headIsRefusedEverywhere() throws Exception {
    List<String> served = new ArrayList<>();
    for (Call call : allCalls()) {
      if (call.method() != HttpMethod.GET) {
        continue;
      }
      Call head = new Call(HttpMethod.HEAD, call.path());
      int status = issue(head, null).getResponse().getStatus();
      boolean publicPath =
          ANONYMOUS_OK.contains("GET " + call.path()) && !call.path().startsWith("/api/");
      if (!publicPath && status < 400) {
        served.add(head + " -> " + status);
      }
    }
    Assertions.assertThat(served)
        .as(
            "A HEAD must be refused wherever its GET is. The two anonymous /api reads are"
                + " GET-scoped on purpose, so their HEAD answers 401.")
        .isEmpty();
  }

  /**
   * The OpenAPI document requires {@code ROLE_ADMIN} under both spellings springdoc registers,
   * including {@code /v3/api-docs.yaml}.
   *
   * @throws Exception when a request could not be performed
   */
  @Test
  @DisplayName("the OpenAPI document is ADMIN-only under both of springdoc's spellings")
  void theOpenApiDocumentIsAdminOnlyUnderBothSpellings() throws Exception {
    for (String path : List.of("/v3/api-docs", "/v3/api-docs.yaml")) {
      Call call = new Call(HttpMethod.GET, path);
      int asMember =
          issue(call, jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
              .getResponse()
              .getStatus();
      Assertions.assertThat(asMember)
          .as("%s must not answer an ordinary member — it enumerates the whole API", path)
          .isGreaterThanOrEqualTo(400);

      int asAdmin =
          issue(call, jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
              .getResponse()
              .getStatus();
      Assertions.assertThat(asAdmin)
          .as("%s must still be readable by an admin — the matcher gates it, not disables it", path)
          .isLessThan(400);
    }
  }

  /**
   * The exempt paths are not refused by the pending or the role-less gate; asserted on the refusal
   * code, since only those gates write {@code PENDING_APPROVAL} and {@code NO_ROLE}.
   *
   * @throws Exception when a request could not be performed
   */
  @Test
  @DisplayName("no exempt path is refused by the pending or the role-less gate")
  void theExemptPathsAreActuallyExempt() throws Exception {
    List<String> refused = new ArrayList<>();
    for (String path : new TreeSet<>(REACHABLE_WHILE_REFUSED)) {
      for (String marker : List.of("ROLE_PENDING_APPROVAL", "ROLE_NO_ROLE")) {
        String body =
            issue(
                    new Call(HttpMethod.GET, path),
                    jwt().authorities(new SimpleGrantedAuthority(marker)))
                .getResponse()
                .getContentAsString();
        if (body.contains("\"code\":\"PENDING_APPROVAL\"")
            || body.contains("\"code\":\"NO_ROLE\"")) {
          refused.add(path + " as " + marker);
        }
      }
    }

    Assertions.assertThat(refused)
        .as(
            "REACHABLE_WHILE_REFUSED is what passes 2 and 3 skip — a path listed here that the gate"
                + " still refuses is a path this sweep silently stopped covering")
        .isEmpty();
  }

  /** Pass 2: a bearer whose only authority is the pending marker. */
  @Test
  @DisplayName("a pending registration reaches nothing but its own status")
  void pendingRegistrationIsRefusedEverywhere() throws Exception {
    assertRefusedEverywhere(
        jwt().authorities(new SimpleGrantedAuthority("ROLE_PENDING_APPROVAL")),
        "REQ-SEC-017: a PENDING registration must be refused on every /api mapping except the"
            + " three it is allowed to read.");
  }

  /** Pass 3: a bearer that maps to no application role (REQ-SEC-053). */
  @Test
  @DisplayName("a role-less token reaches nothing but the three exempt reads")
  void roleLessTokenIsRefusedEverywhere() throws Exception {
    assertRefusedEverywhere(
        jwt().authorities(new SimpleGrantedAuthority("ROLE_NO_ROLE")),
        "REQ-SEC-053: a token carrying no application role must be refused on every /api mapping"
            + " except the three it is allowed to read.");
  }

  /**
   * Shared body of passes 2 and 3.
   *
   * @param principal the caller to install
   * @param because what a failure means
   * @throws Exception when a request could not be performed
   */
  private void assertRefusedEverywhere(
      org.springframework.test.web.servlet.request.RequestPostProcessor principal, String because)
      throws Exception {
    List<String> served = new ArrayList<>();
    for (Call call : allCalls()) {
      if (!call.path().startsWith("/api/")) {
        continue;
      }
      if (REACHABLE_WHILE_REFUSED.contains(call.path())) {
        continue;
      }
      int status = issue(call, principal).getResponse().getStatus();
      if (status < 400) {
        served.add(call + " -> " + status);
      }
    }
    Assertions.assertThat(served).as(because).isEmpty();
  }
}
