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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The sweep REQ-SEC-052 is written against: <b>no mapping answers a caller who is not a member</b>.
 *
 * <p><strong>Why an enumeration and not a list of paths.</strong> Every other test in this
 * repository asserts something about an endpoint somebody thought of. This one asks the dispatcher
 * for <em>every</em> mapping it knows and issues each one, so a path added next month is covered on
 * the day it is added rather than on the day somebody remembers to add an assertion for it. That is
 * the difference between "the tests we have still pass" and "no path exists that the tests do not
 * know about", and the members-only change is only worth making if the second one holds.
 *
 * <p>Three passes, because there are three ways to be not-a-member:
 *
 * <ol>
 *   <li><b>No token at all.</b> Everything answers {@code 401} except the four paths REQ-SEC-052
 *       names. Every {@code GET} is additionally asked with {@code HEAD}: the two anonymous reads
 *       are {@code GET}-scoped and Spring Security compares the verb with {@code String.equals}, so
 *       a {@code HEAD} falls to the catch-all — the REQ-SEC-032 lesson, which cost a live leak when
 *       a tightening was method-scoped above an all-verb {@code permitAll}.
 *   <li><b>A token whose registration is still PENDING.</b> {@code 403 PENDING_APPROVAL} on every
 *       {@code /api} mapping except the registration status read and the two anonymous ones.
 *   <li><b>A token that maps to no application role.</b> {@code 403 NO_ROLE} (REQ-SEC-053), same
 *       exemptions. This is the pass that would have been impossible before ADR-0159: such a token
 *       was mapped onto the authority-less {@code GUEST} role and simply used the anonymous
 *       surface.
 * </ol>
 *
 * <p><strong>What "answers" means here.</strong> The assertion is on the status, never on the body,
 * and the bar is deliberately low: nothing below {@code 400}. A {@code 404} or {@code 405} is a
 * refusal too — the path exists but the request cannot reach data — and demanding a specific
 * refusal code per mapping would make the sweep a transcription of the matrix rather than a check
 * on it. What must never happen is a {@code 2xx} or a {@code 3xx}, because both mean the request
 * was served.
 *
 * <p>Path variables are filled with a nil-shaped UUID and, where the segment is not a UUID, with
 * {@code x}. Method security runs before the controller body, so a refusal never depends on the row
 * existing; a sweep that needed seeded data would assert the seed as much as the rule.
 */
@SpringBootTest
class AnonymousSurfaceSweepTest {

  /** A well-formed id that matches nothing. Authorisation is decided before the lookup. */
  private static final String NIL_UUID = "00000000-0000-4000-8000-000000000000";

  /**
   * The mappings REQ-SEC-052 serves without a token, with the status each must answer.
   *
   * <p>Four entries and no more. {@code /error} is Spring's own dispatch and carries no data — it
   * is listed for completeness but excluded from the sweep, because a direct {@code GET} of it
   * answers {@code 500} by design (see {@link #NOT_SWEPT}); {@code /actuator/health} is the Docker
   * {@code HEALTHCHECK} (in production it lives on the internal management port instead, pinned by
   * {@code ManagementPortIsolationTest}); the two {@code /api/v1} reads are D2 and D3 of ADR-0159 —
   * an app too old to log in must still learn that it is too old, and a document everyone must be
   * able to read before agreeing to anything cannot require having agreed.
   */
  private static final Set<String> ANONYMOUS_OK =
      Set.of(
          "GET /api/v1/app/version-policy",
          "GET /api/v1/terms/document",
          "GET /actuator/health",
          "GET /error");

  /**
   * Mappings excluded from the sweep because they are not a caller-facing surface.
   *
   * <p>Two entries, and both of them are things this sweep cannot express rather than things it
   * chooses not to look at. {@code /internal/**} is machine-to-machine behind a constant-time
   * shared-secret header and is {@code permitAll} at the URL layer on purpose (REQ-SEC-022) — the
   * controller does its own refusing, which is a different assertion belonging to a different test.
   * A direct {@code GET /error} with no error attribute resolves to a {@code 500}, which is its
   * correct contract and not something a "must answer / must refuse" sweep has a verdict for.
   *
   * <p><b>{@code /actuator} used to stand here too, and that was one exclusion too many.</b> Two of
   * the four {@code ANONYMOUS_OK} entries were {@code /actuator/health} and {@code /error} — both
   * excluded, so the list of paths that must answer anonymously was half a list nothing checked.
   * The actuator tree is swept now: {@code health} answers, everything else refuses, and the
   * production management-port isolation that the Javadoc used to cite as the reason for skipping
   * it is a different property, pinned by {@code ManagementPortIsolationTest}.
   *
   * <p><b>Matched as a path segment, not as a string prefix.</b> An exclusion inside a sweep whose
   * whole value is exhaustiveness must not be able to swallow a neighbour it was never meant to
   * name. {@code /v3/api-docs} used to stand here and, compared with {@code startsWith}, took
   * springdoc's second spelling {@code /v3/api-docs.yaml} with it — which was the one the {@code
   * ROLE_ADMIN} matcher's {@code **} did not cover either, so the only path on the whole surface
   * that was silently weaker than its own comment was also the only one this sweep could not see.
   * The document is swept under both spellings now; the entry is gone rather than made exact.
   */
  private static final List<String> NOT_SWEPT = List.of("/internal", "/error");

  /**
   * The three {@code /api} paths a refused-but-authenticated caller may still reach.
   *
   * <p>The registration status is how the frontend routes a pending member to the waiting page;
   * without it the refusal would have no way of explaining itself. The two anonymous reads are
   * exempt because the Android app attaches its bearer to every call once a session exists, so
   * gating them here would refuse the version policy and the terms text to exactly the callers most
   * likely to need both (D2/D3).
   */
  private static final Set<String> REACHABLE_WHILE_REFUSED =
      Set.of(
          "/api/v1/users/me/registration-status",
          "/api/v1/app/version-policy",
          "/api/v1/terms/document");

  @Autowired private WebApplicationContext context;

  /**
   * The MVC mapping registry, named explicitly.
   *
   * <p>By type alone this is ambiguous: Actuator contributes a second {@code
   * RequestMappingHandlerMapping} ({@code controllerEndpointHandlerMapping}) and the context fails
   * to inject. The application's own mappings are the subject here — the actuator tree is gated by
   * the management-port configuration, which this application-connector context does not model.
   */
  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * One (verb, path) pair to issue, expanded from a {@link RequestMappingInfo}.
   *
   * @param method the HTTP verb
   * @param path the concrete path, with every variable substituted
   */
  private record Call(HttpMethod method, String path) {

    @Override
    public String toString() {
      return method.name() + " " + path;
    }
  }

  /**
   * Expands every mapping the dispatcher knows into concrete (verb, path) calls.
   *
   * <p>A mapping with no declared verb (rare, but legal) is issued as {@code GET}: it answers every
   * verb, so the read is the one that would leak.
   *
   * @return every call to sweep, in a stable order
   */
  private List<Call> allCalls() {
    Set<Call> calls = new LinkedHashSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      Set<String> patterns = new TreeSet<>();
      if (info.getPathPatternsCondition() != null) {
        info.getPathPatternsCondition()
            .getPatterns()
            .forEach(p -> patterns.add(p.getPatternString()));
      }
      Set<HttpMethod> verbs = new LinkedHashSet<>();
      info.getMethodsCondition().getMethods().forEach(m -> verbs.add(HttpMethod.valueOf(m.name())));
      if (verbs.isEmpty()) {
        verbs.add(HttpMethod.GET);
      }
      for (String pattern : patterns) {
        String path = substituteVariables(pattern);
        if (path == null || NOT_SWEPT.stream().anyMatch(p -> isUnder(path, p))) {
          continue;
        }
        for (HttpMethod verb : verbs) {
          calls.add(new Call(verb, path));
        }
      }
    }
    List<Call> ordered = new ArrayList<>(calls);
    ordered.sort((a, b) -> a.toString().compareTo(b.toString()));
    return ordered;
  }

  /**
   * Replaces every {@code {name}} segment with a value the binder accepts.
   *
   * <p>A nil UUID for anything whose name reads like an id, {@code x} otherwise. A pattern carrying
   * a wildcard ({@code **}) or a regex constraint is skipped: it has no single concrete spelling,
   * and guessing one would assert a path the application never routes.
   *
   * @param pattern the mapping's path pattern
   * @return the concrete path, or {@code null} when the pattern cannot be made concrete
   */
  private static String substituteVariables(String pattern) {
    if (pattern.contains("**") || pattern.contains(":")) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < pattern.length()) {
      char c = pattern.charAt(i);
      if (c != '{') {
        out.append(c);
        i++;
        continue;
      }
      int close = pattern.indexOf('}', i);
      if (close < 0) {
        return null;
      }
      String name = pattern.substring(i + 1, close).toLowerCase(Locale.ROOT);
      out.append(name.endsWith("id") || name.equals("uuid") ? NIL_UUID : "x");
      i = close + 1;
    }
    return out.toString();
  }

  /**
   * Issues one call, with a body and CSRF token where the verb needs them.
   *
   * <p>{@code .with(csrf())} on every write so the authorisation decision is what is observed
   * rather than the {@code CsrfFilter}'s own refusal — a 403 from CSRF would hide a 2xx from the
   * gate. An empty JSON object is enough for every write here: validation runs after authorisation,
   * so a rejected body still proves the gate answered first.
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
   * Pass 1b: every {@code GET} asked again with {@code HEAD}.
   *
   * <p>Its own test because its failure mode is specific: the two anonymous reads are {@code
   * GET}-scoped, so their {@code HEAD} must fall to the authenticated catch-all and answer {@code
   * 401}. Spring MVC otherwise answers {@code HEAD} from the {@code @GetMapping} handler, which is
   * how a method-scoped tightening above an all-verb {@code permitAll} once leaked a response's
   * {@code Content-Length} on the two paths REQ-SEC-032 exists to close.
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
   * The OpenAPI document requires {@code ROLE_ADMIN} under <b>both</b> spellings springdoc
   * registers for it.
   *
   * <p>Its own test because the three passes above cannot see this one. They ask what a caller who
   * is <em>not a member</em> may reach, and the document was already behind {@code
   * anyRequest().authenticated()} for such a caller under either spelling — so an anonymous sweep
   * stays green whether the {@code ROLE_ADMIN} matcher covers {@code /v3/api-docs.yaml} or not, and
   * passes 2 and 3 skip it outright because it is not an {@code /api} path. The question this asks
   * is the one that was actually wrong: whether an ordinary <em>member</em> can read the most
   * efficient description of the attack surface the project can produce. Until 2026-09-06 they
   * could, through the YAML spelling, because {@code **} spans whole segments.
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

  /**
   * Whether a mapping path lies at or under an excluded root, comparing whole path segments.
   *
   * @param path the substituted mapping path, e.g. {@code /actuator/health} or {@code
   *     /v3/api-docs.yaml}
   * @param root an entry of {@link #NOT_SWEPT}, without a trailing slash
   * @return {@code true} for the root itself and anything below it, {@code false} for a sibling
   *     that merely shares its opening characters
   */
  private static boolean isUnder(String path, String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }
}
