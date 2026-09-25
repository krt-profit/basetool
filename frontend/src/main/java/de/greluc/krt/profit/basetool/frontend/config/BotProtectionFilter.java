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

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that rejects known bot, scanner and exploit requests before the Spring Security
 * chain.
 *
 * <p>Applies, case-insensitively and in this order: a malformed query string (400), a bot path
 * prefix (404), a never-served file extension (404), and an unused HTTP method (405).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BotProtectionFilter extends OncePerRequestFilter {

  /** The Micrometer registry the per-rule bot-block counter is bumped against. */
  private final @NotNull MeterRegistry meterRegistry;

  /**
   * URI path prefixes that are known to originate from automated scanners, bots, or exploit
   * attempts. Requests whose URI starts with any of these prefixes (case-insensitive) are answered
   * with HTTP 404 without further processing.
   */
  static final Set<String> BOT_PATH_PREFIXES =
      Set.of(
          "/wp-",
          "/wordpress",
          "/xmlrpc",
          "/phpmyadmin",
          "/feed",
          "/author",
          "/sitemap",
          "/.env",
          "/.git",
          "/.svn",
          "/.htaccess",
          "/.htpasswd",
          "/.ds_store",
          "/config",
          "/backup",
          "/shell",
          "/cgi-bin",
          "/vendor",
          "/actuator",
          "/console",
          "/manager",
          "/jolokia",
          "/jmx",
          "/.well-known/acme-challenge",
          "/telescope",
          "/horizon",
          "/nova",
          "/laravel",
          "/boaform",
          "/gponform",
          "/setup.cgi",
          "/owa",
          "/autodiscover",
          "/ecp",
          "/ews",
          "/solr",
          "/jenkins",
          "/hudson",
          "/jira",
          "/confluence",
          "/swagger-ui",
          "/api-docs",
          "/debug",
          "/trace");

  /**
   * File extensions that the application never serves. Requests for files with these extensions
   * (case-insensitive) are answered with HTTP 404.
   */
  static final Set<String> BOT_FILE_EXTENSIONS =
      Set.of(
          ".php", ".asp", ".aspx", ".cgi", ".pl", ".py", ".rb", ".cfm", ".sql", ".bak", ".old",
          ".swp", ".env", ".ini", ".log", ".sh", ".bash", ".zsh", ".ps1", ".bat", ".cmd", ".xml.gz",
          ".tar", ".tar.gz", ".zip", ".rar", ".7z");

  /**
   * HTTP methods that the application actively uses. Any request using a method not in this set is
   * answered with HTTP 405 (Method Not Allowed).
   */
  static final Set<String> ALLOWED_HTTP_METHODS =
      Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {

    String uri = request.getRequestURI();
    String method = request.getMethod();

    if (isMalformedQueryString(request.getQueryString())) {
      log.debug("Blocked malformed query string: {} {}", method, uri);
      recordBlocked(MetricNames.BOT_RULE_QUERY_STRING);
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.setContentLength(0);
      return;
    }

    if (!ALLOWED_HTTP_METHODS.contains(method.toUpperCase())) {
      log.debug("Blocked disallowed HTTP method: {} {}", method, uri);
      recordBlocked(MetricNames.BOT_RULE_METHOD);
      response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      return;
    }

    if (isBotPath(uri)) {
      log.debug("Blocked bot/scanner path: {} {}", method, uri);
      recordBlocked(MetricNames.BOT_RULE_PATH_PREFIX);
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    if (isBotFileExtension(uri)) {
      log.debug("Blocked bot/scanner file extension: {} {}", method, uri);
      recordBlocked(MetricNames.BOT_RULE_FILE_EXTENSION);
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Detects the query-string shape Tomcat's parameter parser rejects: a chunk with an empty name,
   * i.e. starting with {@code =} (e.g. {@code /?=phpinfo()}). Checked on the raw string; empty
   * chunks are legal.
   *
   * @param queryString the raw query string from {@code getQueryString()}; may be {@code null}
   * @return {@code true} when at least one chunk starts with {@code =}
   */
  static boolean isMalformedQueryString(String queryString) {
    if (queryString == null || queryString.isEmpty()) {
      return false;
    }
    int chunkStart = 0;
    int length = queryString.length();
    while (chunkStart < length) {
      int ampersand = queryString.indexOf('&', chunkStart);
      int chunkEnd = ampersand < 0 ? length : ampersand;
      if (chunkEnd > chunkStart && queryString.charAt(chunkStart) == '=') {
        return true;
      }
      if (ampersand < 0) {
        break;
      }
      chunkStart = ampersand + 1;
    }
    return false;
  }

  /**
   * Bumps {@code basetool_bot_blocked_total} for one bounded reject {@code rule}. Only the rule
   * category is recorded — never the URI or method, both attacker-controlled / unbounded.
   *
   * @param rule the bounded reject rule ({@code method} / {@code path_prefix} / {@code
   *     file_extension})
   */
  private void recordBlocked(@NotNull String rule) {
    meterRegistry.counter(MetricNames.BOT_BLOCKED, MetricNames.TAG_RULE, rule).increment();
  }

  /**
   * Legitimate paths exempt from {@link #isBotPath(String)}, matched case-insensitively as the
   * exact path or any sub-path; currently the Actuator health endpoint. See {@link
   * #LEGITIMATE_EXACT_PATHS} for the exact-match list.
   */
  static final Set<String> LEGITIMATE_PATHS = Set.of("/actuator/health");

  /**
   * Legitimate paths exempt only as an exact, case-sensitive match; currently {@code
   * /actuator/prometheus}, which is then secured by {@link MonitoringScrapeSecurityConfig}
   * (REQ-OBS-005).
   */
  static final Set<String> LEGITIMATE_EXACT_PATHS = Set.of("/actuator/prometheus");

  /**
   * Returns {@code true} if the given URI starts with a known bot/scanner path prefix.
   *
   * @param uri the request URI to check (must not be {@code null})
   * @return {@code true} if the URI matches a bot path prefix
   */
  boolean isBotPath(@NotNull String uri) {
    if (LEGITIMATE_EXACT_PATHS.contains(uri)) {
      return false;
    }
    String lowerUri = uri.toLowerCase();
    for (String legit : LEGITIMATE_PATHS) {
      if (lowerUri.equals(legit) || lowerUri.startsWith(legit + "/")) {
        return false;
      }
    }
    for (String prefix : BOT_PATH_PREFIXES) {
      if (lowerUri.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the given URI ends with a file extension that the application never
   * serves.
   *
   * @param uri the request URI to check (must not be {@code null})
   * @return {@code true} if the URI ends with a known bot file extension
   */
  boolean isBotFileExtension(@NotNull String uri) {
    String lowerUri = uri.toLowerCase();
    for (String ext : BOT_FILE_EXTENSIONS) {
      if (lowerUri.endsWith(ext)) {
        return true;
      }
    }
    return false;
  }
}
