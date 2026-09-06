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
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.StaleObjectStateException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
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
 *
 * <p><strong>The {@code @Order} is load-bearing — do not remove it.</strong> {@code
 * application.yml} sets {@code spring.mvc.problemdetails.enabled: true}, which makes Spring Boot
 * register its own {@code ProblemDetailsExceptionHandler} advice at {@code @Order(0)} (guarded only
 * by {@code @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)}, and this class
 * deliberately does not extend that base class). An unordered {@code @ControllerAdvice} sits at
 * {@code LOWEST_PRECEDENCE} and therefore LOSES to it for every exception type Spring's advice also
 * declares — {@link MethodArgumentNotValidException}, {@link
 * org.springframework.http.converter.HttpMessageNotReadableException}, {@link
 * org.springframework.web.method.annotation.MethodArgumentTypeMismatchException}, {@link
 * HttpRequestMethodNotSupportedException}, {@link NoResourceFoundException} and {@link
 * ErrorResponseException}. Those responses then ship Spring's bare {@link ProblemDetail}: no {@code
 * code}, no {@code correlationId}, no {@code fieldErrors}, and an untranslated English {@code
 * detail} ({@code "Invalid request content."}). The frontend needs {@code fieldErrors} to place an
 * inline message at the offending field, so it degraded every 400 to a generic "some fields are
 * invalid" toast, and {@link #handleValidationExceptions}'s diagnostic WARN log never ran — a bank
 * employee hit exactly that on a booking-request confirmation and neither they nor the production
 * log could tell which field was rejected.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
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
  private final ProblemResponseFactory problemResponseFactory;
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
    return ProblemResponseFactory.correlationId();
  }

  /** Package prefix that tells this application's stack frames from the framework's. */
  private static final String APP_PACKAGE = "de.greluc.krt.profit.basetool";

  /**
   * Postgres / H2 / generic JDBC constraint name pattern (e.g. "violates foreign key constraint
   * \"fk_xyz\"").
   */
  private static final Pattern CONSTRAINT_NAME_PATTERN =
      Pattern.compile("constraint\\s+\"?([A-Za-z0-9_]+)\"?");

  /**
   * Structured 4xx log line at {@code WARN}, shared by all client-error handlers. See {@link
   * #logProblem(HttpServletRequest, ProblemDetail, String, Map, boolean)} for the format and the
   * level rationale.
   *
   * @param req the servlet request, for method/URI enrichment; never {@code null}.
   * @param pd the problem detail carrying status, {@code code} and {@code correlationId}; never
   *     {@code null}.
   * @param shortMessage the human-readable log prefix; never {@code null}.
   * @param extra optional structured context appended verbatim (no PII); may be {@code null}/empty.
   */
  private void logProblem(
      @org.jetbrains.annotations.NotNull HttpServletRequest req,
      @org.jetbrains.annotations.NotNull ProblemDetail pd,
      @org.jetbrains.annotations.NotNull String shortMessage,
      @org.jetbrains.annotations.Nullable Map<String, ?> extra) {
    logProblem(req, pd, shortMessage, extra, false);
  }

  /**
   * Structured 4xx log line shared by all client-error handlers. Contains HTTP method, URI, status,
   * stable {@code code} and the per-request {@code correlationId} so that a user-reported problem
   * can be located in the log without a reproduction. The optional {@code extra} map is appended
   * verbatim and MUST NOT contain rejected user values (PII protection, see AGENTS.md).
   *
   * <p>Logged at {@code WARN} except when {@code debug} is set: a {@code 401 UNAUTHENTICATED} is
   * the expected, non-actionable default for any unauthenticated caller — internal-TLS health
   * probes hitting {@code /}, bots/scanners, and pre-login navigation all produce it — so it logs
   * at {@code DEBUG} to keep the log free of that steady probe noise (REQ-OBS-001). The {@code
   * basetool_http_error_total{code}} counter preserves the signal regardless of level, and every
   * other 4xx (including {@code 403 ACCESS_DENIED}) stays at {@code WARN}.
   *
   * @param req the servlet request, for method/URI enrichment; never {@code null}.
   * @param pd the problem detail carrying status, {@code code} and {@code correlationId}; never
   *     {@code null}.
   * @param shortMessage the human-readable log prefix; never {@code null}.
   * @param extra optional structured context appended verbatim (no PII); may be {@code null}/empty.
   * @param debug {@code true} to log at {@code DEBUG} instead of {@code WARN}.
   */
  private void logProblem(
      @org.jetbrains.annotations.NotNull HttpServletRequest req,
      @org.jetbrains.annotations.NotNull ProblemDetail pd,
      @org.jetbrains.annotations.NotNull String shortMessage,
      @org.jetbrains.annotations.Nullable Map<String, ?> extra,
      boolean debug) {
    Object cid = pd.getProperties() != null ? pd.getProperties().get("correlationId") : null;
    Object code = pd.getProperties() != null ? pd.getProperties().get("code") : null;
    boolean hasExtra = extra != null && !extra.isEmpty();
    String format =
        hasExtra
            ? "{} for {} {} [status={}, code={}, correlationId={}] {}"
            : "{} for {} {} [status={}, code={}, correlationId={}]";
    Object[] args =
        hasExtra
            ? new Object[] {
              shortMessage, req.getMethod(), req.getRequestURI(), pd.getStatus(), code, cid, extra
            }
            : new Object[] {
              shortMessage, req.getMethod(), req.getRequestURI(), pd.getStatus(), code, cid
            };
    if (debug) {
      log.debug(format, args);
    } else {
      log.warn(format, args);
    }
  }

  private ProblemDetail problem(
      HttpStatus status,
      String title,
      String detail,
      HttpServletRequest req,
      String typeSuffix,
      String code) {
    return problemResponseFactory.problem(
        status,
        title,
        detail,
        req != null ? req.getRequestURI() : null,
        typeSuffix,
        code,
        correlationId());
  }

  // --- 409 Optimistic Locking -----------------------------------------------------------

  /**
   * Maps every flavor of optimistic-locking failure (Spring's wrapper, the JPA spec exception, and
   * Hibernate's stale-state exception) to a single 409 with the stable code {@code
   * OPTIMISTIC_LOCK}. The frontend uses this code to trigger a re-fetch + re-edit prompt rather
   * than surfacing the raw error.
   *
   * <p>The WARN line additionally carries the conflicting entity, its identifier and the compared
   * version pair (see {@link #optimisticLockContext(Exception)}), which is what separates genuine
   * contention from the REQ-FE-003 failure mode where a fragment never re-echoes the bumped {@code
   * version} and the same client 409s forever with nobody else editing.
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
    logProblem(request, pd, "Optimistic locking conflict", optimisticLockContext(ex));
    return toEntity(pd);
  }

  /**
   * Builds the structured {@code extra} context for the 409 WARN line: always the exception's
   * simple name, plus — when the throwable is Spring's {@link
   * ObjectOptimisticLockingFailureException} — the entity class name, the entity identifier and the
   * exception message. For the conflicts the {@code support.OptimisticLock} helpers raise, that
   * message is the {@code expected=<client> persisted=<persisted>} version pair; for a conflict
   * Hibernate raised during flush it is Hibernate's own stale-state text. Either way it is the only
   * field that distinguishes a real concurrent edit from a stale client echo.
   *
   * <p>Deliberately no Mission-style composite section key: every mission section has its own URI,
   * so the affected entity, id and section are already derivable from the method + {@code
   * requestURI} the same line logs. REQ-OBS-004 holds — the identifier is a UUID (or {@code null},
   * or a bounded {@code SystemSetting} key) at every call site and the message is numbers only, so
   * no user-supplied text reaches the log. A {@link LinkedHashMap} keeps the field order stable
   * across log lines (and tolerates a {@code null} identifier, which {@code Map.of} would reject).
   *
   * @param ex the optimistic-locking throwable being mapped to the 409
   * @return the mutable, insertion-ordered context map appended verbatim to the WARN line
   */
  private static Map<String, Object> optimisticLockContext(Exception ex) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("exception", ex.getClass().getSimpleName());
    if (ex instanceof ObjectOptimisticLockingFailureException conflict) {
      context.put("entity", String.valueOf(conflict.getPersistentClassName()));
      context.put("entityId", String.valueOf(conflict.getIdentifier()));
      context.put("versions", String.valueOf(conflict.getMessage()));
    }
    return context;
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
    // DEBUG, not WARN: a 401 is the expected default for any unauthenticated caller (internal-TLS
    // health probes on `/`, bots, pre-login navigation), so WARN-logging it floods the log with
    // steady probe noise. The counter above preserves the signal; 403 stays at WARN (REQ-OBS-001).
    logProblem(
        request,
        pd,
        "Authentication required",
        Map.of("exception", ex.getClass().getSimpleName()),
        true);
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
      // logExtra() is null for most kinds, so this cannot be a copy-constructor.
      java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
      if (ex.logExtra() != null) {
        extra.putAll(ex.logExtra());
      }
      String origin = originOf(ex);
      if (origin != null) {
        extra.put("thrownAt", origin);
      }
      logProblem(request, pd, ex.logLabel(), extra);
    }
    return toEntity(pd);
  }

  /**
   * The source line that refused the request, for the log line that otherwise says only that
   * something was refused.
   *
   * <p>Written after a production {@code 400} could not be diagnosed. {@code Bad request for POST
   * /api/v1/missions/{id}/participants/slim [status=400, code=BAD_REQUEST, correlationId=…]} is the
   * whole line: the caller received the reason in the problem detail, and the operator received
   * none of it. There are 242 {@code BadRequestException} call sites, so "which rule was it" is not
   * a question the URI answers.
   *
   * <p><strong>The line number and not the message, deliberately.</strong> The messages are
   * developer-authored, but a minority interpolate the value that was rejected ({@code "Unknown
   * material type: " + dto.type()}), and logging a rejected user value is exactly what the PII rule
   * forbids (REQ-OBS-004) — it would persist for the retention window and there is no per-call-site
   * audit that could make a blanket copy safe. A file and line carry no runtime data at all, cannot
   * be made to, and answer the same question: the reader opens that line and reads the message in
   * the source.
   *
   * @param ex the refusal.
   * @return {@code File.java:123} for the innermost frame belonging to this application, or {@code
   *     null} when the stack has none (a proxy-only stack, or one stripped by the JVM).
   */
  @org.jetbrains.annotations.Nullable
  private static String originOf(@org.jetbrains.annotations.NotNull Throwable ex) {
    for (StackTraceElement frame : ex.getStackTrace()) {
      if (frame.getClassName().startsWith(APP_PACKAGE) && frame.getFileName() != null) {
        return frame.getFileName() + ":" + frame.getLineNumber();
      }
    }
    return null;
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
      // REQ-OBS-004: an InvalidFormatException/MismatchedInputException message embeds the rejected
      // value verbatim ("... from String \"<value>\": ...") and PiiMasker does not scrub it. Mask
      // any double-quoted segment (the offending user value) while keeping the structural triage
      // text (type, reason, path/line/column) a 400 report needs.
      extra.put("causeMessage", maskQuotedValues(rootCause.getMessage()));
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
   * Masks every double-quoted segment in a Jackson parse-cause message. Jackson embeds the rejected
   * user value in double quotes ({@code ... from String "<value>": ...}) while type names use
   * backticks and field names surface separately as {@code jsonPath}, so replacing quoted runs with
   * {@code "***"} strips the only PII-bearing part (REQ-OBS-004) yet keeps the structural "cannot
   * deserialize type / not a valid X" text that makes a 400 triage-able.
   *
   * @param message the raw {@link Throwable#getMessage()} of the parse cause; may be {@code null}
   * @return the message with quoted values masked, or {@code null} when {@code message} is {@code
   *     null}
   */
  static String maskQuotedValues(String message) {
    if (message == null) {
      return null;
    }
    return message.replaceAll("\"[^\"]*\"", "\"***\"");
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

  // --- upstream HTTP failures ------------------------------------------------------------

  /**
   * Any failure of an outbound {@code RestClient} / {@code RestTemplate} call, mapped to the same
   * {@code 502 EXTERNAL_SERVICE_ERROR} an explicit {@link ExternalServiceException} produces.
   *
   * <p><strong>Written after one reached a user.</strong> Deleting the ingest gateway's stray
   * member row called Keycloak's admin API; the backend's admin client is granted user management
   * but not client inspection, so the call came back {@code 403}. Nothing handled {@link
   * RestClientException}, so a misconfigured <em>upstream permission</em> surfaced to an admin as
   * {@code 500 INTERNAL_ERROR} with "an unexpected error occurred" — a message that describes the
   * one thing it was not. It named neither the dependency nor the cause, and it is
   * indistinguishable from a genuine bug in this application, which is the wrong place to start
   * looking.
   *
   * <p>{@code 502} rather than {@code 500} because that is what happened: a dependency this
   * application calls answered badly, or did not answer. That is also true when the upstream status
   * is a {@code 4xx} — a {@code 401}/{@code 403} from Keycloak means <em>this</em> service
   * presented unusable credentials or lacks a role, which is a configuration fault on our side of
   * the boundary, never the caller's, so it must not be relayed as a client error.
   *
   * <p>Delegates to {@link #handleAppException} rather than building its own body, so the shape,
   * the {@code code} and the disclosure policy cannot drift from the explicit path. That policy is
   * the point here: {@link ErrorDisclosurePolicy#SUPPRESSED} logs the full exception at ERROR with
   * the correlation id and sends the client only a localized generic detail, so an upstream
   * response body — which for an identity provider may carry realm names, client ids or token
   * material — cannot be relayed outward (CWE-209).
   *
   * <p>This is a safety net, not a licence. An adapter that knows what its call meant should still
   * translate the failure itself, with a message worth reading in the log; catching it here only
   * guarantees that forgetting to do so costs a truthful status rather than a mystery.
   *
   * @param ex the failed outbound call — connection, timeout, or any 4xx/5xx from the upstream
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response with status 502
   */
  @ExceptionHandler(RestClientException.class)
  public ResponseEntity<ProblemDetail> handleRestClientException(
      RestClientException ex, HttpServletRequest request) {
    // The message reaches the server log only; SUPPRESSED replaces it for the client. Deliberately
    // the exception's TYPE and not getMessage(), which for HttpClientErrorException embeds the
    // upstream response body — that belongs in the logged stack trace, not in a summary line.
    return handleAppException(
        new ExternalServiceException("Outbound call failed: " + ex.getClass().getSimpleName(), ex),
        request);
  }

  // --- disconnected SSE clients ----------------------------------------------------------

  /**
   * A client that went away while the server was still writing its stream. Not an error, and
   * deliberately answered with <em>nothing at all</em>.
   *
   * <p>Written after it filled the production log. Both SSE endpoints — {@code
   * /api/v1/live-sync/stream} and {@code /api/v1/notifications/stream} — hand the servlet container
   * a response that stays open for minutes, and every ordinary way a client leaves closes it
   * mid-write: a navigation, a closed tab, a phone whose screen went off, a proxy reaping an idle
   * connection. Tomcat reports the broken pipe, Spring wraps it as {@link
   * AsyncRequestNotUsableException}, and before this handler existed it fell through to {@link
   * #handleAllExceptions} and cost <em>two</em> log lines per disconnect:
   *
   * <ul>
   *   <li>an {@code ERROR "Unexpected error at /api/v1/live-sync/stream"} with a full stack trace —
   *       the wrong severity for the most routine thing an SSE endpoint experiences, and it feeds
   *       {@code logback_events_total{level="error"}} and every error-rate alert built on it;
   *   <li>a {@code WARN} from Spring itself, because the {@link ProblemDetail} that handler returns
   *       cannot be written to a response whose content type is already {@code text/event-stream}
   *       ({@code HttpMessageNotWritableException: No converter for [class ProblemDetail] with
   *       preset Content-Type}). Even on a live connection that body would be unparseable to an
   *       {@code EventSource}.
   * </ul>
   *
   * <p>In one 16-hour production window those two lines were 30 of the 50 WARN/ERROR entries the
   * backend produced.
   *
   * <p><strong>The {@code void} return type is the fix, not an oversight.</strong> It tells Spring
   * the exception is handled and leaves the response untouched — there is no socket left to write
   * to, and any attempt to write one is what produced the second line. {@code DEBUG} rather than
   * {@code INFO}: a disconnect carries no information a reader would act on, and an SSE deployment
   * produces one per client per navigation.
   *
   * @param ex the disconnect, kept only for the debug line's cause
   * @param request servlet request, for the URI in the debug line
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public void handleDisconnectedClient(
      AsyncRequestNotUsableException ex, HttpServletRequest request) {
    log.debug("Client disconnected from {}: {}", request.getRequestURI(), ex.getMessage());
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
    // A security refusal raised inside a @PreAuthorize SpEL expression does not arrive as itself:
    // SpEL wraps whatever a bean method threw, so RequestScopeResolver's "no identity" refusal
    // (REQ-SEC-052) would land here and be answered 500 with a stack trace in the log and a 5xx on
    // the alerting - for a request whose only problem is that it carried no login. Unwrap before
    // giving up, so the shape of the answer follows the cause rather than the wrapper.
    AuthenticationException wrapped = rootAuthenticationCause(ex);
    if (wrapped != null) {
      return handleAuthentication(wrapped, request);
    }
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
   * Digs an {@link AuthenticationException} out of a wrapper chain.
   *
   * @param ex the exception that reached the catch-all
   * @return the first {@link AuthenticationException} in its cause chain, or {@code null} when
   *     there is none. The walk is depth-bounded so a self-referencing cause cannot spin.
   */
  @Nullable
  private static AuthenticationException rootAuthenticationCause(Throwable ex) {
    Throwable current = ex;
    for (int depth = 0; current != null && depth < 10; depth++) {
      if (current instanceof AuthenticationException authentication) {
        return authentication;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return null;
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
