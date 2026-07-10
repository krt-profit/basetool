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

import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects known bot, scanner, and exploit-probe requests before they reach Spring Security
 * (REQ-INGEST-009), the gateway mirror of the frontend {@code BotProtectionFilter}. The ingest
 * gateway is the only new internet-reachable surface (REQ-INGEST-001); mass scanners hammer it with
 * WordPress / PHP / Actuator / WebDAV probes that would otherwise each spin up the resource-server
 * bearer-token filter (and, on a cold JWKS cache, an identity-provider round-trip).
 * Short-circuiting them here keeps that noise off the security chain and the backend.
 *
 * <p>Three independent detection strategies are applied in order:
 *
 * <ol>
 *   <li><b>HTTP-method filtering:</b> the gateway only ever answers {@code GET} (actuator /
 *       api-docs), {@code POST} (the two {@code /v1} ingest endpoints), {@code HEAD} (health
 *       probes) and {@code OPTIONS} (CORS preflight). Any other method — {@code PUT}/{@code
 *       DELETE}/{@code PATCH} verb-tampering, {@code TRACE}/{@code CONNECT}, WebDAV {@code
 *       PROPFIND}/{@code MKCOL}/… — is answered with HTTP 405.
 *   <li><b>Path-prefix matching:</b> URIs starting with a known bot/scanner path prefix (e.g.
 *       {@code /wp-admin/}, {@code /.env}, {@code /actuator/env}) get HTTP 404 immediately. The two
 *       actuator paths the gateway really exposes ({@code /actuator/health}, {@code
 *       /actuator/prometheus}) are whitelisted so their own security handling still runs.
 *   <li><b>File-extension matching:</b> requests for file types the JSON-only gateway never serves
 *       (e.g. {@code .php}, {@code .asp}, {@code .sql}) get HTTP 404.
 * </ol>
 *
 * <p>Runs after {@link CorrelationIdFilter} (so a blocked request is still correlation-tagged in
 * the logs) but before the size-cap, rate-limit and Spring Security filters. Detection is
 * case-insensitive to defeat trivial casing bypasses. Only the bounded reject {@code rule} is
 * recorded on {@code basetool_bot_blocked_total} — never the attacker-controlled URI or method
 * (REQ-OBS-006/-011).
 */
@Component
@Order(BotProtectionFilter.ORDER)
@Slf4j
public class BotProtectionFilter extends OncePerRequestFilter {

  /** After correlation id, before the size-cap, rate-limit and Spring Security filters. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 15;

  private final MeterRegistry meterRegistry;

  /**
   * Wires the {@code basetool_bot_blocked_total} counter registry.
   *
   * @param meterRegistry the Micrometer registry the per-rule bot-block counter is bumped against
   */
  public BotProtectionFilter(@NotNull MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * URI path prefixes known to originate from automated scanners, bots, or exploit attempts.
   * Requests whose URI starts with any of these (case-insensitive) are answered with HTTP 404
   * without further processing. Deliberately a superset of the gateway's own surface — none of
   * these prefixes collide with {@code /v1/}, {@code /v3/api-docs} or the whitelisted actuator
   * paths.
   */
  static final Set<String> BOT_PATH_PREFIXES =
      Set.of(
          // WordPress / CMS
          "/wp-",
          "/wordpress",
          "/xmlrpc",
          // PHP / generic web-app scanners
          "/phpmyadmin",
          // Feed / author / sitemap (WordPress-style)
          "/feed",
          "/author",
          "/sitemap",
          // Dot-files and hidden config
          "/.env",
          "/.git",
          "/.svn",
          "/.htaccess",
          "/.htpasswd",
          "/.ds_store",
          // Generic config / backup / shell paths
          "/config",
          "/backup",
          "/shell",
          "/cgi-bin",
          "/vendor",
          // Spring Boot / Java management endpoints (only /actuator/health + /actuator/prometheus
          // are whitelisted below; every other /actuator/... probe is scanner noise here)
          "/actuator",
          "/console",
          "/manager",
          "/jolokia",
          "/jmx",
          // Well-known ACME challenge (scanner abuse)
          "/.well-known/acme-challenge",
          // Laravel / PHP framework paths
          "/telescope",
          "/horizon",
          "/nova",
          "/laravel",
          // Router / IoT exploits
          "/boaform",
          "/gponform",
          "/setup.cgi",
          // Microsoft Exchange / Outlook Web Access
          "/owa",
          "/autodiscover",
          "/ecp",
          "/ews",
          // Other known exploit targets
          "/solr",
          "/jenkins",
          "/hudson",
          "/jira",
          "/confluence",
          // API discovery scans — the gateway serves no Swagger UI; its OpenAPI doc lives under
          // /v3/api-docs (non-prod only), which does NOT match this prefix
          "/swagger-ui",
          "/api-docs",
          // Debug / trace endpoints
          "/debug",
          "/trace");

  /**
   * File extensions that the JSON-only gateway never serves. Requests for files with these
   * extensions (case-insensitive) are answered with HTTP 404.
   */
  static final Set<String> BOT_FILE_EXTENSIONS =
      Set.of(
          ".php", ".asp", ".aspx", ".cgi", ".pl", ".py", ".rb", ".cfm", ".sql", ".bak", ".old",
          ".swp", ".env", ".ini", ".log", ".sh", ".bash", ".zsh", ".ps1", ".bat", ".cmd", ".xml.gz",
          ".tar", ".tar.gz", ".zip", ".rar", ".7z");

  /**
   * HTTP methods the gateway actually uses: {@code GET} (actuator / api-docs), {@code POST} (the
   * {@code /v1} ingest endpoints), {@code HEAD} (health probes) and {@code OPTIONS} (CORS
   * preflight). Any request using a method outside this set is answered with HTTP 405 — narrower
   * than the frontend's set because the gateway never uses {@code PUT}/{@code DELETE}/{@code
   * PATCH}.
   */
  static final Set<String> ALLOWED_HTTP_METHODS = Set.of("GET", "POST", "HEAD", "OPTIONS");

  /**
   * URIs that look like bot/scanner targets but are legitimate actuator endpoints this gateway
   * owns. They short-circuit {@link #isBotPath(String)} — matched case-insensitively as the exact
   * path or any sub-path — so their own security handling runs instead of a bot 404. Currently the
   * Actuator health endpoint (incl. its {@code /liveness} / {@code /readiness} probes), exposed
   * publicly so the Docker {@code HEALTHCHECK} can reach it. See {@link #LEGITIMATE_EXACT_PATHS}
   * for the stricter exact-match whitelist.
   */
  static final Set<String> LEGITIMATE_PATHS = Set.of("/actuator/health");

  /**
   * Whitelist entries matched as an <b>exact, case-sensitive</b> path only — sub-paths, trailing
   * slashes and case variants keep the bot 404. Currently only {@code /actuator/prometheus}, the
   * monitoring scrape endpoint (REQ-OBS-005): without this entry the filter would answer 404 before
   * the fail-closed basic-auth chain in {@code MonitoringScrapeSecurityConfig} ever runs. The
   * exact-match semantics mirror that chain's {@code securityMatcher} — the endpoint has no
   * sub-resources, so anything below it is scanner noise. Passing this filter only hands the
   * request to that fail-closed chain (denyAll until the scrape credentials are configured); it is
   * NOT made public here.
   */
  static final Set<String> LEGITIMATE_EXACT_PATHS = Set.of("/actuator/prometheus");

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {

    String uri = request.getRequestURI();
    String method = request.getMethod();

    // 1. HTTP method check
    if (!ALLOWED_HTTP_METHODS.contains(method.toUpperCase())) {
      log.debug("Blocked disallowed HTTP method: {} {}", method, uri);
      recordBlocked(MetricNames.BOT_RULE_METHOD);
      response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      return;
    }

    // 2. Path-prefix check
    if (isBotPath(uri)) {
      log.debug("Blocked bot/scanner path: {} {}", method, uri);
      recordBlocked(MetricNames.BOT_RULE_PATH_PREFIX);
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    // 3. File-extension check
    if (isBotFileExtension(uri)) {
      log.debug("Blocked bot/scanner file extension: {} {}", method, uri);
      recordBlocked(MetricNames.BOT_RULE_FILE_EXTENSION);
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    filterChain.doFilter(request, response);
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
   * Returns whether the given URI starts with a known bot/scanner path prefix, after excluding the
   * two whitelisted actuator paths.
   *
   * @param uri the request URI to check (must not be {@code null})
   * @return {@code true} if the URI matches a bot path prefix and is not whitelisted
   */
  boolean isBotPath(@NotNull String uri) {
    // Exact, case-sensitive whitelist first: mirrors the securityMatcher of the scrape chain, so
    // only the one real endpoint path escapes the bot 404 (sub-paths/case variants do not).
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
   * Returns whether the given URI ends with a file extension the gateway never serves.
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
