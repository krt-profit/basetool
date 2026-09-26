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
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central Spring MVC error mapping for the frontend module.
 *
 * <p>Maps {@link BackendServiceException}s by their stable problem {@code code} to localized {@code
 * error.*} messages from {@link MessageSource}. AJAX/JSON requests get a compact JSON body for the
 * client-side {@code window.showError(problem)} toast; navigations get the error page.
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
   * Sends a caller whose OAuth2 session lost its usable token through a fresh Keycloak login
   * (REQ-SEC-012, ADR-0019).
   *
   * <p>An HTML navigation gets a {@code 302} to the Keycloak authorization endpoint. An AJAX/JSON
   * caller gets a {@code 401} with the {@code X-Reauthenticate} header and a mirrored JSON body, so
   * the client helper can redirect the whole window.
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

  /**
   * Renders the 404 error page for a URL that names neither a handler nor a file: {@link
   * NoResourceFoundException} for a missing file under an asset tree, {@link
   * NoHandlerFoundException} when no handler matched at all.
   *
   * @param model the view model the 404 page renders from; never {@code null}
   * @return the {@code error/error} view name
   */
  @NotNull
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String handleNotFound(@NotNull Model model) {
    Locale locale = LocaleContextHolder.getLocale();
    model.addAttribute("error", resolve("error.404.title", locale, "Not Found"));
    model.addAttribute(
        "message",
        resolve("error.404.message", locale, "The requested resource could not be found."));
    model.addAttribute("status", "404");
    return "error/error";
  }

  /**
   * Renders a 400 error page when a path or query parameter cannot be coerced to its declared type;
   * the rejected value is not logged.
   *
   * <p>A failed {@link UUID} conversion whose last path segment looks like a static-asset filename
   * (e.g. {@code /missions/common-handlers.js}) renders the 404 page instead and logs at DEBUG
   * (REQ-OBS-001).
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Object handleTypeMismatch(
      @NotNull MethodArgumentTypeMismatchException ex,
      @NotNull Model model,
      @NotNull HttpServletRequest request) {
    Locale locale = LocaleContextHolder.getLocale();
    if (isAssetShapedUuidMismatch(ex, request)) {
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
      ModelAndView notFound = new ModelAndView("error/error", HttpStatus.NOT_FOUND);
      notFound.addObject("error", resolve("error.404.title", locale, "Not Found"));
      notFound.addObject(
          "message",
          resolve("error.404.message", locale, "The requested resource could not be found."));
      notFound.addObject("status", "404");
      return notFound;
    }
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
   * Decides whether a failed path-variable conversion came from an asset-shaped path rather than a
   * malformed identifier: the target type must be {@link UUID} and the last path segment must carry
   * a short alphanumeric file extension.
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
   * Translates Spring Security authorization failures into a 403 page, or a JSON body for AJAX
   * callers.
   *
   * <p>An anonymous caller (no {@link Authentication} or an {@link AnonymousAuthenticationToken})
   * gets the sign-in wording and an {@code unauthenticated=true} model attribute that shows the
   * sign-in button; an authenticated one gets the missing-permission wording.
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
    String message = resolve("error.forbidden", locale, "Access denied.");
    log.warn(
        "Access denied for {} {} [exception={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        ex.getClass().getSimpleName(),
        ex.getMessage());
    if (wantsJson(request)) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", "ACCESS_DENIED");
      body.put("status", 403);
      body.put("title", title);
      body.put("message", message);
      body.put("unauthenticated", false);
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body);
    }
    model.addAttribute("error", title);
    model.addAttribute("message", message);
    model.addAttribute("status", "403");
    model.addAttribute("unauthenticated", false);
    return "error/error";
  }

  /**
   * Maps a multipart upload that exceeds Tomcat's part-count, part-size or total-size limit to a
   * {@code 413 Payload Too Large} (REQ-FE-009).
   *
   * <p>The exception is raised during multipart resolution, before a handler is selected, so only a
   * global {@code &#64;ControllerAdvice} can intercept it.
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
   * Answers a request whose parameters Tomcat refused to parse (an empty parameter name, a
   * percent-escape that fails to decode, too many parameters, or a malformed {@code POST} body)
   * with a {@code 400}.
   *
   * <p>Logged at DEBUG without the exception message, because the input is client-controlled
   * (REQ-OBS-001, REQ-OBS-004).
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
   * Answers a {@link ResponseStatusException} with the status it carries instead of letting the
   * {@link Exception} catch-all turn it into a {@code 500}.
   *
   * <p>A {@code 4xx} is logged at DEBUG, a {@code 5xx} at ERROR without a stack trace
   * (REQ-OBS-001). The exception's reason is neither shown nor logged, since it may name an
   * internal backend URL. JSON callers get {@code code}, {@code status}, {@code title}, {@code
   * message} and {@code detail}; others get the error page with the matching status.
   *
   * @param ex the exception carrying the status to answer with
   * @param request the current request, used to decide JSON-vs-HTML and for the diagnostic line
   * @return a JSON {@link ResponseEntity} for XHR callers, or a {@link ModelAndView} of the {@code
   *     error/error} page carrying the status otherwise
   */
  @ExceptionHandler(ResponseStatusException.class)
  public Object handleResponseStatus(
      @NotNull ResponseStatusException ex, @NotNull HttpServletRequest request) {
    Locale locale = LocaleContextHolder.getLocale();
    HttpStatus status = resolveStatus(ex.getStatusCode().value());
    String code = codeForStatus(status);
    String title =
        resolve(titleKeyForStatus(status), locale, resolve(DEFAULT_TITLE_KEY, locale, "Error"));
    String message =
        resolve(
            CODE_TO_MESSAGE_KEY.getOrDefault(code, DEFAULT_MESSAGE_KEY),
            locale,
            "An unexpected error occurred.");
    if (status.is5xxServerError()) {
      log.error(
          "Request {} {} answered with {}", request.getMethod(), request.getRequestURI(), status);
    } else {
      log.debug(
          "Request {} {} answered with {}", request.getMethod(), request.getRequestURI(), status);
    }
    if (wantsJson(request)) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", code);
      body.put("status", status.value());
      body.put("title", title);
      body.put("message", message);
      body.put("detail", message);
      String correlationId = MDC.get("correlationId");
      if (correlationId != null && !correlationId.isBlank()) {
        body.put("correlationId", correlationId);
      }
      return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
    ModelAndView page = new ModelAndView("error/error", status);
    page.addObject("error", title);
    page.addObject("message", message);
    page.addObject("status", String.valueOf(status.value()));
    page.addObject("errorCode", code);
    return page;
  }

  /**
   * Handles a client that disconnected while the response was being written ({@link
   * AsyncRequestNotUsableException} on an async response such as the SSE stream, {@link
   * ClientAbortException} on a plain one) by logging at DEBUG and writing nothing (REQ-OBS-001,
   * REQ-NOTIF-010).
   *
   * <p>The {@code void} return marks the exception handled and leaves the dead response untouched.
   *
   * @param ex the disconnect, kept only for the debug line's exception type
   * @param request the current request, for the method and URI in the debug line
   */
  @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
  public void handleDisconnectedClient(
      @NotNull IOException ex, @NotNull HttpServletRequest request) {
    log.debug(
        "Client disconnected from {} {} [exception={}]",
        request.getMethod(),
        request.getRequestURI(),
        ex.getClass().getSimpleName());
  }

  /**
   * Catch-all fallback. Renders a 500 error page; unwraps a {@link BackendServiceException} cause
   * to propagate the backend's status code (e.g. 503 / 504) rather than masking it as 500.
   */
  @NotNull
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public String handleException(
      @NotNull Exception e, @NotNull Model model, @NotNull HttpServletRequest request) {
    Locale locale = LocaleContextHolder.getLocale();
    String status = "500";
    String titleKey = DEFAULT_TITLE_KEY;
    String messageKey = DEFAULT_MESSAGE_KEY;

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

  private static @NotNull HttpStatus resolveStatus(int statusCode) {
    HttpStatus resolved = HttpStatus.resolve(statusCode);
    return resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
  }

  /**
   * Returns {@code true} when the security context holds no {@link Authentication} or an {@link
   * AnonymousAuthenticationToken}; selects the sign-in wording of the 403 message.
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

  /**
   * Derives the stable problem code for a status that arrived without one — a {@link
   * ResponseStatusException} carries only the status. The codes are the ones the backend itself
   * emits for the same statuses ({@code BackendServiceException#deriveCodeFromStatus}), so a page
   * that branches on {@code code} treats a relayed refusal like a direct one.
   *
   * @param status the resolved response status
   * @return the matching problem code, or {@link BackendServiceException#CODE_UNKNOWN}
   */
  private static @NotNull String codeForStatus(@NotNull HttpStatus status) {
    return switch (status) {
      case BAD_REQUEST -> "VALIDATION_FAILED";
      case UNAUTHORIZED -> "UNAUTHENTICATED";
      case FORBIDDEN -> "ACCESS_DENIED";
      case NOT_FOUND -> "NOT_FOUND";
      case CONFLICT -> "CONFLICT";
      case CONTENT_TOO_LARGE -> "UPLOAD_TOO_LARGE";
      case LOCKED -> "LOCKED";
      case SERVICE_UNAVAILABLE -> BackendServiceException.CODE_SERVICE_UNAVAILABLE;
      case GATEWAY_TIMEOUT -> BackendServiceException.CODE_BACKEND_TIMEOUT;
      default -> BackendServiceException.CODE_UNKNOWN;
    };
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
    m.put("BANK_OVERDRAFT", "error.bank.overdraft");
    m.put("BANK_HOLDER_OVERDRAFT", "error.bank.holderOverdraft");
    m.put("BANK_ACCOUNT_NOT_EMPTY", "error.bank.accountNotEmpty");
    m.put("BANK_ACCOUNT_CLOSED", "error.bank.accountClosed");
    m.put("BANK_GRANTEE_MISSING_ROLE", "error.bank.granteeMissingRole");
    m.put("BANK_SELF_TRANSFER", "error.bank.selfTransfer");
    m.put("BANK_ALREADY_REVERSED", "error.bank.alreadyReversed");
    m.put("BANK_HOLDER_INACTIVE", "error.bank.holderInactive");
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
    m.put("UPLOAD_TOO_LARGE", "error.uploadTooLarge");
    return Map.copyOf(m);
  }
}
