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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;

/**
 * Unit tests for {@link GlobalExceptionHandler} verifying that backend Problem+JSON error {@code
 * code} values are correctly mapped onto localized message keys for both HTML (error page) and JSON
 * (AJAX/toast) rendering paths. Covers the main error classes from tasks 7-12: optimistic lock ->
 * reload hint, access denied, validation with field errors, service-unavailable / backend timeout.
 */
class GlobalExceptionHandlerTest {

  private MessageSource messageSource;
  private GlobalExceptionHandler handler;

  @BeforeEach
  void setUp() {
    messageSource = Mockito.mock(MessageSource.class);
    // Default: resolver returns the key itself — makes assertions simple.
    when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    handler = new GlobalExceptionHandler(messageSource);
    // Default to an authenticated principal so legacy 403 assertions still target the
    // "authenticated but lacks role" path. The anonymous branch is exercised by dedicated
    // tests further down that clear or replace the context.
    setAuthenticatedUser();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  private static void setAuthenticatedUser() {
    TestingAuthenticationToken auth =
        new TestingAuthenticationToken(
            "test-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    auth.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  private static void setAnonymousUser() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
  }

  private HttpServletRequest jsonRequest() {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
    when(req.getRequestURI()).thenReturn("/unit-test");
    return req;
  }

  private HttpServletRequest htmlRequest() {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("text/html");
    when(req.getRequestURI()).thenReturn("/unit-test");
    return req;
  }

  @Test
  void optimisticLock_ShouldRenderJsonWithReloadHintAndLocalizedKey() {
    BackendServiceException ex =
        new BackendServiceException(
            "conflict", null, 409, "OPTIMISTIC_LOCK", "corr-1", List.of(), "detail");

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("OPTIMISTIC_LOCK", body.get("code"));
    assertEquals(409, body.get("status"));
    assertEquals("error.optimisticLock", body.get("message"));
    assertEquals("error.409.title", body.get("title"));
    assertEquals("corr-1", body.get("correlationId"));
    assertEquals(Boolean.TRUE, body.get("reloadHint"));
  }

  @Test
  void accessDenied_ShouldMapToForbiddenKey_AndRenderErrorPageForHtml() {
    BackendServiceException ex =
        new BackendServiceException(
            "forbidden", null, 403, "ACCESS_DENIED", "corr-403", List.of(), null);
    Model model = new ConcurrentModel();

    Object result = handler.handleBackendServiceException(ex, htmlRequest(), model);

    assertEquals("error/error", result);
    assertEquals("error.403.title", model.getAttribute("error"));
    assertEquals("error.forbidden", model.getAttribute("message"));
    assertEquals("403", model.getAttribute("status"));
    assertEquals("ACCESS_DENIED", model.getAttribute("errorCode"));
    assertEquals("corr-403", model.getAttribute("correlationId"));
  }

  @Test
  void validationFailed_ShouldPropagateFieldErrorsInJsonBody() {
    List<BackendServiceException.FieldError> fieldErrors =
        List.of(
            new BackendServiceException.FieldError("name", "must not be blank"),
            new BackendServiceException.FieldError("amount", "must be positive"));
    BackendServiceException ex =
        new BackendServiceException(
            "validation", null, 400, "VALIDATION_FAILED", "corr-400", fieldErrors, null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("VALIDATION_FAILED", body.get("code"));
    assertEquals("error.validation.generic", body.get("message"));
    assertTrue(body.containsKey("fieldErrors"));
    @SuppressWarnings("unchecked")
    List<BackendServiceException.FieldError> out =
        (List<BackendServiceException.FieldError>) body.get("fieldErrors");
    assertEquals(2, out.size());
    assertEquals("name", out.get(0).field());
  }

  @Test
  void serviceUnavailable_ShouldMapToUnavailableKey() {
    BackendServiceException ex =
        new BackendServiceException(
            "cb open",
            null,
            503,
            BackendServiceException.CODE_SERVICE_UNAVAILABLE,
            null,
            List.of(),
            null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("error.unavailable", body.get("message"));
  }

  @Test
  void backendTimeout_ShouldMapToBackendTimeoutKey() {
    BackendServiceException ex =
        new BackendServiceException(
            "timeout",
            null,
            504,
            BackendServiceException.CODE_BACKEND_TIMEOUT,
            null,
            List.of(),
            null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("error.backendTimeout", body.get("message"));
  }

  @Test
  void unknownCode_ShouldFallBackToUnexpectedKey() {
    BackendServiceException ex =
        new BackendServiceException(
            "boom", null, 500, BackendServiceException.CODE_UNKNOWN, "corr-500", List.of(), null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("error.unexpected", body.get("message"));
    assertEquals("corr-500", body.get("correlationId"));
  }

  // ─── Ajax-detection via X-Requested-With ────────────────────────────────

  @Test
  void wantsJson_viaXRequestedWith_isHonoured() {
    // Given — a non-Accept-JSON request that's clearly Ajax
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("text/html");
    when(req.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
    when(req.getRequestURI()).thenReturn("/ajax");

    BackendServiceException ex =
        new BackendServiceException("x", null, 400, "VALIDATION_FAILED", null, List.of(), null);

    Object result = handler.handleBackendServiceException(ex, req, new ConcurrentModel());

    // The X-Requested-With header alone must promote the response to JSON
    // so legacy XHR callers don't accidentally get a full HTML error page.
    assertInstanceOf(ResponseEntity.class, result);
  }

  // ─── handleBackendServiceException — model branch (HTML) details ────────

  @Test
  void backendException_html_withoutCorrelationId_doesNotAddCorrelationIdAttribute() {
    // Given — no correlation id; the controller must NOT push a `null`
    // attribute that would render as the literal "null" string.
    BackendServiceException ex =
        new BackendServiceException(
            "x", null, 404, "NOT_FOUND", /*correlationId*/ null, List.of(), null);
    Model model = new ConcurrentModel();

    handler.handleBackendServiceException(ex, htmlRequest(), model);

    assertEquals(null, model.getAttribute("correlationId"));
  }

  @Test
  void backendException_json_withoutCorrelationId_doesNotIncludeKey() {
    // Same contract for the JSON branch: an absent correlation id must
    // NOT surface as `"correlationId":null` — the JSON body is consumed
    // by the toast renderer which checks `key in body`.
    BackendServiceException ex =
        new BackendServiceException("x", null, 404, "NOT_FOUND", null, List.of(), null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    ResponseEntity<?> response = (ResponseEntity<?>) result;
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertTrue(!body.containsKey("correlationId"), "correlationId key must be absent, not null");
  }

  @Test
  void backendException_unmappedStatusCode_fallsBackTo500() {
    // Given an unusual status code that HttpStatus.resolve() doesn't know.
    // The handler must fall back to 500 — not propagate the unknown code.
    BackendServiceException ex =
        new BackendServiceException("weird", null, 599, "UNKNOWN", null, List.of(), null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void conflictCode_setsReloadHintTrue_inJsonBody() {
    // The reloadHint flag is set for both OPTIMISTIC_LOCK and CONFLICT
    // codes — frontend uses it to auto-reload the page after dismissing
    // the toast. A regression here would leave the user on stale data.
    BackendServiceException ex =
        new BackendServiceException("x", null, 409, "CONFLICT", null, List.of(), null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    ResponseEntity<?> response = (ResponseEntity<?>) result;
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals(Boolean.TRUE, body.get("reloadHint"));
  }

  @Test
  void nonConflictNonOptimisticLock_setsReloadHintFalse() {
    BackendServiceException ex =
        new BackendServiceException("x", null, 400, "VALIDATION_FAILED", null, List.of(), null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    ResponseEntity<?> response = (ResponseEntity<?>) result;
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals(Boolean.FALSE, body.get("reloadHint"));
  }

  // ─── Kartell bank stable-409 codes (epic #556, K1 mockup) ───────────────

  @Test
  void bankOverdraft409_mapsToBankKey_andKeepsReloadHintFalse() {
    // A Kartell bank overdraft is a business 409 that bank.js renders inline at the booking-modal
    // fields — never a page reload (K1: a bank 409 is never a full-page reload). It must resolve to
    // its dedicated error.bank.overdraft key (not fall through to error.unexpected) AND, unlike
    // OPTIMISTIC_LOCK/CONFLICT, keep reloadHint=false so the half-filled booking form survives.
    BackendServiceException ex =
        new BackendServiceException(
            "overdraft", null, 409, "BANK_OVERDRAFT", "corr-bank", List.of(), null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("BANK_OVERDRAFT", body.get("code"));
    assertEquals(409, body.get("status"));
    assertEquals("error.bank.overdraft", body.get("message"));
    assertEquals(Boolean.FALSE, body.get("reloadHint"));
  }

  @Test
  void bankOwnerApprovalRequired409_mapsToDedicatedKey_notUnexpected() {
    // Regression for the "Ein unerwarteter Fehler" incident: booking-request/justification/fee 409
    // codes were absent from the code→message map and fell through to error.unexpected. They must
    // now resolve to their dedicated error.bank.* key (here BANK_OWNER_APPROVAL_REQUIRED) so the
    // user sees the real reason inline instead of the generic text.
    BackendServiceException ex =
        new BackendServiceException(
            "needs approval",
            null,
            409,
            "BANK_OWNER_APPROVAL_REQUIRED",
            "corr-bank",
            List.of(),
            null);

    Object result = handler.handleBackendServiceException(ex, jsonRequest(), new ConcurrentModel());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("BANK_OWNER_APPROVAL_REQUIRED", body.get("code"));
    assertEquals("error.bank.ownerApprovalRequired", body.get("message"));
    assertEquals(Boolean.FALSE, body.get("reloadHint"));
  }

  // ─── handleNoResourceFoundException ─────────────────────────────────────

  @Test
  void noResourceFound_setsModelAttributes_andReturnsErrorView() {
    Model model = new ConcurrentModel();

    String view = handler.handleNoResourceFoundException(model);

    assertEquals("error/error", view);
    assertEquals("error.404.title", model.getAttribute("error"));
    assertEquals("error.404.message", model.getAttribute("message"));
    assertEquals("404", model.getAttribute("status"));
  }

  // ─── handleTypeMismatch ─────────────────────────────────────────────────

  @Test
  void typeMismatch_jsonRequest_returns400JsonBody() {
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
        Mockito.mock(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class);
    when(ex.getName()).thenReturn("id");
    Mockito.<Class<?>>when(ex.getRequiredType()).thenReturn((Class) java.util.UUID.class);

    Object result = handler.handleTypeMismatch(ex, new ConcurrentModel(), jsonRequest());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("VALIDATION_FAILED", body.get("code"));
    assertEquals(400, body.get("status"));
    assertEquals("error.400.title", body.get("title"));
  }

  @Test
  void typeMismatch_htmlRequest_returnsErrorView() {
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
        Mockito.mock(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class);
    when(ex.getName()).thenReturn("id");
    Model model = new ConcurrentModel();

    Object result = handler.handleTypeMismatch(ex, model, htmlRequest());

    assertEquals("error/error", result);
    assertEquals("error.400.title", model.getAttribute("error"));
    assertEquals("400", model.getAttribute("status"));
  }

  @Test
  void typeMismatch_withNullRequiredType_doesNotNPE() {
    // Defensive: getRequiredType() returning null is rare but valid.
    // Without the null-check the handler would NPE when logging.
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
        Mockito.mock(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class);
    when(ex.getName()).thenReturn("x");
    Mockito.<Class<?>>when(ex.getRequiredType()).thenReturn(null);

    assertEquals(
        "error/error", handler.handleTypeMismatch(ex, new ConcurrentModel(), htmlRequest()));
  }

  @Test
  void typeMismatch_assetShapedPath_htmlRequest_returns404ModelAndView() {
    // A crawler resolving head.html's script filenames relative to /missions/... produces a UUID
    // mismatch on a path that names no resource — the handler must answer 404, not 400
    // (REQ-OBS-001 asset-shaped carve-out).
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
        Mockito.mock(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class);
    when(ex.getName()).thenReturn("id");
    Mockito.<Class<?>>when(ex.getRequiredType()).thenReturn((Class) java.util.UUID.class);
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("text/html");
    when(req.getRequestURI()).thenReturn("/missions/common-handlers.js");

    Object result = handler.handleTypeMismatch(ex, new ConcurrentModel(), req);

    assertInstanceOf(ModelAndView.class, result);
    ModelAndView mav = (ModelAndView) result;
    assertEquals("error/error", mav.getViewName());
    assertEquals(HttpStatus.NOT_FOUND, mav.getStatus());
    assertEquals("error.404.title", mav.getModel().get("error"));
    assertEquals("error.404.message", mav.getModel().get("message"));
    assertEquals("404", mav.getModel().get("status"));
  }

  @Test
  void typeMismatch_assetShapedPath_jsonRequest_returns404Json() {
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
        Mockito.mock(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class);
    when(ex.getName()).thenReturn("id");
    Mockito.<Class<?>>when(ex.getRequiredType()).thenReturn((Class) java.util.UUID.class);
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
    when(req.getRequestURI()).thenReturn("/orders/scu-decimal-input.js");

    Object result = handler.handleTypeMismatch(ex, new ConcurrentModel(), req);

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("NOT_FOUND", body.get("code"));
    assertEquals(404, body.get("status"));
    assertEquals("error.404.title", body.get("title"));
  }

  @Test
  void typeMismatch_assetShapedPath_nonUuidTarget_keeps400() {
    // The carve-out is double-keyed: a dotted URI alone must NOT flip the response — only a UUID
    // target type qualifies, so a legitimately dotted route with a non-UUID parameter keeps its
    // honest 400 + WARN.
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
        Mockito.mock(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class);
    when(ex.getName()).thenReturn("count");
    Mockito.<Class<?>>when(ex.getRequiredType()).thenReturn((Class) Integer.class);
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("text/html");
    when(req.getRequestURI()).thenReturn("/api/proxy/audit/bank/export.json");
    Model model = new ConcurrentModel();

    Object result = handler.handleTypeMismatch(ex, model, req);

    assertEquals("error/error", result);
    assertEquals("error.400.title", model.getAttribute("error"));
    assertEquals("400", model.getAttribute("status"));
  }

  @Test
  void typeMismatch_uuidTarget_undottedPath_keeps400() {
    // A truncated pasted link (e.g. /missions/8bd4a2de) has no filename extension — it stays a
    // genuine 400 so human navigation errors keep their WARN signal.
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
        Mockito.mock(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class);
    when(ex.getName()).thenReturn("id");
    Mockito.<Class<?>>when(ex.getRequiredType()).thenReturn((Class) java.util.UUID.class);
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("text/html");
    when(req.getRequestURI()).thenReturn("/missions/8bd4a2de");
    Model model = new ConcurrentModel();

    Object result = handler.handleTypeMismatch(ex, model, req);

    assertEquals("error/error", result);
    assertEquals("error.400.title", model.getAttribute("error"));
    assertEquals("400", model.getAttribute("status"));
  }

  // ─── handleAccessDenied ─────────────────────────────────────────────────

  @Test
  void accessDenied_springSecurity_jsonRequest_returns403Json() {
    org.springframework.security.access.AccessDeniedException ex =
        new org.springframework.security.access.AccessDeniedException("forbidden");

    Object result = handler.handleAccessDenied(ex, jsonRequest(), new ConcurrentModel());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("ACCESS_DENIED", body.get("code"));
    assertEquals(403, body.get("status"));
    assertEquals("error.403.title", body.get("title"));
  }

  @Test
  void accessDenied_authorizationDenied_htmlRequest_returnsErrorView() {
    // AuthorizationDeniedException is the Spring Security 6+ replacement —
    // the handler must catch both because @PreAuthorize raises the new one
    // while older code paths still raise the legacy AccessDeniedException.
    org.springframework.security.authorization.AuthorizationDeniedException ex =
        new org.springframework.security.authorization.AuthorizationDeniedException("denied");
    Model model = new ConcurrentModel();

    Object result = handler.handleAccessDenied(ex, htmlRequest(), model);

    assertEquals("error/error", result);
    assertEquals("error.403.title", model.getAttribute("error"));
    assertEquals("error.forbidden", model.getAttribute("message"));
    assertEquals("403", model.getAttribute("status"));
    // Default @BeforeEach context is authenticated → the sign-in CTA must stay hidden.
    assertEquals(Boolean.FALSE, model.getAttribute("unauthenticated"));
  }

  @Test
  void accessDenied_anonymousUser_htmlRequest_usesUnauthenticatedMessageAndFlag() {
    // Issue #108: an anonymous caller hitting a @PreAuthorize gate behind a permitAll() route
    // must see "please sign in and try again" rather than the generic "you lack permission",
    // and the template needs the `unauthenticated` flag to render the Sign-in CTA.
    setAnonymousUser();
    org.springframework.security.authorization.AuthorizationDeniedException ex =
        new org.springframework.security.authorization.AuthorizationDeniedException("denied");
    Model model = new ConcurrentModel();

    Object result = handler.handleAccessDenied(ex, htmlRequest(), model);

    assertEquals("error/error", result);
    assertEquals("error.403.title", model.getAttribute("error"));
    assertEquals("error.forbidden.unauthenticated", model.getAttribute("message"));
    assertEquals("403", model.getAttribute("status"));
    assertEquals(Boolean.TRUE, model.getAttribute("unauthenticated"));
  }

  @Test
  void accessDenied_noAuthentication_htmlRequest_usesUnauthenticatedMessageAndFlag() {
    // Belt-and-suspenders for the AnonymousAuthenticationToken case: when no Authentication
    // is present at all (e.g. AnonymousAuthenticationFilter disabled, programmatic clearContext),
    // the handler must still classify the caller as unauthenticated.
    SecurityContextHolder.clearContext();
    org.springframework.security.access.AccessDeniedException ex =
        new org.springframework.security.access.AccessDeniedException("denied");
    Model model = new ConcurrentModel();

    Object result = handler.handleAccessDenied(ex, htmlRequest(), model);

    assertEquals("error/error", result);
    assertEquals("error.forbidden.unauthenticated", model.getAttribute("message"));
    assertEquals(Boolean.TRUE, model.getAttribute("unauthenticated"));
  }

  @Test
  void accessDenied_anonymousUser_jsonRequest_setsUnauthenticatedFlagInBody() {
    // The JSON branch is consumed by the toast renderer — it relies on `body.unauthenticated`
    // to surface a sign-in hint instead of the default "no permission" copy.
    setAnonymousUser();
    org.springframework.security.access.AccessDeniedException ex =
        new org.springframework.security.access.AccessDeniedException("denied");

    Object result = handler.handleAccessDenied(ex, jsonRequest(), new ConcurrentModel());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("error.forbidden.unauthenticated", body.get("message"));
    assertEquals(Boolean.TRUE, body.get("unauthenticated"));
  }

  @Test
  void backendAccessDenied_anonymousUser_usesUnauthenticatedMessage() {
    // Mirror of the @PreAuthorize-derived branch for backend-originated 403s. In practice the
    // backend would return 401 UNAUTHENTICATED for a missing JWT, but this guards the edge case
    // where the backend forwards ACCESS_DENIED to an anonymous frontend caller — the user-facing
    // wording must still be "please sign in" rather than "you lack permission".
    setAnonymousUser();
    BackendServiceException ex =
        new BackendServiceException(
            "forbidden", null, 403, "ACCESS_DENIED", "corr-403", List.of(), null);
    Model model = new ConcurrentModel();

    Object result = handler.handleBackendServiceException(ex, htmlRequest(), model);

    assertEquals("error/error", result);
    assertEquals("error.forbidden.unauthenticated", model.getAttribute("message"));
    assertEquals(Boolean.TRUE, model.getAttribute("unauthenticated"));
  }

  @Test
  void backendUnauthenticatedCode_alwaysSetsUnauthenticatedFlag() {
    // UNAUTHENTICATED keeps its dedicated "your session has expired" wording, but the flag must
    // still be set so the error page renders the Sign-in CTA next to the message.
    BackendServiceException ex =
        new BackendServiceException(
            "session expired", null, 401, "UNAUTHENTICATED", null, List.of(), null);
    Model model = new ConcurrentModel();

    handler.handleBackendServiceException(ex, htmlRequest(), model);

    assertEquals("error.unauthenticated", model.getAttribute("message"));
    assertEquals(Boolean.TRUE, model.getAttribute("unauthenticated"));
  }

  @Test
  void backendAccessDenied_authenticatedUser_keepsForbiddenMessage() {
    // Authenticated user without the required role must still see the generic forbidden message;
    // adding the anonymous branch must not regress this path.
    BackendServiceException ex =
        new BackendServiceException("forbidden", null, 403, "ACCESS_DENIED", null, List.of(), null);
    Model model = new ConcurrentModel();

    handler.handleBackendServiceException(ex, htmlRequest(), model);

    assertEquals("error.forbidden", model.getAttribute("message"));
    assertEquals(Boolean.FALSE, model.getAttribute("unauthenticated"));
  }

  // ─── handleMaxUploadSizeExceeded (REQ-FE-009) ───────────────────────────

  @Test
  void maxUploadSizeExceeded_jsonRequest_returns413WithUploadTooLargeCode() {
    // The frontend's FormData AJAX writes serialise every form field as a multipart part; a large
    // editor can breach Tomcat's part-count cap. The XHR caller must get a clean 413 JSON body the
    // toast renderer can show — not the generic 500 the catch-all would otherwise produce.
    Object result = handler.handleMaxUploadSizeExceeded(jsonRequest(), new ConcurrentModel());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.CONTENT_TOO_LARGE, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("UPLOAD_TOO_LARGE", body.get("code"));
    assertEquals(413, body.get("status"));
    assertEquals("error.413.title", body.get("title"));
    assertEquals("error.uploadTooLarge", body.get("message"));
  }

  @Test
  void maxUploadSizeExceeded_htmlRequest_returnsErrorViewWith413() {
    Model model = new ConcurrentModel();

    Object result = handler.handleMaxUploadSizeExceeded(htmlRequest(), model);

    assertEquals("error/error", result);
    assertEquals("error.413.title", model.getAttribute("error"));
    assertEquals("error.uploadTooLarge", model.getAttribute("message"));
    assertEquals("413", model.getAttribute("status"));
  }

  // ─── handleInvalidParameter (unparseable request parameters) ────────────

  @Test
  void invalidParameter_htmlRequest_returnsErrorViewWith400() {
    Model model = new ConcurrentModel();

    Object result = handler.handleInvalidParameter(htmlRequest(), model);

    // Not the 500 the Exception catch-all would have produced: unparseable client input is a bad
    // request, and the ERROR + stack trace it used to emit is pure scanner noise (REQ-OBS-001).
    assertEquals("error/error", result);
    assertEquals("error.400.title", model.getAttribute("error"));
    assertEquals("error.malformedRequest", model.getAttribute("message"));
    assertEquals("400", model.getAttribute("status"));
  }

  @Test
  void invalidParameter_jsonRequest_returns400ProblemBody() {
    Model model = new ConcurrentModel();

    Object result = handler.handleInvalidParameter(jsonRequest(), model);

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("MALFORMED_REQUEST", body.get("code"));
    assertEquals(400, body.get("status"));
    assertEquals("error.400.title", body.get("title"));
    assertEquals("error.malformedRequest", body.get("message"));
  }

  @Test
  void invalidParameter_isMappedAheadOfTheGenericCatchAll() throws Exception {
    // The two handlers overlap: InvalidParameterException extends IllegalStateException, so
    // without a dedicated @ExceptionHandler it lands on the Exception catch-all — which is exactly
    // how the production 500s were produced. Assert the specific mapping exists and is annotated
    // with 400, since Spring picks the most specific declared exception type.
    var method =
        GlobalExceptionHandler.class.getMethod(
            "handleInvalidParameter", HttpServletRequest.class, Model.class);
    var handlerAnnotation = method.getAnnotation(ExceptionHandler.class);
    assertNotNull(handlerAnnotation);
    assertEquals(
        InvalidParameterException.class,
        handlerAnnotation.value()[0],
        "the container's parameter-parse failure must map here, not to the catch-all");
    assertEquals(HttpStatus.BAD_REQUEST, method.getAnnotation(ResponseStatus.class).value());
  }

  // ─── handleException (generic catch-all) ────────────────────────────────

  @Test
  void genericException_returnsErrorView_with500Default() {
    RuntimeException unexpected = new RuntimeException("boom");
    Model model = new ConcurrentModel();

    String view = handler.handleException(unexpected, model, htmlRequest());

    assertEquals("error/error", view);
    assertEquals("error.generic.title", model.getAttribute("error"));
    assertEquals("error.unexpected", model.getAttribute("message"));
    assertEquals("500", model.getAttribute("status"));
  }

  @Test
  void genericException_unwrapsWebClientResponseException_andUsesItsStatus() {
    // Given a WebClient call that escaped the BackendApiClient wrapping
    // (rare but possible — e.g. an unrelated WebClient instance) and
    // surfaced via a wrapper RuntimeException. The handler must walk the
    // cause chain and adopt the upstream HTTP status for the error view.
    org.springframework.web.reactive.function.client.WebClientResponseException inner =
        org.springframework.web.reactive.function.client.WebClientResponseException.create(
            503, "Service Unavailable", null, null, null);
    RuntimeException wrapped = new RuntimeException("outer", inner);

    Model model = new ConcurrentModel();
    String view = handler.handleException(wrapped, model, htmlRequest());

    assertEquals("error/error", view);
    assertEquals(
        "503",
        model.getAttribute("status"),
        "When the cause is a WebClientResponseException, surface its status");
    assertEquals("error.503.title", model.getAttribute("error"));
  }

  // ─── handleReauthenticationRequired (REQ-SEC-012) ───────────────────────

  private static HttpServletRequest navigationRequest() {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("text/html");
    when(req.getContextPath()).thenReturn("");
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/orders");
    return req;
  }

  private static HttpServletRequest ajaxRequest() {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
    when(req.getContextPath()).thenReturn("");
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/notifications/unread-count");
    return req;
  }

  @Test
  void reauthRequired_htmlNavigation_redirectsToKeycloakLoginFlow() {
    Object result = handler.handleReauthenticationRequired(navigationRequest());

    assertEquals("redirect:/oauth2/authorization/keycloak", result);
  }

  @Test
  void reauthRequired_ajax_returns401WithReauthenticateHeaderAndBody() {
    Object result = handler.handleReauthenticationRequired(ajaxRequest());

    assertInstanceOf(ResponseEntity.class, result);
    ResponseEntity<?> response = (ResponseEntity<?>) result;
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertEquals(
        "/oauth2/authorization/keycloak", response.getHeaders().getFirst("X-Reauthenticate"));
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertNotNull(body);
    assertEquals("REAUTH_REQUIRED", body.get("code"));
    assertEquals(401, body.get("status"));
    assertEquals(Boolean.TRUE, body.get("reauthenticate"));
    assertEquals("/oauth2/authorization/keycloak", body.get("location"));
  }

  @Test
  void reauthRequired_ajax_carriesCorrelationIdFromMdc() {
    MDC.put("correlationId", "corr-reauth-42");
    Object result = handler.handleReauthenticationRequired(ajaxRequest());

    assertInstanceOf(ResponseEntity.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) ((ResponseEntity<?>) result).getBody();
    assertNotNull(body);
    assertEquals(
        "corr-reauth-42",
        body.get("correlationId"),
        "the reauth JSON body must echo the request-scoped correlation id for cross-tier tracing");
  }

  @Test
  void reauthRequired_honoursContextPathPrefix() {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("text/html");
    when(req.getContextPath()).thenReturn("/app");
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/app/inventory");

    Object result = handler.handleReauthenticationRequired(req);

    assertEquals("redirect:/app/oauth2/authorization/keycloak", result);
  }
}
