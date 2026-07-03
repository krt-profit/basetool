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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BotProtectionFilterTest {

  private BotProtectionFilter filter;
  private FilterChain filterChain;

  @BeforeEach
  void setUp() {
    filter = new BotProtectionFilter();
    filterChain = mock(FilterChain.class);
  }

  // -------------------------------------------------------------------------
  // Path-prefix blocking
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/wp-admin/install.php",
        "/wp-login.php",
        "/wp-json/wp/v2/users",
        "/wp-sitemap-users-1.xml",
        "/wordpress/index.php",
        "/xmlrpc.php",
        "/feed/",
        "/author/admin/",
        "/author-sitemap.xml",
        "/phpmyadmin/",
        "/.env",
        "/.git/config",
        "/.svn/entries",
        "/.htaccess",
        "/.htpasswd",
        "/.ds_store",
        "/config/database.php",
        "/backup/db.sql",
        "/shell.php",
        "/cgi-bin/test",
        "/vendor/autoload.php",
        // M-17: /robots.txt removed — served as a static file with "Disallow: /".
        "/sitemap.xml",
        "/sitemap-users-1.xml",
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
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/WP-ADMIN/install.php",
        "/Wp-Login.php",
        // M-17: /ROBOTS.TXT no longer blocked — see static robots.txt + isBotPath test.
        "/ACTUATOR/env",
        "/.ENV",
        "/PHPMYADMIN/",
        "/FEED/",
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
  // HTTP method blocking
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
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
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/dashboard");
    request.setRequestURI("/dashboard");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(
        405, response.getStatus(), "Disallowed HTTP method should return 405. Method=" + method);
    verify(filterChain, never()).doFilter(request, response);
  }

  // -------------------------------------------------------------------------
  // Legitimate requests pass through
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/",
        "/missions",
        "/missions/ff529a98-469a-4ea1-b91e-3028f79f30c0",
        "/orders",
        "/order/",
        "/operations",
        "/hangar",
        "/inventory",
        "/profile",
        "/settings",
        "/error",
        "/css/main.css",
        "/js/app.js",
        "/images/logo.png",
        "/fonts/lato.woff2",
        "/impressum",
        "/privacy"
      })
  void doFilterInternal_shouldPassThrough_whenLegitimatePathRequested(String appUri)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", appUri);
    request.setRequestURI(appUri);
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertEquals(
        200, response.getStatus(), "Legitimate path should pass through filter. URI=" + appUri);
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
  void doFilterInternal_shouldPassThrough_whenAllowedHttpMethodUsed(String method)
      throws Exception {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/missions");
    request.setRequestURI("/missions");
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
  void isBotPath_shouldReturnFalse_forLegitimateAppPaths() {
    assertFalse(filter.isBotPath("/"));
    assertFalse(filter.isBotPath("/missions"));
    assertFalse(filter.isBotPath("/orders"));
    assertFalse(filter.isBotPath("/hangar"));
    assertFalse(filter.isBotPath("/css/main.css"));
    // M-17: /robots.txt is now served as a regular static file ("Disallow: /"); the bot
    // filter does NOT short-circuit it anymore.
    assertFalse(filter.isBotPath("/robots.txt"));
  }

  @Test
  void isBotPath_shouldReturnFalse_forWhitelistedActuatorHealth() {
    // The Docker HEALTHCHECK hits /actuator/health, so this exact path is
    // explicitly whitelisted even though /actuator/* is otherwise blocked.
    assertFalse(filter.isBotPath("/actuator/health"));
    assertFalse(filter.isBotPath("/actuator/health/liveness"));
    assertFalse(filter.isBotPath("/actuator/health/readiness"));
  }

  @Test
  void isBotPath_shouldReturnFalse_forWhitelistedActuatorPrometheus() {
    // The monitoring scrape endpoint (REQ-OBS-005, epic #936) must reach the dedicated
    // fail-closed basic-auth chain in MonitoringScrapeSecurityConfig — without the whitelist
    // entry the bot filter would answer 404 before any security chain runs. Every other
    // /actuator/... path stays blocked (assertion below).
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
    // the main OAuth2 chain (login redirect + request-cache churn).
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
  void isBotFileExtension_shouldReturnFalse_forLegitimateFileExtensions() {
    assertFalse(filter.isBotFileExtension("/css/main.css"));
    assertFalse(filter.isBotFileExtension("/js/app.js"));
    assertFalse(filter.isBotFileExtension("/images/logo.png"));
    assertFalse(filter.isBotFileExtension("/fonts/lato.woff2"));
    assertFalse(filter.isBotFileExtension("/page.html"));
  }
}
