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

package de.greluc.krt.profit.basetool.backend.exception;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.StaleObjectStateException;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central RFC7807 exception handler.
 *
 * <p>Every error response carries the standard Problem Details fields (type, title, status, detail,
 * instance) plus two additional, stable extension fields:
 *
 * <ul>
 *   <li>{@code code} - a stable, machine readable error code used by the frontend to select the
 *       appropriate localized message (e.g. {@code OPTIMISTIC_LOCK}). The code must never change
 *       once published, even if the user-facing {@code detail} text is reworded.
 *   <li>{@code correlationId} - a per-request UUID used to correlate the user-visible error with a
 *       server log entry without leaking stack traces or internal implementation details to the
 *       client.
 * </ul>
 *
 * <p>Expected, user-driven errors (4xx) are logged at {@code WARN}/{@code DEBUG} without a stack
 * trace; unexpected internal errors (5xx) are logged at {@code ERROR} with the full stack trace and
 * the correlation id to aid post-mortem debugging.
 */
@ControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

  /** Stable error codes exposed via the {@code code} extension property. */
  public static final String CODE_OPTIMISTIC_LOCK = "OPTIMISTIC_LOCK";

  public static final String CODE_PESSIMISTIC_LOCK = "PESSIMISTIC_LOCK";
  public static final String CODE_ACCESS_DENIED = "ACCESS_DENIED";
  public static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
  public static final String CODE_VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String CODE_CONSTRAINT_VIOLATION = "CONSTRAINT_VIOLATION";
  public static final String CODE_DUPLICATE_ENTITY = "DUPLICATE_ENTITY";
  public static final String CODE_ILLEGAL_ARGUMENT = "ILLEGAL_ARGUMENT";
  public static final String CODE_BAD_REQUEST = "BAD_REQUEST";
  public static final String CODE_TYPE_MISMATCH = "TYPE_MISMATCH";
  public static final String CODE_DATA_INTEGRITY = "DATA_INTEGRITY_VIOLATION";
  public static final String CODE_NOT_FOUND = "NOT_FOUND";
  public static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
  public static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

  private static final String MDC_CORRELATION_ID = "correlationId";

  private final AppProblemProperties problemProperties;
  private final MessageSource messageSource;
  private final MeterRegistry meterRegistry;

  private URI type(String suffix) {
    return URI.create(problemProperties.getBaseUri() + suffix);
  }

  /**
   * Increments {@code basetool_http_error_total} for a handled error, tagged by its stable RFC-7807
   * code (REQ-OBS-011). The code is a bounded {@code CODE_*} constant, never a path or message, so
   * the label stays low-cardinality and PII-free. Only the security/concurrency codes operators
   * alert on are instrumented — a 409 optimistic-lock storm (contention / locking regression), 401
   * spikes (token breakage) and 403 spikes (authorization misconfig).
   *
   * @param code the stable {@code CODE_*} error code to tag the increment with
   */
  private void countHttpError(String code) {
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code).increment();
  }

  /**
   * Resolve a localized message via Spring's {@link MessageSource} using the locale from {@link
   * LocaleContextHolder} (populated from the {@code Accept-Language} header by Spring's default
   * {@code AcceptHeaderLocaleResolver}). If the key is missing in the bundle, the key itself is
   * returned as the default — which makes missing translations obvious in QA without crashing
   * production. Keys live in {@code backend/src/main/resources/messages*.properties} under the
   * {@code problem.*.title} / {@code problem.*.detail} convention.
   */
  private String tr(String key, Object... args) {
    Locale locale = LocaleContextHolder.getLocale();
    return messageSource.getMessage(key, args, key, locale);
  }

  /**
   * Sentinel returned by {@link MessageSource#getMessage(String, Object[], String, Locale)} when
   * the key is not present in the bundle. The value is chosen so it cannot collide with any real
   * translation: two consecutive NUL characters never appear in human text.
   */
  private static final String MESSAGE_NOT_FOUND_SENTINEL = "\u0000\u0000__missing__\u0000\u0000";

  /**
   * Resolve the {@code detail} text for handlers that fall back to a thrown exception's message
   * (e.g. {@code BadRequestException}, {@code NotFoundException}, {@code
   * DuplicateEntityException}). The behaviour is layered so that legacy throw sites with hardcoded
   * English strings keep working unchanged while new throw sites may pass an i18n key and receive a
   * locale-aware translation for free.
   *
   * <p>Resolution order:
   *
   * <ol>
   *   <li>{@code message} is {@code null} or blank → return the localized {@code fallbackKey} (the
   *       same generic detail this handler has always produced when no specific message was
   *       provided).
   *   <li>{@code message} matches a key in {@link MessageSource} → return the localized
   *       translation. This is the §3.9 migration seam: throw {@code new
   *       BadRequestException("error.refinery_order.location_required")} and the {@code
   *       Accept-Language}-aware translation flows through to the RFC 7807 {@code detail}.
   *   <li>Otherwise → return {@code message} verbatim. Keeps the dozens of existing throw sites
   *       with literal English strings byte-identical on the wire until they are migrated
   *       key-by-key.
   * </ol>
   *
   * <p>The sentinel-based missing-key check is necessary because {@link
   * MessageSource#getMessage(String, Object[], String, Locale)} returns the default unchanged when
   * the key is missing — we need a value that can never appear as a legitimate translation so we
   * can tell "key not in bundle" apart from "key resolved to a string that happens to equal the
   * input".
   */
  private String resolveDetail(
      @org.jetbrains.annotations.Nullable String message,
      @org.jetbrains.annotations.NotNull String fallbackKey) {
    if (message == null || message.isBlank()) {
      return tr(fallbackKey);
    }
    Locale locale = LocaleContextHolder.getLocale();
    String resolved = messageSource.getMessage(message, null, MESSAGE_NOT_FOUND_SENTINEL, locale);
    return MESSAGE_NOT_FOUND_SENTINEL.equals(resolved) ? message : resolved;
  }

  private static ResponseEntity<ProblemDetail> toEntity(ProblemDetail pd) {
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Resolve an existing correlation id from the SLF4J MDC or generate a fresh one. Using MDC makes
   * the same id appear in log lines emitted during the same request.
   */
  private static String correlationId() {
    String existing = MDC.get(MDC_CORRELATION_ID);
    if (existing != null && !existing.isBlank()) {
      return existing;
    }
    return UUID.randomUUID().toString();
  }

  /**
   * Postgres / H2 / generic JDBC constraint name pattern (e.g. "violates foreign key constraint
   * \"fk_xyz\"").
   */
  private static final Pattern CONSTRAINT_NAME_PATTERN =
      Pattern.compile("constraint\\s+\"?([A-Za-z0-9_]+)\"?");

  /**
   * Structured WARN log line shared by all 4xx handlers. Contains HTTP method, URI, status, stable
   * {@code code} and the per-request {@code correlationId} so that a user-reported problem can be
   * located in the log without a reproduction. The optional {@code extra} map is appended verbatim
   * and MUST NOT contain rejected user values (PII protection, see AGENTS.md).
   */
  private void logProblem(
      @org.jetbrains.annotations.NotNull HttpServletRequest req,
      @org.jetbrains.annotations.NotNull ProblemDetail pd,
      @org.jetbrains.annotations.NotNull String shortMessage,
      @org.jetbrains.annotations.Nullable Map<String, ?> extra) {
    Object cid = pd.getProperties() != null ? pd.getProperties().get("correlationId") : null;
    Object code = pd.getProperties() != null ? pd.getProperties().get("code") : null;
    if (extra == null || extra.isEmpty()) {
      log.warn(
          "{} for {} {} [status={}, code={}, correlationId={}]",
          shortMessage,
          req.getMethod(),
          req.getRequestURI(),
          pd.getStatus(),
          code,
          cid);
    } else {
      log.warn(
          "{} for {} {} [status={}, code={}, correlationId={}] {}",
          shortMessage,
          req.getMethod(),
          req.getRequestURI(),
          pd.getStatus(),
          code,
          cid,
          extra);
    }
  }

  private ProblemDetail problem(
      HttpStatus status,
      String title,
      String detail,
      HttpServletRequest req,
      String typeSuffix,
      String code) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setTitle(title);
    pd.setType(type(typeSuffix));
    if (req != null) {
      pd.setInstance(URI.create(req.getRequestURI()));
    }
    pd.setProperty("code", code);
    pd.setProperty("correlationId", correlationId());
    return pd;
  }

  // --- 409 Optimistic Locking -----------------------------------------------------------

  /**
   * Maps every flavor of optimistic-locking failure (Spring's wrapper, the JPA spec exception, and
   * Hibernate's stale-state exception) to a single 409 with the stable code {@code
   * OPTIMISTIC_LOCK}. The frontend uses this code to trigger a re-fetch + re-edit prompt rather
   * than surfacing the raw error.
   *
   * @param ex thrown optimistic-locking exception
   * @param request servlet request, used for the {@code instance} URI and access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler({
    ObjectOptimisticLockingFailureException.class,
    OptimisticLockException.class,
    StaleObjectStateException.class
  })
  public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
      Exception ex, HttpServletRequest request) {
    countHttpError(CODE_OPTIMISTIC_LOCK);
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.optimistic_lock.title"),
            tr("problem.optimistic_lock.detail"),
            request,
            "concurrency-conflict",
            CODE_OPTIMISTIC_LOCK);
    logProblem(
        request,
        pd,
        "Optimistic locking conflict",
        Map.of("exception", ex.getClass().getSimpleName()));
    return toEntity(pd);
  }

  // --- 409 Pessimistic Locking ----------------------------------------------------------

  /**
   * Maps pessimistic-lock acquisition failures (timeout / deadlock-victim) to 409 with code {@code
   * PESSIMISTIC_LOCK}. Distinguishable from {@code OPTIMISTIC_LOCK} so the frontend can surface a
   * different retry hint and operations can monitor the two failure modes separately.
   *
   * @param ex thrown {@link PessimisticLockingFailureException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handlePessimisticLocking(
      PessimisticLockingFailureException ex, HttpServletRequest request) {
    countHttpError(CODE_PESSIMISTIC_LOCK);
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.pessimistic_lock.title"),
            tr("problem.pessimistic_lock.detail"),
            request,
            "pessimistic-lock",
            CODE_PESSIMISTIC_LOCK);
    logProblem(
        request,
        pd,
        "Pessimistic locking conflict",
        Map.of("exception", ex.getClass().getSimpleName()));
    return toEntity(pd);
  }

  // --- 401 Authentication ---------------------------------------------------------------

  /**
   * Maps a missing/invalid bearer token to 401 with code {@code UNAUTHENTICATED}. The exception
   * type is intentionally never echoed in the response body so a malformed-JWT case is
   * indistinguishable on the wire from a missing-token case (information disclosure prevention).
   *
   * @param ex Spring Security {@link AuthenticationException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    countHttpError(CODE_UNAUTHENTICATED);
    ProblemDetail pd =
        problem(
            HttpStatus.UNAUTHORIZED,
            tr("problem.unauthenticated.title"),
            tr("problem.unauthenticated.detail"),
            request,
            "unauthenticated",
            CODE_UNAUTHENTICATED);
    logProblem(
        request, pd, "Authentication required", Map.of("exception", ex.getClass().getSimpleName()));
    return toEntity(pd);
  }

  // --- 403 Authorization ----------------------------------------------------------------

  /**
   * Covers both the legacy {@link AccessDeniedException} and the newer Spring Security 6+ {@link
   * AuthorizationDeniedException} raised by method security. The detail is kept deliberately
   * generic: the required role is not echoed back to avoid information disclosure to clients who
   * are already not authorized for this resource.
   *
   * @param ex thrown access-denied exception (legacy or Spring Security 6+ method-security flavor)
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response with 403 / code {@code ACCESS_DENIED}
   */
  @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
  public ResponseEntity<ProblemDetail> handleAccessDenied(
      Exception ex, HttpServletRequest request) {
    countHttpError(CODE_ACCESS_DENIED);
    // Do NOT echo ex.getMessage() to clients (may contain SpEL or required-role hints) -
    // keep the user-facing detail generic and put the diagnostic info into the WARN log only.
    final ProblemDetail pd =
        problem(
            HttpStatus.FORBIDDEN,
            tr("problem.access_denied.title"),
            tr("problem.access_denied.detail"),
            request,
            "access-denied",
            CODE_ACCESS_DENIED);
    Map<String, Object> extra = new HashMap<>();
    extra.put("exception", ex.getClass().getSimpleName());
    if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
      extra.put("reason", ex.getMessage());
    }
    if (ex instanceof AuthorizationDeniedException ade && ade.getAuthorizationResult() != null) {
      extra.put("authorizationResult", String.valueOf(ade.getAuthorizationResult()));
    }
    logProblem(request, pd, "Access denied", extra);
    return toEntity(pd);
  }

  // --- 400 Validation (@Valid on @RequestBody) ------------------------------------------

  /**
   * Maps a failed {@code @Valid @RequestBody} into a 400 with code {@code VALIDATION_FAILED}.
   *
   * <p>Builds two views of the per-field errors: a legacy map ({@code errors}: field → message) for
   * older frontend code and a structured list ({@code fieldErrors}: array of {@code {field,
   * message}}) for newer consumers. Logs the field+message pairs but never the rejected value, so
   * PII (handles, emails, recipient names) does not leak into log files.
   *
   * @param ex Spring's bind-result wrapper with the per-field constraint violations
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidationExceptions(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> errorsByField = new HashMap<>();
    List<Map<String, String>> errors = new ArrayList<>();
    List<String> logSummary = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(
            fieldError -> {
              String field = fieldError.getField();
              String message = fieldError.getDefaultMessage();
              errorsByField.put(field, message);
              Map<String, String> entry = new HashMap<>();
              entry.put("field", field);
              entry.put("message", message);
              errors.add(entry);
              // Log only field name + violated constraint message; never the rejected value to
              // avoid leaking PII (handles, emails, recipient names) into the log files.
              logSummary.add(field + "=" + message + " (code=" + fieldError.getCode() + ")");
            });
    ex.getBindingResult()
        .getGlobalErrors()
        .forEach(
            globalError ->
                logSummary.add(
                    "[" + globalError.getObjectName() + "] " + globalError.getDefaultMessage()));
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.validation_failed.title"),
            tr("problem.validation_failed.detail"),
            request,
            "constraint-violation",
            CODE_VALIDATION_FAILED);
    // Keep the legacy map-shaped "errors" for backwards compatibility AND expose a
    // structured list under "fieldErrors" for new consumers.
    pd.setProperty("errors", errorsByField);
    pd.setProperty("fieldErrors", errors);
    // WARN-level structured log so a 400 VALIDATION_FAILED can be analysed in production
    // without having to ask the user to reproduce the request (see CHANGELOG / log.txt L1467).
    log.warn(
        "Validation failed for {} {} [correlationId={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        pd.getProperties() != null ? pd.getProperties().get("correlationId") : null,
        logSummary);
    return toEntity(pd);
  }

  // --- 400 Validation (@Validated on path/query params, jakarta constraints) ------------

  /**
   * Maps a failed {@code @Validated} on path/query parameters or service-level Jakarta constraints
   * into a 400 with code {@code CONSTRAINT_VIOLATION}.
   *
   * <p>Distinct from {@link #handleValidationExceptions} (different exception type, different
   * source of constraints), but exposes the same {@code fieldErrors} list so the frontend can treat
   * both uniformly. The {@code field} value comes from the constraint's property path, which on
   * method parameters looks like {@code methodName.argName}.
   *
   * @param ex thrown {@link ConstraintViolationException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    List<String> logSummary = new ArrayList<>();
    ex.getConstraintViolations()
        .forEach(
            v -> {
              String field = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
              Map<String, String> entry = new HashMap<>();
              entry.put("field", field);
              entry.put("message", v.getMessage());
              errors.add(entry);
              // Log field + message only; the invalid value may contain user input/PII.
              logSummary.add(field + "=" + v.getMessage());
            });
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.constraint_violation.title"),
            tr("problem.constraint_violation.detail"),
            request,
            "constraint-violation",
            CODE_CONSTRAINT_VIOLATION);
    pd.setProperty("fieldErrors", errors);
    log.warn(
        "Constraint violation for {} {} [correlationId={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        pd.getProperties() != null ? pd.getProperties().get("correlationId") : null,
        logSummary);
    return toEntity(pd);
  }

  // --- AppException dispatch (S4, #910) --------------------------------------------------

  /**
   * Single dispatch handler for every sealed {@link AppException} subtype except {@link
   * NotFoundException} (whose handler stays separate — see {@link #handleNotFound}): {@link
   * BadRequestException}, {@link BankConflictException}, {@link BusinessConflictException}, {@link
   * DuplicateEntityException}, {@link EntityInUseException}, {@link ExternalServiceException} and
   * {@link ReportGenerationException}. Replaces the seven near-identical handlers these types used
   * to each carry — the status/code/i18n-keys/type-suffix/log-label now live on the exception
   * itself (via {@link AppExceptionKind} for the six fixed types, computed per-instance for {@link
   * BankConflictException}), so this method only needs to read the accessors.
   *
   * <p>{@link AppException#disclosurePolicy()} forks the one genuinely different behaviour, applied
   * after the common {@link #problem} construction: {@link ErrorDisclosurePolicy#SUPPRESSED} (only
   * {@code ExternalServiceException} / {@code ReportGenerationException}) never consults {@code
   * ex.getMessage()} for the client-visible detail — it may carry upstream response bodies or
   * library-internal paths (CWE-209) — and logs the full exception at ERROR with the correlation id
   * instead of the standard WARN, skipping {@link AppException#extraProperties()} (no suppressed
   * subtype carries any).
   *
   * @param ex the thrown {@link AppException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(AppException.class)
  public ResponseEntity<ProblemDetail> handleAppException(
      AppException ex, HttpServletRequest request) {
    boolean suppressed = ex.disclosurePolicy() == ErrorDisclosurePolicy.SUPPRESSED;
    String detail =
        suppressed ? tr(ex.detailKey()) : resolveDetail(ex.getMessage(), ex.detailKey());
    ProblemDetail pd =
        problem(ex.status(), tr(ex.titleKey()), detail, request, ex.typeSuffix(), ex.code());
    if (suppressed) {
      String cid = correlationId();
      MDC.put(MDC_CORRELATION_ID, cid);
      try {
        log.error("{} at {} [correlationId={}]", ex.logLabel(), request.getRequestURI(), cid, ex);
      } finally {
        MDC.remove(MDC_CORRELATION_ID);
      }
      // Overwrite the freshly generated correlation id with the one we used for the log
      // line so the client-visible id matches the server log entry exactly.
      pd.setProperty("correlationId", cid);
    } else {
      ex.extraProperties().forEach(pd::setProperty);
      logProblem(request, pd, ex.logLabel(), ex.logExtra());
    }
    return toEntity(pd);
  }

  // --- 400 Illegal arguments / malformed bodies -----------------------------------------

  /**
   * Maps {@link IllegalArgumentException} (typically: unknown sort field, malformed UUID-not-from-
   * the-binder, library guard) to 400 with code {@code ILLEGAL_ARGUMENT}. The exception message is
   * never echoed in the response body because it can contain SQL fragments, internal paths or raw
   * inputs that triggered a parser — the message lives in the server log only.
   *
   * @param ex thrown {@link IllegalArgumentException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    // ex.getMessage() can contain implementation details (SQL fragments, internal
    // paths, raw inputs that triggered a parser, ...). Return a generic detail to
    // the client and keep the real message only in the server log.
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.illegal_argument.title"),
            tr("problem.illegal_argument.detail"),
            request,
            "invalid-argument",
            CODE_ILLEGAL_ARGUMENT);
    logProblem(
        request,
        pd,
        "IllegalArgumentException",
        Map.of("exceptionMessage", String.valueOf(ex.getMessage())));
    return toEntity(pd);
  }

  /**
   * Maps {@link IllegalStateException} (service-layer invariant guard — typically the multi-tenant
   * cross-staffel pre-write check in {@code JobOrderHandoverService}, MULTI_SQUADRON_PLAN.md
   * section 4.4) to 400. The exception message is generic and safe to echo because these checks
   * never embed user input or implementation details — they describe the violated invariant (e.g.
   * "Inventory item does not belong to this JobOrder"). The message goes through {@link
   * #resolveDetail(String, String)} so callers can keep raising it with either an i18n key or a
   * literal string.
   *
   * @param ex thrown {@link IllegalStateException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> handleIllegalState(
      IllegalStateException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.bad_request.title"),
            resolveDetail(ex.getMessage(), "problem.bad_request.detail"),
            request,
            "bad-request",
            CODE_BAD_REQUEST);
    logProblem(request, pd, "Illegal state", null);
    return toEntity(pd);
  }

  /**
   * Catch-all for {@link ResponseStatusException} thrown by callers (or framework code) that prefer
   * Spring's lightweight status-only exception. Maps the carried status to a matching RFC&nbsp;7807
   * problem; the {@code code} is derived from the status via {@link #codeForStatus(HttpStatus)}.
   *
   * @param ex thrown {@link ResponseStatusException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest request) {
    HttpStatus status =
        (ex.getStatusCode() instanceof HttpStatus hs)
            ? hs
            : HttpStatus.valueOf(ex.getStatusCode().value());
    String code = codeForStatus(status);
    // M-7: never echo {@code ex.getMessage()} verbatim. Spring's {@link
    // ResponseStatusException#getMessage()} synthesises "&lt;status&gt; &lt;phrase&gt;
    // \"&lt;reason&gt;\";
    // nested exception is …" — the "; nested exception is" suffix carries the underlying
    // exception's class name and message, which on WebClient-relay paths (see {@code
    // HangarImportProxyController.forwardImport}) wraps the upstream Spring/Hibernate error
    // verbatim. Echoing that leaks SQL constraint names / class FQDNs / internal paths (CWE-209).
    // Use the caller-friendly {@code reason} only, or the bare status reason phrase as fallback.
    String safeDetail = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
    ProblemDetail pd =
        problem(status, safeDetail, safeDetail, request, Integer.toString(status.value()), code);
    logProblem(
        request, pd, "ResponseStatusException", Map.of("reason", String.valueOf(ex.getReason())));
    return toEntity(pd);
  }

  /**
   * Handles Spring 6's {@link ErrorResponseException} (used by some MVC infrastructure code that
   * carries its own {@code ProblemDetail} body). Preserves the existing body when present, only
   * filling in {@code instance}, {@code code} and {@code correlationId} if they are missing, so we
   * never overwrite richer information Spring already attached.
   *
   * @param ex thrown {@link ErrorResponseException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response (possibly augmented from the carried body)
   */
  @ExceptionHandler(ErrorResponseException.class)
  public ResponseEntity<ProblemDetail> handleErrorResponseException(
      ErrorResponseException ex, HttpServletRequest request) {
    HttpStatus status =
        (ex.getStatusCode() instanceof HttpStatus hs)
            ? hs
            : HttpStatus.valueOf(ex.getStatusCode().value());
    ProblemDetail base = ex.getBody();
    if (base == null) {
      base =
          problem(
              status,
              status.getReasonPhrase(),
              ex.getMessage(),
              request,
              Integer.toString(status.value()),
              codeForStatus(status));
    } else {
      base.setInstance(URI.create(request.getRequestURI()));
      if (base.getProperties() == null || !base.getProperties().containsKey("code")) {
        base.setProperty("code", codeForStatus(status));
      }
      if (base.getProperties() == null || !base.getProperties().containsKey("correlationId")) {
        base.setProperty("correlationId", correlationId());
      }
    }
    logProblem(
        request,
        base,
        "ErrorResponseException",
        Map.of("exception", ex.getClass().getSimpleName()));
    return toEntity(base);
  }

  /**
   * Maps a malformed request body (Jackson parse errors, missing closing brace, wrong type for a
   * field) to 400 with code {@code BAD_REQUEST}.
   *
   * <p>Extracts the JSON path of the offending node from the Jackson cause when available — that
   * path is the single most useful triage signal for a "400 BAD_REQUEST" report and does NOT
   * contain user values, only field names. The raw user values stay out of the log because Jackson
   * masks them in the cause message.
   *
   * @param ex Spring's wrapper around the Jackson parse failure
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(
      org.springframework.http.converter.HttpMessageNotReadableException ex,
      HttpServletRequest request) {
    // Most-specific cause carries the actual JSON parse error (path, line, column) which is the
    // information needed to triage "400 BAD_REQUEST" reports without a reproduction.
    Throwable rootCause = ex.getMostSpecificCause();
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.unreadable_body.title"),
            tr("problem.unreadable_body.detail"),
            request,
            "bad-request",
            CODE_BAD_REQUEST);
    Map<String, Object> extra = new HashMap<>();
    extra.put("contentType", String.valueOf(request.getContentType()));
    if (rootCause != null) {
      extra.put("rootCause", rootCause.getClass().getSimpleName());
      // Cause message may be long but does NOT contain raw user values (Jackson masks them);
      // it carries the JSON path/line/column needed for triage.
      extra.put("causeMessage", rootCause.getMessage());
      if (rootCause instanceof tools.jackson.databind.DatabindException jme
          && jme.getPath() != null) {
        StringBuilder path = new StringBuilder();
        jme.getPath()
            .forEach(
                ref -> {
                  if (ref.getPropertyName() != null) {
                    if (path.length() > 0) {
                      path.append('.');
                    }
                    path.append(ref.getPropertyName());
                  } else if (ref.getIndex() >= 0) {
                    path.append('[').append(ref.getIndex()).append(']');
                  }
                });
        if (path.length() > 0) {
          extra.put("jsonPath", path.toString());
        }
      }
    }
    logProblem(request, pd, "Unreadable request body", extra);
    return toEntity(pd);
  }

  /**
   * Maps a Spring {@link DataIntegrityViolationException} (FK violation, NOT NULL violation,
   * unique-constraint violation that wasn't pre-checked in the service) to 409 with code {@code
   * DATA_INTEGRITY_VIOLATION}. Tries to extract the offending constraint name from the cause
   * message using {@link #CONSTRAINT_NAME_PATTERN}; only the first line of the cause message goes
   * into the log because subsequent lines may contain row data.
   *
   * @param ex thrown {@link DataIntegrityViolationException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.data_integrity.title"),
            tr("problem.data_integrity.detail"),
            request,
            "data-integrity-violation",
            CODE_DATA_INTEGRITY);
    Throwable cause = ex.getMostSpecificCause();
    Map<String, Object> extra = new HashMap<>();
    if (cause != null) {
      extra.put("rootCause", cause.getClass().getSimpleName());
      String msg = cause.getMessage();
      if (msg != null) {
        Matcher m = CONSTRAINT_NAME_PATTERN.matcher(msg);
        if (m.find()) {
          extra.put("constraint", m.group(1));
        }
        // First line of the cause message - usually the SQL state + constraint summary,
        // safe to log; subsequent lines may contain row data and are dropped.
        int nl = msg.indexOf('\n');
        extra.put("causeMessage", nl > 0 ? msg.substring(0, nl) : msg);
      }
    }
    logProblem(request, pd, "Data integrity violation", extra);
    return toEntity(pd);
  }

  /**
   * Maps a path/query-parameter binding failure (e.g. {@code "abc"} where a UUID is expected) to
   * 400 with code {@code TYPE_MISMATCH}. The rejected raw value is never logged because path/query
   * parameters can carry PII like usernames or emails; only the parameter name and the target type
   * are logged.
   *
   * @param ex Spring's binder wrapper for the conversion failure
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
      HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.type_mismatch.title"),
            tr("problem.type_mismatch.detail", ex.getName()),
            request,
            "type-mismatch",
            CODE_TYPE_MISMATCH);
    // Do NOT log ex.getValue() - request parameter values may carry PII (handles, mails, IDs).
    Map<String, Object> extra = new HashMap<>();
    extra.put("parameter", ex.getName());
    extra.put(
        "targetType", ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "n/a");
    logProblem(request, pd, "Type mismatch", extra);
    return toEntity(pd);
  }

  /**
   * Maps a 405 from Spring MVC to a localized RFC&nbsp;7807 response with code {@code
   * METHOD_NOT_ALLOWED} (e.g. {@code POST} to an endpoint that only declares {@code GET}). Logs the
   * supported methods so reverse-proxy misrouting is easy to spot.
   *
   * @param ex Spring's method-not-supported exception
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.METHOD_NOT_ALLOWED,
            tr("problem.method_not_allowed.title"),
            tr("problem.method_not_allowed.detail", ex.getMethod()),
            request,
            "method-not-allowed",
            CODE_METHOD_NOT_ALLOWED);
    logProblem(
        request,
        pd,
        "Method not allowed",
        Map.of(
            "supportedMethods",
            String.valueOf(
                java.util.Arrays.toString(
                    ex.getSupportedMethods() == null ? new String[0] : ex.getSupportedMethods()))));
    return toEntity(pd);
  }

  // --- 404 Not Found --------------------------------------------------------------------

  /**
   * Handles both the application-specific {@link NotFoundException} as well as common JPA / JDK
   * flavors of "not found" ({@link EntityNotFoundException}, {@link NoSuchElementException}) and
   * Spring's {@link NoResourceFoundException} (static resources / unknown paths) so that none of
   * them accidentally bubble up into the generic 500 handler. The status/code/title/detail literals
   * are read from {@link AppExceptionKind#NOT_FOUND} — the same constant {@link NotFoundException}
   * itself delegates to — rather than a second, independently-hardcoded copy, so the two can never
   * drift apart.
   */
  @ExceptionHandler({
    NotFoundException.class,
    EntityNotFoundException.class,
    NoSuchElementException.class,
    NoResourceFoundException.class
  })
  public ResponseEntity<ProblemDetail> handleNotFound(Exception ex, HttpServletRequest request) {
    // 404 is an expected, user-driven outcome (e.g. stale links, external crawlers hitting
    // deleted mission IDs). Log at DEBUG only and do NOT include the stacktrace to keep
    // the error log focused on real problems.
    log.debug("Not found at {}: {}", request.getRequestURI(), ex.getMessage());
    ProblemDetail pd =
        problem(
            AppExceptionKind.NOT_FOUND.status(),
            tr(AppExceptionKind.NOT_FOUND.titleKey()),
            resolveDetail(ex.getMessage(), AppExceptionKind.NOT_FOUND.detailKey()),
            request,
            AppExceptionKind.NOT_FOUND.typeSuffix(),
            AppExceptionKind.NOT_FOUND.code());
    return toEntity(pd);
  }

  // --- 500 fallback ---------------------------------------------------------------------

  /**
   * Last-resort fallback for any {@link Exception} not matched by a more specific handler above.
   * Returns a 500 with code {@code INTERNAL_ERROR} and a localized generic detail. The full
   * stacktrace is logged at ERROR with the per-request correlation id so the client-visible id and
   * the server log line can be tied together without leaking implementation details to the
   * response.
   *
   * @param ex any unhandled exception
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response with status 500
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleAllExceptions(
      Exception ex, HttpServletRequest request) {
    String cid = correlationId();
    // Make sure the correlation id is the same for both the log line and the response.
    MDC.put(MDC_CORRELATION_ID, cid);
    try {
      log.error("Unexpected error at {} [correlationId={}]", request.getRequestURI(), cid, ex);
    } finally {
      MDC.remove(MDC_CORRELATION_ID);
    }
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, tr("problem.internal_error.detail"));
    pd.setTitle(tr("problem.internal_error.title"));
    pd.setType(type("internal-error"));
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("code", CODE_INTERNAL_ERROR);
    pd.setProperty("correlationId", cid);
    return toEntity(pd);
  }

  /**
   * Map an arbitrary HTTP status to a reasonable default error code. Used for {@link
   * ResponseStatusException} / {@link ErrorResponseException} where the original cause is not known
   * to this handler.
   */
  private static String codeForStatus(HttpStatus status) {
    return switch (status) {
      case UNAUTHORIZED -> CODE_UNAUTHENTICATED;
      case FORBIDDEN -> CODE_ACCESS_DENIED;
      case NOT_FOUND -> CODE_NOT_FOUND;
      case CONFLICT -> CODE_DUPLICATE_ENTITY;
      case METHOD_NOT_ALLOWED -> CODE_METHOD_NOT_ALLOWED;
      case BAD_REQUEST -> CODE_BAD_REQUEST;
      default -> status.is5xxServerError() ? CODE_INTERNAL_ERROR : CODE_BAD_REQUEST;
    };
  }
}
