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

import de.greluc.krt.profit.basetool.testsupport.web.Call;
import de.greluc.krt.profit.basetool.testsupport.web.EndpointEnumeration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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

  /**
   * The paths REQ-SEC-052 serves without a session.
   *
   * <p>{@code /error} is excluded from the sweep rather than listed here: a direct {@code GET} with
   * no error attribute resolves to a {@code 500}, which is its correct contract and not something
   * this sweep can express. {@code SecurityConfigStaticAssetPermitAllTest} owns the asset paths and
   * their "must not redirect" contract, which is a different assertion from "must not serve data".
   */
  private static final Set<String> PUBLIC_PAGES =
      Set.of("/", "/impressum", "/privacy", "/terms", "/licenses", "/app/link-help");

  /**
   * Public, and a redirect — so neither of the two sets above describes it.
   *
   * <p>{@code /app/callback} is the Android App Link's web-side fallback (REQ-SEC-038). It is
   * reached only when the link did not resolve to the app, and it answers {@code 303} to {@link
   * #APP_LINK_HELP} so the authorization code in the query leaves the address bar, the history
   * entry and the {@code Referer} of the page that follows.
   *
   * <p>It needs its own branch rather than an entry in either set: the sweep's default expectation
   * for a {@code 3xx} is the OAuth2 entry point, which this must never be — that redirect is the
   * loop this path exists to break, and it would also put the callback URL into {@code
   * HttpSessionRequestCache} to be replayed after the next login.
   */
  private static final String APP_LINK_CALLBACK = "/app/callback";

  /** Where {@link #APP_LINK_CALLBACK} must send an anonymous caller. */
  private static final String APP_LINK_HELP = "/app/link-help";

  /**
   * Public, but not a page — so the navigation shape does not apply to it.
   *
   * <p>Android App Links verification is fetched by the platform with no session and no browser
   * (REQ-SEC-038). It must answer {@code 200} with {@code application/json} and MUST NOT redirect:
   * behind the catch-all it answered {@code 302} into the OAuth2 entry point, verification failed,
   * and the login callback opened in the browser instead of the app — the member landed on the 404
   * page mid-login. Asked for as HTML it answers {@code 500}, which is why it is swept in the
   * background shape only.
   *
   * <p>The web app manifest is here for the same reason (REQ-UI-020, ADR-0164): a browser reads it
   * on the landing page with no session, and a {@code 302} would make the installed app's name and
   * icon come from the login page. <strong>Listing it is not a formality.</strong> Until it was
   * added, the sweep did issue it — and passed only because the mapping's {@code produces} made
   * both sweep shapes fail content negotiation, so a status that meant "I could not represent this"
   * was read as "the gate refused you". A path can be enumerated by this sweep and still have no
   * coverage at all; the entry is what turns that into a real assertion.
   */
  private static final Set<String> PUBLIC_RESOURCES =
      Set.of("/.well-known/assetlinks.json", "/manifest.webmanifest");

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

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Every call this sweep issues: what the dispatcher knows, minus the subtrees it does not ask
   * about.
   *
   * <p>The enumeration itself is shared with the other sweep ({@link EndpointEnumeration}, #1804).
   * Both guards are worth having for one reason - they ask the dispatcher rather than a list
   * somebody remembered to write - so a defect in that engine would blind both at once, and it now
   * lives in one place with tests of its own. What stays here is what is this sweep's own question:
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
        // An ASSERTION, not a skip — which is what this set's own Javadoc, SecurityConfig:249 and
        // WebAppManifestController all say it is. It was a bare `continue`, so adding a path here
        // REMOVED it from the sweep instead of covering it, and the `permitAll` entry those
        // comments
        // point at could have been deleted with every test still green.
        //
        // Asked for with `*/*` rather than `text/html`, which is why it could not simply go through
        // `issue()`: both of these mappings declare `produces`, so an HTML Accept header fails
        // content negotiation and returns a status that means "I cannot represent this" — read, in
        // the shape this sweep uses, as "the gate refused you". `*/*` matches any `produces`, so a
        // 200 here means served, and a 3xx means the gate redirected a resource that must never
        // redirect.
        int rendered =
            mockMvc
                .perform(
                    MockMvcRequestBuilders.request(call.method(), call.path())
                        .accept(MediaType.ALL)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getStatus();
        if (rendered != 200) {
          served.add(
              call
                  + " -> "
                  + rendered
                  + " (a REQ-SEC-052 public resource must be served anonymously, never"
                  + " redirected)");
        }
        continue;
      }
      if (APP_LINK_CALLBACK.equals(call.path())) {
        // An ASSERTION, not a skip, for the same reason the PUBLIC_RESOURCES branch above is one:
        // listing the path without checking it would remove it from the sweep, and the permitAll
        // entry it depends on could then be deleted with every test still green.
        String target =
            mockMvc
                .perform(
                    MockMvcRequestBuilders.request(call.method(), call.path())
                        .accept(MediaType.TEXT_HTML)
                        .with(csrf()))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        if (!APP_LINK_HELP.equals(target)) {
          served.add(
              call
                  + " -> "
                  + target
                  + " (REQ-SEC-038: the App Link fallback must redirect anonymously to "
                  + APP_LINK_HELP
                  + ", never into the OAuth2 entry point and never with the query attached)");
        }
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
      // rendered for somebody with no session, which is what this whole change removes. The second
      // clause carries no lower bound: `||` short-circuits, so it is only evaluated once the status
      // is already >= 300, and spelling that out again reads as a condition doing work it is not.
      if (status < 300 || (status < 400 && !isLoginRedirect(call))) {
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
          && (PUBLIC_PAGES.contains(call.path())
              || PUBLIC_RESOURCES.contains(call.path())
              // Same exclusion, same reason: this one must NOT be refused either. That it
              // redirects anonymously to the help page — and nowhere else — is asserted in
              // navigationIsSentToTheLogin above, so it is covered once rather than nowhere.
              || APP_LINK_CALLBACK.equals(call.path()))) {
        // A genuine exclusion here, unlike the one in `navigationIsSentToTheLogin` above: this test
        // asserts that a background call is REFUSED, and a public path is the one kind that must
        // not
        // be. That it is served anonymously is asserted there, so the entry is covered once rather
        // than nowhere.
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
   * The notification stream, asked for exactly the way a browser's {@code EventSource} asks: {@code
   * Accept: text/event-stream}, once with the Fetch Metadata every current engine sends ({@code
   * Sec-Fetch-Mode: cors}) and once without it (an older client, where the {@code Accept} header is
   * the only signal).
   *
   * <p>The sweep above covers this path only in the {@code application/json} shape, which no {@code
   * EventSource} ever sends. After the v1.11.0 deploy renamed the session cookie (2026-09-25),
   * every open tab reconnected its stream without a session, and the question was whether that
   * reconnect met a login redirect — a 302 an {@code EventSource} cannot follow, which would also
   * have overwritten the session's one saved OAuth2 authorization request (#1137). It does not: the
   * entry point answers {@code 401} + {@code X-Reauthenticate}, and nothing on the way logs an
   * {@code ERROR}. This pins both, so the shape a real browser uses stays covered.
   *
   * @throws Exception when the request could not be performed
   */
  @Test
  @DisplayName(
      "an anonymous EventSource on the notification stream gets 401, never a login redirect")
  void anonymousEventSourceGets401() throws Exception {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      for (boolean withFetchMetadata : new boolean[] {true, false}) {
        MockHttpServletRequestBuilder request =
            MockMvcRequestBuilders.get("/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM);
        if (withFetchMetadata) {
          request = request.header("Sec-Fetch-Mode", "cors");
        }
        org.springframework.mock.web.MockHttpServletResponse response =
            mockMvc.perform(request).andReturn().getResponse();

        Assertions.assertThat(response.getStatus())
            .as("Sec-Fetch-Mode present: %s", withFetchMetadata)
            .isEqualTo(401);
        Assertions.assertThat(response.getRedirectedUrl())
            .as("an EventSource cannot follow a redirect into the login")
            .isNull();
        Assertions.assertThat(response.getHeader("X-Reauthenticate"))
            .as("the re-auth contract the badge poll acts on (REQ-SEC-012)")
            .isEqualTo("/oauth2/authorization/keycloak");
      }
    } finally {
      root.detachAppender(appender);
    }
    Assertions.assertThat(appender.list)
        .as("a refused stream is a client condition, not an error (REQ-OBS-001)")
        .noneMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR);
  }
}
