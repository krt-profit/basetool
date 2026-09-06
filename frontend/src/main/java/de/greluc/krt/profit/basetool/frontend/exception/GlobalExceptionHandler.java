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

package de.greluc.krt.profit.basetool.frontend.exception;

import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central Spring MVC error mapping for the frontend module.
 *
 * <p>Translates {@link BackendServiceException} (raised by the WebClient layer when the backend
 * returns an RFC7807 Problem+JSON response or when a Resilience4j fallback fires) into localized,
 * user-facing messages using {@link MessageSource}. The stable Problem {@code code} (e.g. {@code
 * OPTIMISTIC_LOCK}, {@code ACCESS_DENIED}, {@code VALIDATION_FAILED}) is mapped onto a well-defined
 * set of {@code error.*} message keys — no hardcoded user-facing strings are emitted (AGENTS.md:
 * DYNAMIC TRANSLATION ONLY).
 *
 * <p>AJAX/JSON requests receive a compact JSON body so that the client-side {@code
 * window.showError(problem)} function can render a KRT-styled toast, while regular navigation
 * requests render the error page with a localized title and explanation.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

  private static final Map<String, String> CODE_TO_MESSAGE_KEY = buildCodeMapping();
  private static final String DEFAULT_MESSAGE_KEY = "error.unexpected";
  private static final String DEFAULT_TITLE_KEY = "error.generic.title";
  private static final String FORBIDDEN_UNAUTHENTICATED_KEY = "error.forbidden.unauthenticated";

  /**
   * Matches request URIs whose final path segment looks like a static-asset filename (a dot
   * followed by a 1-8 character alphanumeric extension, e.g. {@code /missions/common-handlers.js}).
   * Feeds {@link #isAssetShapedUuidMismatch} — crawler requests shaped like this name no resource
   * and are answered with 404 + DEBUG instead of 400 + WARN (REQ-OBS-001).
   */
  private static final Pattern ASSET_SHAPED_PATH = Pattern.compile(".*/[^/]+\\.[A-Za-z0-9]{1,8}$");

  private final MessageSource messageSource;

  /**
   * Renders an i18n-resolved error page (or JSON toast snippet for XHR clients) from an RFC-7807
   * problem returned by the backend, preserving the correlation id for cross-tier debugging.
   */
  @ExceptionHandler(BackendServiceException.class)
  public Object handleBackendServiceException(
      @NotNull BackendServiceException ex,
      @NotNull HttpServletRequest request,
      @NotNull Model model) {
    Locale locale = LocaleContextHolder.getLocale();
    HttpStatus status = resolveStatus(ex.getStatusCode());
    String messageKey = CODE_TO_MESSAGE_KEY.getOrDefault(ex.getProblemCode(), DEFAULT_MESSAGE_KEY);
    boolean unauthenticated = isUnauthenticatedAccessDenial(ex.getProblemCode());
    if (unauthenticated && isForbiddenCode(ex.getProblemCode())) {
      // Replace the generic "you don't have permission" wording with one that tells the user
      // they are not signed in and recommends retrying after authentication — issue #108.
      messageKey = FORBIDDEN_UNAUTHENTICATED_KEY;
    }
    String localizedMessage = resolve(messageKey, locale, ex.getReadableErrorMessage());
    String localizedTitle =
        resolve(titleKeyForStatus(status), locale, resolve(DEFAULT_TITLE_KEY, locale, "Error"));

    log.warn(
        "Backend error propagated to user: code={}, status={}, correlationId={}, uri={}",
        ex.getProblemCode(),
        status.value(),
        ex.getCorrelationId(),
        request.getRequestURI());

    if (wantsJson(request)) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", ex.getProblemCode());
      body.put("status", status.value());
      body.put("title", localizedTitle);
      body.put("message", localizedMessage);
      if (ex.getCorrelationId() != null) {
        body.put("correlationId", ex.getCorrelationId());
      }
      if (!ex.getFieldErrors().isEmpty()) {
        body.put("fieldErrors", ex.getFieldErrors());
      }
      body.put(
          "reloadHint",
          ex.getProblemCode().equals("OPTIMISTIC_LOCK") || ex.getProblemCode().equals("CONFLICT"));
      body.put("unauthenticated", unauthenticated);
      return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    model.addAttribute("error", localizedTitle);
    model.addAttribute("message", localizedMessage);
    model.addAttribute("status", String.valueOf(status.value()));
    model.addAttribute("errorCode", ex.getProblemCode());
    model.addAttribute("unauthenticated", unauthenticated);
    if (ex.getCorrelationId() != null) {
      model.addAttribute("correlationId", ex.getCorrelationId());
    }
    return "error/error";
  }

  /**
   * Bounces a caller whose frontend OAuth2 session lost its usable token through a fresh Keycloak
   * login instead of letting the failure render an empty page / 500 and flood the log (REQ-SEC-012,
   * ADR-0019).
   *
   * <p>For a normal HTML navigation this returns a {@code 302} to the Keycloak authorization
   * endpoint; while the Keycloak SSO session is still alive the re-authentication is transparent
   * and the user lands back in the app with a freshly minted token. For an AJAX/JSON caller (the
   * unread-count poll, a {@code krtFetch} write) it returns {@code 401} carrying the {@code
   * X-Reauthenticate} response header (and a mirrored JSON body) so the shared client-side helper
   * can redirect the whole browser window — an in-place toast would strand the user on a dead
   * session.
   *
   * @param request the current request, used to decide HTML-redirect vs JSON and to prefix the
   *     context path
   * @return a {@code redirect:} view name for HTML, or a {@code 401} {@link ResponseEntity} for
   *     JSON
   */
  @ExceptionHandler(ReauthenticationRequiredException.class)
  public Object handleReauthenticationRequired(@NotNull HttpServletRequest request) {
    String reauthUrl = request.getContextPath() + ReauthenticationRequiredException.REAUTH_PATH;
    log.warn(
        "Re-authentication required for {} {}: redirecting to the Keycloak login flow.",
        request.getMethod(),
        request.getRequestURI());
    if (wantsJson(request)) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", "REAUTH_REQUIRED");
      body.put("status", HttpStatus.UNAUTHORIZED.value());
      body.put("reauthenticate", Boolean.TRUE);
      body.put("location", reauthUrl);
      // Carry the request-scoped correlation id (set by CorrelationIdFilter) so an AJAX caller can
      // quote it to support, mirroring the general BackendServiceException JSON branch — RFC-7807
      // hardening.
      String correlationId = MDC.get("correlationId");
      if (correlationId != null && !correlationId.isBlank()) {
        body.put("correlationId", correlationId);
      }
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header("X-Reauthenticate", reauthUrl)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body);
    }
    return "redirect:" + reauthUrl;
  }

  /** Renders the 404 error page for unmapped static resource / page requests. */
  @ExceptionHandler(NoResourceFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String handleNoResourceFoundException(@NotNull Model model) {
    Locale locale = LocaleContextHolder.getLocale();
    model.addAttribute("error", resolve("error.404.title", locale, "Not Found"));
    model.addAttribute(
        "message",
        resolve("error.404.message", locale, "The requested resource could not be found."));
    model.addAttribute("status", "404");
    return "error/error";
  }

  /**
   * Renders a 400 error page when an MVC path / query parameter cannot be coerced to its declared
   * type. The rejected value is intentionally not logged because it may carry PII.
   *
   * <p>Asset-shaped carve-out (REQ-OBS-001): crawlers occasionally resolve the shared script
   * filenames of {@code fragments/head.html} relative to a page's URL and request e.g. {@code GET
   * /missions/common-handlers.js}, which binds to a {@code /{id}} mapping and fails UUID
   * conversion. Such a path names no resource, so when the required type is {@link UUID} and the
   * final path segment looks like a static-asset filename the handler renders the 404 page instead
   * of a 400 and logs at DEBUG — pure bot noise must not pollute the WARN stream. Every other type
   * mismatch (a garbled id on a real navigation, a broken {@code data-*} echo) keeps the 400 + WARN
   * signal.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Object handleTypeMismatch(
      @NotNull MethodArgumentTypeMismatchException ex,
      @NotNull Model model,
      @NotNull HttpServletRequest request) {
    Locale locale = LocaleContextHolder.getLocale();
    if (isAssetShapedUuidMismatch(ex, request)) {
      // Do NOT log ex.getValue() - request parameter values may carry PII (REQ-OBS-004).
      log.debug(
          "Asset-shaped path failed UUID conversion, treating as 404 for {} {} [parameter={}]",
          request.getMethod(),
          request.getRequestURI(),
          ex.getName());
      if (wantsJson(request)) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "NOT_FOUND");
        body.put("status", 404);
        body.put("title", resolve("error.404.title", locale, "Not Found"));
        body.put(
            "message",
            resolve("error.404.message", locale, "The requested resource could not be found."));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
      }
      // ModelAndView.setStatus overrides the method-level @ResponseStatus(BAD_REQUEST) at render
      // time, so the error page ships with an honest 404 for this branch only.
      ModelAndView notFound = new ModelAndView("error/error", HttpStatus.NOT_FOUND);
      notFound.addObject("error", resolve("error.404.title", locale, "Not Found"));
      notFound.addObject(
          "message",
          resolve("error.404.message", locale, "The requested resource could not be found."));
      notFound.addObject("status", "404");
      return notFound;
    }
    // Do NOT log ex.getValue() - request parameter values may carry PII.
    log.warn(
        "Frontend type mismatch for {} {} [parameter={}, targetType={}]",
        request.getMethod(),
        request.getRequestURI(),
        ex.getName(),
        ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "n/a");
    String message =
        resolve("error.validation.failed", locale, "Invalid parameter " + ex.getName());
    if (wantsJson(request)) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", "VALIDATION_FAILED");
      body.put("status", 400);
      body.put("title", resolve("error.400.title", locale, "Bad Request"));
      body.put("message", message);
      return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body);
    }
    model.addAttribute("error", resolve("error.400.title", locale, "Bad Request"));
    model.addAttribute("message", message);
    model.addAttribute("status", "400");
    return "error/error";
  }

  /**
   * Decides whether a failed path-variable conversion was caused by a static-asset-shaped request
   * path rather than a genuinely malformed identifier. True only when <em>both</em> hold: the
   * declared target type is {@link UUID} (so a legitimately dotted route parameter of another type
   * can never be misclassified — keep this guard if a dotted route ever gains a UUID parameter) and
   * the final path segment carries a short alphanumeric filename extension (e.g. {@code
   * common-handlers.js}). All seven bare {@code /{id}} detail routes take pure UUIDs, which never
   * contain a dot, so a truncated pasted link keeps its 400 + WARN.
   *
   * @param ex the conversion failure raised during handler argument resolution
   * @param request the current request; its URI (not the rejected value) feeds the filename check
   * @return {@code true} when the mismatch should be treated as a 404 for a nonexistent asset
   */
  private static boolean isAssetShapedUuidMismatch(
      @NotNull MethodArgumentTypeMismatchException ex, @NotNull HttpServletRequest request) {
    if (ex.getRequiredType() != UUID.class) {
      return false;
    }
    String uri = request.getRequestURI();
    return uri != null && ASSET_SHAPED_PATH.matcher(uri).matches();
  }

  /**
   * Translates Spring Security authorization failures (raised by {@code @PreAuthorize} checks
   * within controllers) into a 403 response page or a JSON body for AJAX callers, instead of
   * letting them fall through to the generic 500 handler. Without this mapping, a missing role
   * would surface to the user as an opaque "Internal Server Error" — which both hides the real
   * cause and contradicts the standard semantics for HTTP 403.
   *
   * <p>The wording adapts to the security context: an authenticated caller who lacks the required
   * role sees the generic "you do not have permission" copy, while an anonymous caller (no {@link
   * Authentication} or an {@link AnonymousAuthenticationToken}) gets the "please sign in and try
   * again" variant plus an {@code unauthenticated=true} model attribute that flips on the sign-in
   * CTA on the error page (issue #108).
   */
  @ExceptionHandler({
    org.springframework.security.access.AccessDeniedException.class,
    org.springframework.security.authorization.AuthorizationDeniedException.class
  })
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public Object handleAccessDenied(
      @NotNull Exception ex, @NotNull HttpServletRequest request, @NotNull Model model) {
    Locale locale = LocaleContextHolder.getLocale();
    String title = resolve("error.403.title", locale, "Forbidden");
    // The "not signed in at all" branch of issue #108 is gone with its cause: an anonymous caller
    // could reach handleAccessDenied because a permitAll() route still ran @PreAuthorize on the
    // controller method, and that raised AuthorizationDeniedException for the anonymous principal
    // instead of triggering SsoReAuthenticationEntryPoint. There is no permitAll() route with a
    // method gate behind it any more (ADR-0159), so an unauthenticated request meets the entry
    // point and never arrives here.
    //
    // The `unauthenticated` model attribute stays: the backend's own UNAUTHENTICATED problem code
    // still sets it (see handleBackendServiceException), and the error view renders the sign-in CTA
    // off that — a session that expired mid-request is a real case and a different one.
    String messageKey = "error.forbidden";
    boolean anonymous = false;
    String message = resolve(messageKey, locale, "Access denied.");
    log.warn(
        "Access denied for {} {} [exception={}, anonymous={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        ex.getClass().getSimpleName(),
        anonymous,
        ex.getMessage());
    if (wantsJson(request)) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", "ACCESS_DENIED");
      body.put("status", 403);
      body.put("title", title);
      body.put("message", message);
      body.put("unauthenticated", anonymous);
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body);
    }
    model.addAttribute("error", title);
    model.addAttribute("message", message);
    model.addAttribute("status", "403");
    model.addAttribute("unauthenticated", anonymous);
    return "error/error";
  }

  /**
   * Maps a multipart upload that breaches Tomcat's part-count, part-size or total-size limit onto a
   * clean {@code 413 Payload Too Large} instead of letting it fall through to the generic 500
   * handler. The frontend's in-place AJAX writes (epic #571) submit their forms as {@code
   * multipart/form-data} via {@code FormData}, so every form field is a separate multipart part; a
   * large editor (a refinery order with many goods rows, a job order with many items) can exceed
   * Tomcat 11's lowered {@code maxPartCount} default of 10. The connector cap is raised in {@code
   * application.yml}; this handler is the graceful backstop for any residual breach (REQ-FE-009).
   *
   * <p>Spring raises {@link MaxUploadSizeExceededException} during {@code DispatcherServlet}'s
   * multipart resolution — before a handler method is selected — so only a global {@code
   * &#64;ControllerAdvice} (not a controller-local {@code &#64;ExceptionHandler}) can intercept it.
   *
   * @param request the current request, used to decide JSON-vs-HTML and for the diagnostic log line
   * @param model the model populated for the HTML error page
   * @return a {@code 413} JSON body for XHR callers, or the {@code error/error} view name otherwise
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
  public Object handleMaxUploadSizeExceeded(
      @NotNull HttpServletRequest request, @NotNull Model model) {
    Locale locale = LocaleContextHolder.getLocale();
    String title = resolve("error.413.title", locale, "Upload Too Large");
    String message =
        resolve(
            "error.uploadTooLarge",
            locale,
            "The upload exceeded the allowed size or number of parts.");
    // WARN, not ERROR: malformed/oversized client input is not a server fault. Never log the body —
    // it may carry PII.
    log.warn(
        "Upload rejected for {} {}: multipart part-count or size limit exceeded",
        request.getMethod(),
        request.getRequestURI());
    if (wantsJson(request)) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", "UPLOAD_TOO_LARGE");
      body.put("status", HttpStatus.CONTENT_TOO_LARGE.value());
      body.put("title", title);
      body.put("message", message);
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body);
    }
    model.addAttribute("error", title);
    model.addAttribute("message", message);
    model.addAttribute("status", String.valueOf(HttpStatus.CONTENT_TOO_LARGE.value()));
    return "error/error";
  }

  /**
   * Answers a request whose parameters the servlet container refused to parse with a clean {@code
   * 400} instead of letting it reach the {@link Exception} catch-all as a {@code 500} + {@code
   * ERROR} + full stack trace.
   *
   * <p>Tomcat rejects three things at parameter-parse time: a chunk with an empty parameter name
   * ({@code /?=phpinfo()} — a stock PHP-CGI scanner probe), a percent-escape that fails to decode
   * ({@code ?q=100%}, which a real user can produce by pasting a truncated link), and a breach of
   * {@code maxParameterCount}. All three surface on the <em>first</em> {@code getParameter*()} call
   * of the request, which in this module is {@code LocaleChangeInterceptor} reading {@code ?lang} —
   * i.e. on every request, whatever it was addressed to, and again on the {@code /error} dispatch
   * that the resulting failure triggers. {@link
   * de.greluc.krt.profit.basetool.frontend.config.BotProtectionFilter} rejects the first and by far
   * most common case at the edge before anything parses; this handler is the backstop for the other
   * two and for a malformed {@code POST} body.
   *
   * <p>{@code DEBUG}, not {@code ERROR}: the input is entirely client-controlled, so any higher
   * level is a log-flood vector (REQ-OBS-001) — one scanner produced three 200-line ERROR stack
   * traces in eight seconds, which is exactly what {@code LogbackErrorSpike} watches for. The
   * exception message is never logged either: it quotes the offending chunk verbatim, i.e. raw
   * attacker-controlled bytes including possible line breaks (CWE-117, REQ-OBS-004).
   *
   * @param request the current request, used to decide JSON-vs-HTML and for the diagnostic line
   * @param model the model populated for the HTML error page
   * @return a {@code 400} JSON body for XHR callers, or the {@code error/error} view name otherwise
   */
  @ExceptionHandler(InvalidParameterException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Object handleInvalidParameter(@NotNull HttpServletRequest request, @NotNull Model model) {
    Locale locale = LocaleContextHolder.getLocale();
    String title = resolve("error.400.title", locale, "Bad Request");
    String message = resolve("error.malformedRequest", locale, "The request could not be parsed.");
    log.debug(
        "Rejected unparseable request parameters for {} {}",
        request.getMethod(),
        request.getRequestURI());
    if (wantsJson(request)) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", "MALFORMED_REQUEST");
      body.put("status", HttpStatus.BAD_REQUEST.value());
      body.put("title", title);
      body.put("message", message);
      return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body);
    }
    model.addAttribute("error", title);
    model.addAttribute("message", message);
    model.addAttribute("status", String.valueOf(HttpStatus.BAD_REQUEST.value()));
    return "error/error";
  }

  /**
   * Catch-all fallback. Renders a 500 error page; unwraps a {@link BackendServiceException} cause
   * to propagate the backend's status code (e.g. 503 / 504) rather than masking it as 500.
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public String handleException(
      @NotNull Exception e, @NotNull Model model, @NotNull HttpServletRequest request) {
    Locale locale = LocaleContextHolder.getLocale();
    String status = "500";
    String titleKey = DEFAULT_TITLE_KEY;
    String messageKey = DEFAULT_MESSAGE_KEY;

    // Unwrap well-known frameworks to give users a meaningful hint.
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof WebClientResponseException wcre) {
        status = String.valueOf(wcre.getStatusCode().value());
        titleKey = titleKeyForStatus(resolveStatus(wcre.getStatusCode().value()));
        messageKey = CODE_TO_MESSAGE_KEY.getOrDefault("UNKNOWN", DEFAULT_MESSAGE_KEY);
        break;
      }
      cause = cause.getCause();
    }

    log.error(
        "Unexpected frontend error for {} {} [exception={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        e.getClass().getSimpleName(),
        e.getMessage(),
        e);
    model.addAttribute("error", resolve(titleKey, locale, "Unexpected Error"));
    model.addAttribute("message", resolve(messageKey, locale, "An unexpected error occurred."));
    model.addAttribute("status", status);
    return "error/error";
  }

  // ------------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------------

  private static @NotNull HttpStatus resolveStatus(int statusCode) {
    HttpStatus resolved = HttpStatus.resolve(statusCode);
    return resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
  }

  /**
   * Returns {@code true} when the current Spring Security context represents an unauthenticated
   * caller — either no {@link Authentication} at all or an {@link AnonymousAuthenticationToken}
   * supplied by the {@code AnonymousAuthenticationFilter}. Used to decide whether a 403 message
   * should explain "you don't have permission" (authenticated user, insufficient role) versus
   * "please sign in and try again" (anonymous caller hitting a {@code @PreAuthorize} gate behind a
   * {@code permitAll()} route).
   */
  private static boolean isAnonymous() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null || auth instanceof AnonymousAuthenticationToken;
  }

  /**
   * Reports whether the supplied backend problem code denotes a forbidden-style outcome that should
   * be rephrased into "please sign in" when the caller is anonymous. {@code UNAUTHENTICATED} is
   * intentionally handled by {@link #isUnauthenticatedAccessDenial(String)} separately because its
   * own message key ({@code error.unauthenticated}) already speaks about session expiry.
   */
  private static boolean isForbiddenCode(@NotNull String problemCode) {
    return "ACCESS_DENIED".equals(problemCode) || "FORBIDDEN_ROLE".equals(problemCode);
  }

  /**
   * Tells the caller-facing layer whether to render a "sign in" CTA next to the error message.
   * {@code true} for explicit {@code UNAUTHENTICATED} backend problems (session expired) and for
   * {@code ACCESS_DENIED}/{@code FORBIDDEN_ROLE} when the current security context is anonymous —
   * both scenarios resolve with the same user action: re-authenticate and retry.
   */
  private static boolean isUnauthenticatedAccessDenial(@NotNull String problemCode) {
    if ("UNAUTHENTICATED".equals(problemCode)) {
      return true;
    }
    return isForbiddenCode(problemCode) && isAnonymous();
  }

  private static @NotNull String titleKeyForStatus(@NotNull HttpStatus status) {
    return switch (status) {
      case BAD_REQUEST -> "error.400.title";
      case UNAUTHORIZED -> "error.401.title";
      case FORBIDDEN -> "error.403.title";
      case NOT_FOUND -> "error.404.title";
      case CONTENT_TOO_LARGE -> "error.413.title";
      case CONFLICT -> "error.409.title";
      case LOCKED -> "error.423.title";
      case SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT -> "error.503.title";
      default -> DEFAULT_TITLE_KEY;
    };
  }

  private @NotNull String resolve(
      @NotNull String key, @NotNull Locale locale, @NotNull String fallback) {
    try {
      return messageSource.getMessage(key, null, fallback, locale) != null
          ? messageSource.getMessage(key, null, fallback, locale)
          : fallback;
    } catch (Exception ex) {
      return fallback;
    }
  }

  private static boolean wantsJson(@NotNull HttpServletRequest request) {
    String accept = request.getHeader("Accept");
    if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
      return true;
    }
    String requestedWith = request.getHeader("X-Requested-With");
    return "XMLHttpRequest".equalsIgnoreCase(requestedWith);
  }

  private static @NotNull Map<String, String> buildCodeMapping() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("OPTIMISTIC_LOCK", "error.optimisticLock");
    m.put("PESSIMISTIC_LOCK", "error.pessimisticLock");
    m.put("ACCESS_DENIED", "error.forbidden");
    m.put("FORBIDDEN_ROLE", "error.forbidden");
    m.put("UNAUTHENTICATED", "error.unauthenticated");
    m.put("VALIDATION_FAILED", "error.validation.generic");
    m.put("CONSTRAINT_VIOLATION", "error.validation.generic");
    m.put("TYPE_MISMATCH", "error.validation.generic");
    m.put("MALFORMED_REQUEST", "error.validation.generic");
    m.put("NOT_FOUND", "error.notFound");
    m.put("METHOD_NOT_SUPPORTED", "error.methodNotSupported");
    m.put("CONFLICT", "error.conflict.duplicate");
    m.put("DATA_INTEGRITY", "error.conflict.duplicate");
    m.put("LOCKED", "error.pessimisticLock");
    // Kartell bank stable 409 codes (epic #556) — bank.js renders these inline at the
    // booking-modal fields (K1 mockup: 409 never toast-only).
    m.put("BANK_OVERDRAFT", "error.bank.overdraft");
    m.put("BANK_HOLDER_OVERDRAFT", "error.bank.holderOverdraft");
    m.put("BANK_ACCOUNT_NOT_EMPTY", "error.bank.accountNotEmpty");
    m.put("BANK_ACCOUNT_CLOSED", "error.bank.accountClosed");
    m.put("BANK_GRANTEE_MISSING_ROLE", "error.bank.granteeMissingRole");
    m.put("BANK_SELF_TRANSFER", "error.bank.selfTransfer");
    m.put("BANK_ALREADY_REVERSED", "error.bank.alreadyReversed");
    m.put("BANK_HOLDER_INACTIVE", "error.bank.holderInactive");
    // Booking-request lifecycle, split-deposit, justification and fee-inclusive 409s (epic #666 /
    // REQ-BANK-023/-043/-045/-033) plus the retired KRT direct-booking cap (BANK_CARTEL_APPROVAL_
    // REQUIRED, kept defensively though ADR-0109 now files a request instead of throwing it).
    // Without these the codes fell through to error.unexpected ("Ein unerwarteter Fehler ...").
    m.put("BANK_NOT_REVERSIBLE", "error.bank.notReversible");
    m.put("BANK_REQUEST_NOT_PENDING", "error.bank.requestNotPending");
    m.put("BANK_ACCOUNT_HAS_PENDING_REQUESTS", "error.bank.accountHasPendingRequests");
    m.put("BANK_OWNER_APPROVAL_REQUIRED", "error.bank.ownerApprovalRequired");
    m.put("BANK_CARTEL_APPROVAL_REQUIRED", "error.bank.cartelApprovalRequired");
    m.put("BANK_SPLIT_NO_TARGETS", "error.bank.splitNoTargets");
    m.put("BANK_SPLIT_TOO_SMALL", "error.bank.splitTooSmall");
    m.put("BANK_JUSTIFICATION_REQUIRED", "error.bank.justificationRequired");
    m.put("BANK_FEE_EXCEEDS_AMOUNT", "error.bank.feeExceedsAmount");
    m.put("DUPLICATE_ENTITY", "error.conflict.duplicate");
    m.put(BackendServiceException.CODE_SERVICE_UNAVAILABLE, "error.unavailable");
    m.put(BackendServiceException.CODE_BACKEND_TIMEOUT, "error.backendTimeout");
    m.put(BackendServiceException.CODE_UNKNOWN, "error.unexpected");
    return Map.copyOf(m);
  }
}
