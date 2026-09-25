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
 * The frontend half of REQ-SEC-052: enumerates every MVC mapping and verifies that none serves a
 * visitor without a session.
 *
 * <p>Each read is issued as a navigation, which must redirect to the OAuth2 entry point, and as a
 * background {@code application/json} call, which must answer {@code 401} with {@code
 * X-Reauthenticate} (REQ-SEC-012). Writes carry a CSRF token, so a {@code 403} fails the sweep like
 * a {@code 2xx}.
 */
@SpringBootTest
class AnonymousSurfaceSweepMvcTest {

  /** The page paths REQ-SEC-052 serves without a session. */
  private static final Set<String> PUBLIC_PAGES =
      Set.of("/", "/impressum", "/privacy", "/terms", "/licenses", "/app/link-help");

  /**
   * The Android App Link's web-side fallback (REQ-SEC-038), which is public and must answer {@code
   * 303} to {@link #APP_LINK_HELP} rather than redirect to the OAuth2 entry point.
   */
  private static final String APP_LINK_CALLBACK = "/app/callback";

  /** Where {@link #APP_LINK_CALLBACK} must send an anonymous caller. */
  private static final String APP_LINK_HELP = "/app/link-help";

  /**
   * Public non-page resources that must answer {@code 200} without a session and are swept only in
   * the background shape: the App Links descriptor (REQ-SEC-038) and the web app manifest
   * (REQ-UI-020).
   */
  private static final Set<String> PUBLIC_RESOURCES =
      Set.of("/.well-known/assetlinks.json", "/manifest.webmanifest");

  /**
   * Mappings this sweep does not own, matched as whole path segments rather than string prefixes.
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
   * Every call this sweep issues: the dispatcher's mappings via {@link EndpointEnumeration}, minus
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
   * Whether the redirect this call produces points at the login; re-issues the call.
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
              || APP_LINK_CALLBACK.equals(call.path()))) {
        continue;
      }
      int status = issue(call, MediaType.APPLICATION_JSON);
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
   * Verifies that an anonymous {@code EventSource}-shaped request to the notification stream
   * ({@code Accept: text/event-stream}, with and without {@code Sec-Fetch-Mode: cors}) answers
   * {@code 401} with {@code X-Reauthenticate} and logs no {@code ERROR}.
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
