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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The frontend half of REQ-SEC-052: <b>no page renders for a visitor without a session</b>.
 *
 * <p>Enumerates every MVC mapping the dispatcher knows and issues each one anonymously, rather than
 * asserting the handful of routes somebody thought to write a case for. A page added next month is
 * covered on the day it is added.
 *
 * <p><strong>Two shapes per read, because the frontend answers them differently</strong> — and the
 * difference is the whole of REQ-SEC-012:
 *
 * <ul>
 *   <li><b>Navigation</b> (an HTML {@code Accept}): a redirect into the OAuth2 entry point, so the
 *       member lands where they were going after signing in.
 *   <li><b>Background</b> ({@code Accept: application/json} — the shape {@code krtFetch} uses for
 *       {@code /catalog/**}, {@code /csrf} and every {@code ?fragment=…} refetch): {@code 401} with
 *       {@code X-Reauthenticate}, so the page can re-authenticate in place instead of replacing a
 *       half-filled form with a login screen.
 * </ul>
 *
 * <p>Every write is issued <b>with</b> a CSRF token. Without one the {@code CsrfFilter} answers
 * {@code 403} before the authorisation decision is reached — which looks like a refusal, passes a
 * naive assertion, and would hide a {@code 2xx} underneath. The sweep therefore fails on {@code
 * 2xx} and on {@code 403} alike: only a redirect or a {@code 401} proves the gate answered.
 *
 * <p>Path variables are filled with a nil-shaped UUID and, where the segment is not an id, with
 * {@code x}. A refusal never depends on the row existing.
 */
@SpringBootTest
class AnonymousSurfaceSweepMvcTest {

  /** A well-formed id that matches nothing. */
  private static final String NIL_UUID = "00000000-0000-4000-8000-000000000000";

  /**
   * The paths REQ-SEC-052 serves without a session.
   *
   * <p>{@code /error} is excluded from the sweep rather than listed here: a direct {@code GET} with
   * no error attribute resolves to a {@code 500}, which is its correct contract and not something
   * this sweep can express. {@code SecurityConfigStaticAssetPermitAllTest} owns the asset paths and
   * their "must not redirect" contract, which is a different assertion from "must not serve data".
   */
  private static final Set<String> PUBLIC_PAGES = Set.of("/", "/impressum", "/privacy", "/terms");

  /**
   * Public, but not a page — so the navigation shape does not apply to it.
   *
   * <p>Android App Links verification is fetched by the platform with no session and no browser
   * (REQ-SEC-038). It must answer {@code 200} with {@code application/json} and MUST NOT redirect:
   * behind the catch-all it answered {@code 302} into the OAuth2 entry point, verification failed,
   * and the login callback opened in the browser instead of the app — the member landed on the 404
   * page mid-login. Asked for as HTML it answers {@code 500}, which is why it is swept in the
   * background shape only.
   */
  private static final Set<String> PUBLIC_RESOURCES = Set.of("/.well-known/assetlinks.json");

  /**
   * <b>The enumeration below is duplicated in the sibling sweep of the other module</b> ({@code
   * AnonymousSurfaceSweepTest}). Deliberately, for now: the two live in different Gradle modules
   * and sharing would need a test-support module, while their assertions must stay separate — this
   * one asks what a caller who is not a member may reach, the other what a navigation and a
   * background call get. Tracked as <a
   * href="https://github.com/krt-profit/basetool/issues/1804">#1804</a> rather than left as a thing
   * somebody notices twice.
   */
  /**
   * Mappings this sweep does not own, matched as whole path segments rather than as string
   * prefixes.
   *
   * <p>The distinction is the backend sweep's lesson (2026-09-06): compared with {@code
   * startsWith}, an entry silently claims every sibling that happens to share its opening
   * characters, and an exclusion that over-reaches inside a sweep whose whole value is
   * exhaustiveness removes exactly the coverage nobody will notice missing.
   */
  private static final List<String> NOT_SWEPT =
      List.of("/error", "/actuator", "/oauth2", "/login", "/logout", "/csrf");

  @Autowired private WebApplicationContext context;

  /**
   * Mocked so the sweep exercises the security chain rather than the backend.
   *
   * <p>Every refusal here happens before a handler runs, so no call should reach this bean at all —
   * and a stubbed client that returns {@code null} makes that visible as a clean refusal instead of
   * as a connection error, which would look like the same failure for a different reason.
   */
  @MockitoBean
  private de.greluc.krt.profit.basetool.frontend.service.BackendApiClient backendApiClient;

  /** The frontend is an OAuth2 client; the registry is what the entry point redirects through. */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * One (verb, path) pair to issue.
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
   * Expands every mapping into concrete calls, in a stable order.
   *
   * @return the calls to sweep
   */
  private List<Call> allCalls() {
    Set<Call> calls = new LinkedHashSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      Set<String> patterns = new TreeSet<>();
      if (info.getPathPatternsCondition() != null) {
        info.getPathPatternsCondition()
            .getPatterns()
            .forEach(pattern -> patterns.add(pattern.getPatternString()));
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
   * @param pattern the mapping's path pattern
   * @return the concrete path, or {@code null} when it cannot be made concrete
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
   * Issues one call anonymously.
   *
   * @param call the call to issue
   * @param accept the {@code Accept} header — the navigation / background distinction
   * @return the response status; a template that threw counts as served
   * @throws Exception when the request could not be performed
   */
  private int issue(Call call, MediaType accept) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(call.method(), call.path()).accept(accept).with(csrf());
    if (call.method() != HttpMethod.GET) {
      request = request.contentType(MediaType.APPLICATION_JSON).content("{}");
    }
    try {
      return mockMvc.perform(request).andReturn().getResponse().getStatus();
    } catch (Exception renderFailure) {
      // A template that threw is a template that RAN, which means the security chain did not
      // refuse the request — so this counts as served, not as an incidental test failure. Reported
      // as 200 so it lands in the violation list with its path rather than aborting the sweep at
      // the first one and hiding every path after it.
      return 200;
    }
  }

  @Test
  @DisplayName("the sweep sees the whole dispatcher")
  void theSweepEnumeratesTheWholeFrontend() {
    Assertions.assertThat(allCalls())
        .as("mappings the dispatcher knows — a near-empty sweep passes for the wrong reason")
        .hasSizeGreaterThan(100);
  }

  @Test
  @DisplayName("an anonymous navigation is sent to the login, and never served")
  void navigationIsSentToTheLogin() throws Exception {
    List<String> served = new ArrayList<>();
    for (Call call : allCalls()) {
      if (call.method() != HttpMethod.GET) {
        continue;
      }
      if (PUBLIC_RESOURCES.contains(call.path())) {
        continue;
      }
      if (PUBLIC_PAGES.contains(call.path())) {
        // NOT through issue(): that reports a render failure as 200 so a template which threw
        // still counts as "served" for the refusal check below. Applied to a public page it turns
        // the assertion inside out - the page that could not render passes as the page that must.
        // Four of them did, because BackendApiClient is a @MockitoBean and terms.html evaluated
        // ${terms.title} on the null it returned. Here the exception is the failure.
        try {
          int rendered =
              mockMvc
                  .perform(
                      MockMvcRequestBuilders.request(call.method(), call.path())
                          .accept(MediaType.TEXT_HTML)
                          .with(csrf()))
                  .andReturn()
                  .getResponse()
                  .getStatus();
          if (rendered != 200) {
            served.add(call + " -> " + rendered + " (a REQ-SEC-052 public page must render)");
          }
        } catch (Exception renderFailure) {
          served.add(
              call
                  + " -> threw during rendering ("
                  + renderFailure.getClass().getSimpleName()
                  + ") — a REQ-SEC-052 public page must render");
        }
        continue;
      }
      int status = issue(call, MediaType.TEXT_HTML);
      // 3xx is the redirect into the OAuth2 entry point; 4xx is any refusal. A 2xx is a page
      // rendered for somebody with no session, which is what this whole change removes.
      if (status < 300 || (status >= 300 && status < 400 && !isLoginRedirect(call))) {
        served.add(call + " -> " + status);
      }
    }
    Assertions.assertThat(served)
        .as(
            "REQ-SEC-052: every page outside the four public ones must send an anonymous navigation"
                + " into the OAuth2 entry point. A 2xx here is a page served to the internet.")
        .isEmpty();
  }

  /**
   * Whether the redirect this call produced points at the login.
   *
   * <p>Re-issued rather than threaded through, because the assertion above reads better as a status
   * check with one named exception than as a tuple.
   *
   * @param call the call to re-issue
   * @return {@code true} when the redirect target is the OAuth2 entry point
   * @throws Exception when the request could not be performed
   */
  private boolean isLoginRedirect(Call call) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(call.method(), call.path())
            .accept(MediaType.TEXT_HTML)
            .with(csrf());
    String location = mockMvc.perform(request).andReturn().getResponse().getRedirectedUrl();
    return location != null && location.contains("/oauth2/authorization/keycloak");
  }

  @Test
  @DisplayName("an anonymous background call answers 401, never the payload")
  void backgroundCallsAnswer401() throws Exception {
    List<String> served = new ArrayList<>();
    for (Call call : allCalls()) {
      if (call.method() == HttpMethod.GET
          && (PUBLIC_PAGES.contains(call.path()) || PUBLIC_RESOURCES.contains(call.path()))) {
        continue;
      }
      int status = issue(call, MediaType.APPLICATION_JSON);
      // 403 fails too: a CSRF token rides on every request here, so a 403 would mean the
      // authorisation decision was never reached — and a refusal nobody made is not a refusal.
      if (status < 400 || status == 403) {
        served.add(call + " -> " + status);
      }
    }
    Assertions.assertThat(served)
        .as(
            "REQ-SEC-052 / REQ-SEC-012: a background call from an expired session must answer 401"
                + " so the page can re-authenticate in place. A 2xx is data served without a"
                + " session; a 403 means the CSRF filter answered before the gate did.")
        .isEmpty();
  }

  /**
   * Whether a mapping path lies at or under an excluded root, comparing whole path segments.
   *
   * @param path the substituted mapping path
   * @param root an entry of {@link #NOT_SWEPT}, without a trailing slash
   * @return {@code true} for the root itself and anything below it, {@code false} for a sibling
   *     that merely shares its opening characters
   */
  private static boolean isUnder(String path, String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }
}
