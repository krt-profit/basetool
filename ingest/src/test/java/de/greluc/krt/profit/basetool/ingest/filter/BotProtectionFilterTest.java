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

package de.greluc.krt.profit.basetool.ingest.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for the gateway {@link BotProtectionFilter} (REQ-INGEST-009): known bot/scanner paths
 * and file extensions get 404, disallowed HTTP methods get 405, and the gateway's real surface
 * ({@code /v1/...}, {@code /actuator/health}, {@code /actuator/prometheus}, {@code /v3/api-docs})
 * passes through. Every reject bumps {@code basetool_bot_blocked_total} under its bounded {@code
 * rule} tag.
 */
class BotProtectionFilterTest {

  private BotProtectionFilter filter;
  private FilterChain filterChain;
  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    filter = new BotProtectionFilter(registry);
    filterChain = mock(FilterChain.class);
  }

  /**
   * Reads the {@code basetool_bot_blocked_total} value for one bounded {@code rule}, or {@code 0}
   * when that rule's series has not been registered yet.
   *
   * @param rule the bounded reject rule tag value
   * @return the counter value, or {@code 0.0} when no matching series exists
   */
  private double botCount(String rule) {
    var counter = registry.find(MetricNames.BOT_BLOCKED).tag(MetricNames.TAG_RULE, rule).counter();
    return counter == null ? 0.0 : counter.count();
  }

  // -------------------------------------------------------------------------
  // Path-prefix blocking
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/wp-admin/install.php",
        "/wp-login.php",
        "/wordpress/index.php",
        "/xmlrpc.php",
        "/phpmyadmin/",
        "/.env",
        "/.git/config",
        "/.svn/entries",
        "/.htaccess",
        "/.htpasswd",
        "/config/database.php",
        "/backup/db.sql",
        "/shell.php",
        "/cgi-bin/test",
        "/vendor/autoload.php",
        "/sitemap.xml",
        "/actuator/env",
        "/actuator/metrics",
        "/console",
        "/manager/html",
        "/jolokia",
        "/jmx",
        "/.well-known/acme-challenge/token",
        "/telescope/requests",
        "/horizon/api",
        "/nova/api",
        "/laravel/public",
        "/boaform/admin/formLogin",
        "/gponform/diag_Form",
        "/setup.cgi",
        "/owa/auth/logon.aspx",
        "/autodiscover/autodiscover.xml",
        "/ecp/default.aspx",
        "/ews/exchange.asmx",
        "/solr/admin",
        "/jenkins/script",
        "/hudson/script",
        "/jira/login.jsp",
        "/confluence/login.action",
        "/swagger-ui/index.html",
        "/api-docs",
        "/debug",
        "/trace"
      })
  void doFilterInternal_shouldReturn404_whenBotPathDetected(String botUri) throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", botUri);
    request.setRequestURI(botUri);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(404, response.getStatus(), "Bot path should return 404. URI=" + botUri);
    verify(filterChain, never()).doFilter(request, response);
    assertEquals(1.0, botCount(MetricNames.BOT_RULE_PATH_PREFIX));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/WP-ADMIN/install.php",
        "/Wp-Login.php",
        "/ACTUATOR/env",
        "/.ENV",
        "/PHPMYADMIN/",
        "/SITEMAP.XML"
      })
  void doFilterInternal_shouldReturn404_whenBotPathDetectedCaseInsensitive(String botUri)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", botUri);
    request.setRequestURI(botUri);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(
        404, response.getStatus(), "Bot path detection must be case-insensitive. URI=" + botUri);
    verify(filterChain, never()).doFilter(request, response);
  }

  // -------------------------------------------------------------------------
  // File-extension blocking
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/config.php",
        "/index.asp",
        "/default.aspx",
        "/test.cgi",
        "/script.pl",
        "/app.py",
        "/app.rb",
        "/index.cfm",
        "/dump.sql",
        "/db.bak",
        "/config.old",
        "/vim.swp",
        "/secrets.env",
        "/app.ini",
        "/error.log",
        "/deploy.sh",
        "/setup.bash",
        "/run.zsh",
        "/install.ps1",
        "/run.bat",
        "/start.cmd",
        "/archive.tar",
        "/backup.zip",
        "/data.rar",
        "/files.7z"
      })
  void doFilterInternal_shouldReturn404_whenBotFileExtensionDetected(String botUri)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", botUri);
    request.setRequestURI(botUri);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(404, response.getStatus(), "Bot file extension should return 404. URI=" + botUri);
    verify(filterChain, never()).doFilter(request, response);
    // Blocked exactly once. Some URIs (e.g. /config.php, /backup.zip) also match a bot PATH prefix
    // (/config, /backup), which is checked before the file-extension rule — so the block is counted
    // under path_prefix for those and under file_extension for the rest. Either way, exactly one.
    assertEquals(
        1.0,
        botCount(MetricNames.BOT_RULE_FILE_EXTENSION) + botCount(MetricNames.BOT_RULE_PATH_PREFIX),
        "the request must be blocked exactly once, by the file-extension or path-prefix rule: "
            + botUri);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/CONFIG.PHP", "/INDEX.ASP", "/DUMP.SQL", "/BACKUP.BAK"})
  void doFilterInternal_shouldReturn404_whenBotFileExtensionDetectedCaseInsensitive(String botUri)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", botUri);
    request.setRequestURI(botUri);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(
        404,
        response.getStatus(),
        "File extension detection must be case-insensitive. URI=" + botUri);
    verify(filterChain, never()).doFilter(request, response);
  }

  // -------------------------------------------------------------------------
  // HTTP method blocking (gateway allows only GET/POST/HEAD/OPTIONS)
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PUT",
        "DELETE",
        "PATCH",
        "TRACE",
        "CONNECT",
        "PROPFIND",
        "PROPPATCH",
        "MKCOL",
        "COPY",
        "MOVE",
        "LOCK",
        "UNLOCK"
      })
  void doFilterInternal_shouldReturn405_whenDisallowedHttpMethodUsed(String method)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/refinery-extract");
    request.setRequestURI("/v1/refinery-extract");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(
        405, response.getStatus(), "Disallowed HTTP method should return 405. Method=" + method);
    verify(filterChain, never()).doFilter(request, response);
    assertEquals(1.0, botCount(MetricNames.BOT_RULE_METHOD));
  }

  // -------------------------------------------------------------------------
  // Legitimate requests pass through
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/v1/refinery-extract",
        "/v1/blueprint-preview",
        "/actuator/health",
        "/actuator/health/readiness",
        "/actuator/health/liveness",
        "/actuator/prometheus",
        "/v3/api-docs",
        "/v3/api-docs/swagger-config"
      })
  void doFilterInternal_shouldPassThrough_whenLegitimatePathRequested(String appUri)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("POST", appUri);
    request.setRequestURI(appUri);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(
        200, response.getStatus(), "Legitimate path should pass through filter. URI=" + appUri);
    verify(filterChain, times(1)).doFilter(request, response);
    assertEquals(0.0, botCount(MetricNames.BOT_RULE_PATH_PREFIX));
    assertEquals(0.0, botCount(MetricNames.BOT_RULE_FILE_EXTENSION));
    assertEquals(0.0, botCount(MetricNames.BOT_RULE_METHOD));
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "HEAD", "OPTIONS"})
  void doFilterInternal_shouldPassThrough_whenAllowedHttpMethodUsed(String method)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/refinery-extract");
    request.setRequestURI("/v1/refinery-extract");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(
        200, response.getStatus(), "Allowed HTTP method should pass through. Method=" + method);
    verify(filterChain, times(1)).doFilter(request, response);
  }

  // -------------------------------------------------------------------------
  // Unit tests for helper methods
  // -------------------------------------------------------------------------

  @Test
  void isBotPath_shouldReturnTrue_forKnownBotPrefixes() {
    assertTrue(filter.isBotPath("/wp-admin/"));
    // `/actuator` (without the /health suffix) is still a bot path — only the
    // /actuator/health sub-path is whitelisted, see test below.
    assertTrue(filter.isBotPath("/actuator"));
    assertTrue(filter.isBotPath("/actuator/env"));
    assertTrue(filter.isBotPath("/.env"));
    assertTrue(filter.isBotPath("/phpmyadmin/"));
  }

  @Test
  void isBotPath_shouldReturnFalse_forLegitimateGatewayPaths() {
    assertFalse(filter.isBotPath("/v1/refinery-extract"));
    assertFalse(filter.isBotPath("/v1/blueprint-preview"));
    // The gateway's OpenAPI doc (non-prod) lives under /v3/api-docs, which must NOT be caught by
    // the /api-docs bot prefix.
    assertFalse(filter.isBotPath("/v3/api-docs"));
    assertFalse(filter.isBotPath("/v3/api-docs/swagger-config"));
  }

  @Test
  void isBotPath_shouldReturnFalse_forWhitelistedActuatorHealth() {
    // The Docker HEALTHCHECK hits /actuator/health/readiness, so this exact path is
    // explicitly whitelisted even though /actuator/* is otherwise blocked.
    assertFalse(filter.isBotPath("/actuator/health"));
    assertFalse(filter.isBotPath("/actuator/health/liveness"));
    assertFalse(filter.isBotPath("/actuator/health/readiness"));
  }

  @Test
  void isBotPath_shouldReturnFalse_forWhitelistedActuatorPrometheus() {
    // The monitoring scrape endpoint (REQ-OBS-005) must reach the dedicated fail-closed basic-auth
    // chain in MonitoringScrapeSecurityConfig — without the whitelist entry the bot filter would
    // answer 404 before any security chain runs. Every other /actuator/... path stays blocked.
    assertFalse(filter.isBotPath("/actuator/prometheus"));
    assertTrue(filter.isBotPath("/actuator/env"));
    assertTrue(filter.isBotPath("/actuator/metrics"));
    assertTrue(filter.isBotPath("/actuator"));
  }

  @Test
  void isBotPath_shouldMatchActuatorPrometheusExactlyOnly() {
    // The prometheus whitelist is exact and case-sensitive, mirroring the scrape chain's
    // securityMatcher: the endpoint has no sub-resources, so sub-paths, trailing slashes and
    // case variants are scanner noise and keep the cheap bot 404 instead of falling through to
    // the main resource-server chain.
    assertTrue(filter.isBotPath("/actuator/prometheus/"));
    assertTrue(filter.isBotPath("/actuator/prometheus/anything"));
    assertTrue(filter.isBotPath("/ACTUATOR/PROMETHEUS"));
    assertTrue(filter.isBotPath("/Actuator/Prometheus"));
  }

  @Test
  void doFilterInternal_shouldPassThrough_forActuatorPrometheus() throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
    request.setRequestURI("/actuator/prometheus");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then: the scrape path reaches the (fail-closed) security chain instead of a bot 404.
    assertEquals(200, response.getStatus(), "/actuator/prometheus must pass the bot filter");
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void isBotFileExtension_shouldReturnTrue_forKnownBotExtensions() {
    assertTrue(filter.isBotFileExtension("/config.php"));
    assertTrue(filter.isBotFileExtension("/dump.sql"));
    assertTrue(filter.isBotFileExtension("/backup.bak"));
    assertTrue(filter.isBotFileExtension("/deploy.sh"));
    assertTrue(filter.isBotFileExtension("/archive.zip"));
  }

  @Test
  void isBotFileExtension_shouldReturnFalse_forGatewaySurface() {
    assertFalse(filter.isBotFileExtension("/v1/refinery-extract"));
    assertFalse(filter.isBotFileExtension("/actuator/prometheus"));
    assertFalse(filter.isBotFileExtension("/v3/api-docs"));
  }

  // -------------------------------------------------------------------------
  // Malformed query string (rule 0)
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        // The stock PHP-CGI probe, verbatim from the production log that motivated this rule.
        "=phpinfo",
        "=phpinfo()",
        "=-phpinfo()",
        // The empty name can also sit in a later chunk.
        "a=1&=2",
        "a=1&=",
        // A bare "=" is invalid too: empty name, empty value.
        "="
      })
  void doFilterInternal_shouldReturn400_whenQueryStringIsMalformed(String queryString)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setRequestURI("/");
    request.setQueryString(queryString);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(
        400, response.getStatus(), "malformed query string must be rejected: " + queryString);
    // sendError() would hand the request to the container's error dispatch, which re-reads the very
    // parameters that cannot be parsed. The reject therefore carries no error page at all.
    assertNull(response.getErrorMessage());
    assertEquals("", response.getContentAsString());
    verify(filterChain, never()).doFilter(request, response);
    assertEquals(1.0, botCount(MetricNames.BOT_RULE_QUERY_STRING));
  }

  @Test
  void doFilterInternal_shouldRejectMalformedQueryString_beforeTheOtherRules() throws Exception {
    // A scanner combines both: a bot path AND a broken query string. The path rule answers with
    // sendError(404), whose error dispatch would re-parse the query string and blow up — so the
    // query-string rule has to win.
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wp-login.php");
    request.setRequestURI("/wp-login.php");
    request.setQueryString("=phpinfo()");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, filterChain);

    assertEquals(400, response.getStatus());
    assertEquals(1.0, botCount(MetricNames.BOT_RULE_QUERY_STRING));
    assertEquals(0.0, botCount(MetricNames.BOT_RULE_PATH_PREFIX));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "lang=en",
        "page=2&size=25",
        // An empty chunk is legal for Tomcat and must not be rejected.
        "a=1&&b=2",
        "&",
        // A name with no value is legal.
        "flag",
        "a=1&flag&b=2",
        // "=" inside a VALUE is legal; only a chunk STARTING with "=" is not.
        "filter=a=b",
        // %3D is a separator only after decoding, which happens per chunk — not a chunk boundary.
        "%3Dphpinfo=1"
      })
  void doFilterInternal_shouldPassThrough_whenQueryStringIsWellFormed(String queryString)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setRequestURI("/");
    request.setQueryString(queryString);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, filterChain);

    assertEquals(200, response.getStatus(), "well-formed query string must pass: " + queryString);
    verify(filterChain, times(1)).doFilter(request, response);
    assertEquals(0.0, botCount(MetricNames.BOT_RULE_QUERY_STRING));
  }

  @Test
  void isMalformedQueryString_shouldTolerateNullAndEmpty() {
    assertFalse(BotProtectionFilter.isMalformedQueryString(null));
    assertFalse(BotProtectionFilter.isMalformedQueryString(""));
  }
}
