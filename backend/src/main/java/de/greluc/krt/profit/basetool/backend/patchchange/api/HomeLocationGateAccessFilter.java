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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import de.greluc.krt.profit.basetool.backend.identity.api.TermsAcceptanceAccessFilter;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Refuses the web's API with an RFC 7807 403 to a member who must still choose a new home location
 * (REQ-PATCH-020), like the terms acceptance's gate.
 *
 * <p>Runs after {@link TermsAcceptanceAccessFilter}. An exchange client's relayed request passes:
 * the exchange keeps reading and booking new stock, and the relocation lock refuses its writes on
 * locked rows and ships instead (REQ-PATCH-023).
 */
@Slf4j
@RequiredArgsConstructor
public class HomeLocationGateAccessFilter extends OncePerRequestFilter {

  /** The prompt and the choice of the caller's own home location. */
  public static final String PROMPT_PATH = "/api/v1/patch-changes/me/home-location";

  /** The cheap check the frontend's gate asks on every request it does not cache. */
  public static final String STATUS_PATH = PROMPT_PATH + "/status";

  /** The shared parser for this filter's path patterns. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /** The guarded surface, matched on the decoded path. */
  private static final PathPattern API_SCOPE = PATH_PARSER.parse("/api/**");

  /**
   * The endpoints a pending member may still reach: the gate's own, the caller's identity read the
   * frontend's role sync makes, and every endpoint the terms acceptance's gate leaves open.
   */
  public static final List<String> EXEMPT_PATHS =
      Stream.concat(
              Stream.of(PROMPT_PATH, STATUS_PATH, "/api/v1/users/me"),
              TermsAcceptanceAccessFilter.EXEMPT_PATHS.stream())
          .toList();

  /** {@link #EXEMPT_PATHS} parsed once, matched on the decoded path. */
  private static final List<PathPattern> EXEMPT_PATTERNS =
      EXEMPT_PATHS.stream().map(PATH_PARSER::parse).toList();

  /** App-wide correlation-id response header. */
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** The machine-readable code the frontend maps to the home-location page. */
  private static final String CODE = PatchChangeProblemCode.HOME_LOCATION_REQUIRED.code();

  private final HomeLocationGateCheck gateCheck;
  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    if (mustChoose(request)) {
      writeForbidden(request, response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Decides whether the request comes from a pending member's web session outside the exempt
   * endpoints.
   *
   * @param request the current request
   * @return {@code true} when the request must be refused
   */
  private boolean mustChoose(@NotNull HttpServletRequest request) {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    if (!API_SCOPE.matches(path) || EXEMPT_PATTERNS.stream().anyMatch(p -> p.matches(path))) {
      return false;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (isExchangeClient(auth)) {
      return false;
    }
    UUID userId = AuthenticatedSubject.idOf(auth).orElse(null);
    return userId != null && gateCheck.mustChoose(userId);
  }

  /**
   * Tells whether an exchange client relayed the request.
   *
   * @param auth the current authentication, or {@code null}
   * @return {@code true} for a relayed exchange client's call
   */
  private static boolean isExchangeClient(@Nullable Authentication auth) {
    return auth instanceof SubjectAuthentication subject && subject.externalClient() != null;
  }

  /**
   * Writes the RFC 7807 403 and counts it.
   *
   * @param request the refused request
   * @param response the response to write into
   * @throws IOException if serialization or writing fails
   */
  private void writeForbidden(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String correlationId = UUID.randomUUID().toString();
    log.debug(
        "Home location missing; refused {} {} [correlationId={}]",
        request.getMethod(),
        request.getRequestURI(),
        correlationId);
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, CODE).increment();
    Locale locale = request.getLocale();
    String key = "problem." + CODE.toLowerCase(Locale.ROOT);
    String title = messageSource.getMessage(key + ".title", null, "Forbidden", locale);
    String detail =
        messageSource.getMessage(
            key + ".detail", null, "A new home location must be chosen first.", locale);
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.FORBIDDEN,
            title,
            detail,
            request.getRequestURI(),
            CODE.toLowerCase(Locale.ROOT).replace('_', '-'),
            CODE,
            correlationId);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }
}
