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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.NumberUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Caps the page size an <b>unauthenticated</b> caller may request on the API (REQ-SEC-032).
 *
 * <p>{@code PaginationUtil} clamps {@code size} at 100 000, which is right for the authenticated
 * consumers that page-walk large catalogues, and far too generous for a surface anyone on the
 * internet can reach: a single request would return the whole materials catalogue, and repeating it
 * is the cheapest amplification available. The anonymous ceiling is {@value
 * #MAX_ANONYMOUS_PAGE_SIZE}, chosen because it is exactly what the known anonymous callers already
 * ask for — the guest order-form pickers and the catalogue page-walks all use {@code size=1000} —
 * so it costs the legitimate flows nothing while removing two orders of magnitude from the lever.
 *
 * <p><b>It refuses rather than clamps, and that is the whole design.</b> Silently reducing a
 * requested page size is the defect ADR-0104 forbids: the caller receives fewer rows than it asked
 * for, cannot tell, and any surface built on "one big page" then lies about being complete. A 400
 * naming the limit is honest, and a page-walking consumer is unaffected either way because it asks
 * for pages until they run out.
 *
 * <p>Runs inside the security chain, after authentication has been established, because "is this
 * caller anonymous" is the only question it asks. An authenticated caller passes through untouched.
 */
@Slf4j
@RequiredArgsConstructor
public class AnonymousPageSizeFilter extends OncePerRequestFilter {

  /**
   * The largest {@code size} an unauthenticated caller may request.
   *
   * <p>Not lower on purpose: every anonymous caller in the codebase asks for exactly this, so a
   * tighter value would start rejecting the guest order form rather than an attacker.
   */
  public static final int MAX_ANONYMOUS_PAGE_SIZE = 1_000;

  /** Stable machine-readable code on the problem body, and the metric's bounded label. */
  static final String CODE_PAGE_SIZE_TOO_LARGE = "PAGE_SIZE_TOO_LARGE";

  /** Only the API is paginated; the rest of the surface has no {@code size} semantics. */
  private static final PathPattern API_SCOPE = PathPatternParser.defaultInstance.parse("/api/**");

  /** Correlation id echoed onto the problem body, matching the other filter-level problems. */
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  /**
   * Skips everything that cannot carry a paginated anonymous request.
   *
   * <p>The scope is matched against the <b>decoded</b> path (REQ-SEC-029): {@code getRequestURI()}
   * is percent-encoded, so a spelling such as {@code /%61pi/v1/materials} must not slip the cap.
   *
   * @param request the incoming request.
   * @return {@code true} when the filter has nothing to decide.
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null || !API_SCOPE.matches(PathContainer.parsePath(uri))) {
      return true;
    }
    // An absent OR empty `size` is not this filter's business: `?size=` binds to null and is a
    // legal way to ask for the default page. Handled here rather than inside withinLimit, so that
    // method can stay a strict "does this parse the way Spring parses it, and is it in range".
    String size = request.getParameter("size");
    return size == null || size.isBlank();
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    if (!isAnonymous() || withinLimit(request.getParameter("size"))) {
      chain.doFilter(request, response);
      return;
    }
    reject(request, response);
  }

  /**
   * Whether the current caller has no authenticated identity.
   *
   * @return {@code true} for a missing, unauthenticated or anonymous authentication.
   */
  private static boolean isAnonymous() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken;
  }

  /**
   * Whether a requested size is acceptable for an anonymous caller.
   *
   * <p><strong>Parsed exactly the way Spring will parse it, and not more strictly.</strong> This
   * used to be {@code Integer.parseInt(sizeParam.trim())} with an "unparseable means allowed"
   * fallback, on the stated grounds that {@code PaginationUtil} defaults a non-numeric value
   * anyway. {@code PaginationUtil} never sees the raw string - Spring's binder does, through {@code
   * StringToNumberConverterFactory} - {@code NumberUtils#parseNumber}, which first strips
   * <em>all</em> whitespace and then honours the hex spellings {@code 0x}, {@code 0X} and {@code
   * #}. So {@code size=0x186A0} threw here, was waved through as "unparseable", and bound to 100000
   * downstream: the REQ-SEC-032 ceiling of 1000 was bypassable with three characters, on paths that
   * are anonymous by design and outside the per-account budget.
   *
   * <p>Using the binder's own parser removes the divergence by construction. A value it cannot
   * parse either is now <em>refused</em> rather than allowed, because a size the binder rejects can
   * never legitimately produce a page. The one legitimate non-number, an <em>empty</em> {@code
   * size=}, never reaches here: {@link #shouldNotFilter} skips it.
   *
   * @param sizeParam the raw {@code size} query parameter; never {@code null} or blank here.
   * @return {@code true} only when the value parses the way Spring will parse it AND is within the
   *     ceiling.
   */
  private static boolean withinLimit(String sizeParam) {
    try {
      return NumberUtils.parseNumber(sizeParam, Integer.class) <= MAX_ANONYMOUS_PAGE_SIZE;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  /**
   * Writes the RFC 7807 refusal naming the limit, and counts it.
   *
   * @param request the request being refused.
   * @param response the response to write.
   * @throws IOException if the body cannot be written.
   */
  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // Bounded label, and the counter is the only record: the path is already on the access log and
    // the caller's address is deliberately never logged here (REQ-OBS-004).
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, CODE_PAGE_SIZE_TOO_LARGE)
        .increment();
    log.debug(
        "Anonymous page size above the {} ceiling refused on {}",
        MAX_ANONYMOUS_PAGE_SIZE,
        request.getRequestURI());

    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String correlationId = response.getHeader(CORRELATION_ID_HEADER);
    if (correlationId != null) {
      response.setHeader(CORRELATION_ID_HEADER, correlationId);
    }
    Locale locale = request.getLocale();
    String title =
        messageSource.getMessage("problem.page_size_too_large.title", null, "Bad Request", locale);
    String detail =
        messageSource.getMessage(
            "problem.page_size_too_large.detail",
            new Object[] {MAX_ANONYMOUS_PAGE_SIZE},
            "The requested page size exceeds the limit for unauthenticated callers.",
            locale);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.BAD_REQUEST,
            title,
            detail,
            request.getRequestURI(),
            "page-size-too-large",
            CODE_PAGE_SIZE_TOO_LARGE,
            correlationId);
    // UTF-8 bytes directly: the localized detail carries German umlauts and the servlet writer
    // would encode them as ISO-8859-1.
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }
}
