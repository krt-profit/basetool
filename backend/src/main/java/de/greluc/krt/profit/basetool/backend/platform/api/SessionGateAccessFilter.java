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

package de.greluc.krt.profit.basetool.backend.platform.api;

import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
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
 * Refuses the API with an RFC 7807 403 to an authenticated caller a {@link SessionGate} holds
 * (REQ-SEC-028, REQ-PATCH-020, ADR-0385).
 *
 * <p>The gates run in their order, and the first that holds the caller refuses; the refusal carries
 * {@code sessionGate: true} besides the gate's code. A gate leaves open its own exempt endpoints
 * and those of every gate before it. Non-{@code /api} and unauthenticated requests pass, and so
 * does a {@code sub} that is not a UUID. Paths are matched as {@link PathPattern}s on the decoded
 * path, so a percent-encoded spelling neither bypasses a gate nor loses an exemption.
 */
@Slf4j
public class SessionGateAccessFilter extends OncePerRequestFilter {

  /** The shared parser for the gates' path patterns. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /** The guarded surface. */
  private static final PathPattern API_SCOPE = PATH_PARSER.parse("/api/**");

  /** App-wide correlation-id response header. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** MDC key the logback pattern renders as the user id. */
  static final String MDC_USER_ID = "userId";

  /**
   * Problem property set to {@code true} on every gate's refusal, by which a client tells a session
   * gate's 403 from any other without knowing the gates' codes.
   */
  static final String SESSION_GATE_PROPERTY = "sessionGate";

  /** Each gate with the endpoints it leaves open, in the gates' order. */
  private final List<Guard> guards;

  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  /**
   * Creates the filter over the gates in the order they run.
   *
   * @param gates the session gates, in their {@code @Order}
   * @param messageSource localizes the refusals' title and detail
   * @param problemResponseFactory assembles the RFC 7807 body
   * @param objectMapper serializes the body
   * @param meterRegistry counts each refusal by its code
   */
  public SessionGateAccessFilter(
      @NotNull List<SessionGate> gates,
      @NotNull MessageSource messageSource,
      @NotNull ProblemResponseFactory problemResponseFactory,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry) {
    this.guards = guards(gates);
    this.messageSource = messageSource;
    this.problemResponseFactory = problemResponseFactory;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    UUID userId = AuthenticatedSubject.idOf(auth).orElse(null);
    if (API_SCOPE.matches(path) && auth != null && userId != null) {
      SessionGate holding = holding(path, auth, userId);
      if (holding != null) {
        holding.refused(userId);
        writeForbidden(request, response, holding, userId);
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Finds the first gate that holds the caller on a path it does not leave open.
   *
   * @param path the decoded request path
   * @param auth the caller's authentication
   * @param userId the caller's user id
   * @return the holding gate, or {@code null} when every gate lets the caller through
   */
  private @Nullable SessionGate holding(
      @NotNull PathContainer path, @NotNull Authentication auth, @NotNull UUID userId) {
    for (Guard guard : guards) {
      if (guard.exempt().stream().noneMatch(pattern -> pattern.matches(path))
          && guard.gate().holds(auth, userId)) {
        return guard.gate();
      }
    }
    return null;
  }

  /**
   * Writes the RFC 7807 403 of a gate, with the {@code userId} MDC key owned for the write.
   *
   * @param request the refused request
   * @param response the response to write into
   * @param gate the gate that refused
   * @param userId the refused caller
   * @throws IOException if serialization or writing fails
   */
  private void writeForbidden(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull SessionGate gate,
      @NotNull UUID userId)
      throws IOException {
    boolean owned = stampUserId(userId);
    try {
      writeForbiddenBody(request, response, gate);
    } finally {
      if (owned) {
        MDC.remove(MDC_USER_ID);
      }
    }
  }

  /**
   * Builds and writes a gate's problem document, logs the refusal and counts it.
   *
   * @param request the refused request
   * @param response the response to write into
   * @param gate the gate that refused
   * @throws IOException if serialization or writing fails
   */
  private void writeForbiddenBody(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull SessionGate gate)
      throws IOException {
    ProblemCode code = gate.problemCode();
    String correlationId = UUID.randomUUID().toString();
    log.debug(
        "Session gate {} refused {} {} [correlationId={}]",
        code.code(),
        request.getMethod(),
        request.getRequestURI(),
        correlationId);
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code.code()).increment();
    Locale locale = request.getLocale();
    String title = messageSource.getMessage(code.titleKey(), null, "Forbidden", locale);
    String detail = messageSource.getMessage(code.detailKey(), null, gate.fallbackDetail(), locale);
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.FORBIDDEN,
            title,
            detail,
            request.getRequestURI(),
            code.typeSuffix(),
            code.code(),
            correlationId);
    problem.setProperty(SESSION_GATE_PROPERTY, true);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }

  /**
   * Puts the caller's id into the MDC unless something already owns that key.
   *
   * @param userId the refused caller
   * @return {@code true} when this call stamped the key and must remove it again
   */
  private static boolean stampUserId(@NotNull UUID userId) {
    String existing = MDC.get(MDC_USER_ID);
    if (existing != null && !existing.isBlank()) {
      return false;
    }
    MDC.put(MDC_USER_ID, userId.toString());
    return true;
  }

  /**
   * Pairs each gate with the endpoints it leaves open: its own and those of every gate before it.
   *
   * @param gates the gates in their order
   * @return the guards in the same order
   */
  private static @NotNull List<Guard> guards(@NotNull List<SessionGate> gates) {
    List<PathPattern> open = new ArrayList<>();
    List<Guard> guards = new ArrayList<>();
    for (SessionGate gate : gates) {
      gate.exemptPaths().stream().map(PATH_PARSER::parse).forEach(open::add);
      guards.add(new Guard(gate, List.copyOf(open)));
    }
    return List.copyOf(guards);
  }

  /**
   * One gate with the endpoints it leaves open.
   *
   * @param gate the gate
   * @param exempt its own exempt endpoints and those of every gate before it
   */
  private record Guard(@NotNull SessionGate gate, @NotNull List<PathPattern> exempt) {}
}
