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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationBulkResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationCountResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPageSliceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationViewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * Frontend page + AJAX relay for the per-user notification inbox. The browser never talks to the
 * backend directly: this controller proxies to the backend REST API (which derives the recipient
 * from the session's JWT) and localizes each notification's text server-side via {@link
 * MessageSource} so the page and the bell dropdown render identical strings.
 */
@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class NotificationPageController {

  private static final String BACKEND_BASE = "/api/v1/notifications";
  private static final int PAGE_LIMIT = 50;
  private static final int DROPDOWN_LIMIT = 10;
  private static final DateTimeFormatter DISPLAY_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
  private static final ParameterizedTypeReference<List<NotificationDto>> LIST_TYPE =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<PageResponse<NotificationDto>> PAGE_TYPE =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
      new ParameterizedTypeReference<>() {};
  private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
  private static final String REGISTRATION_ID = "keycloak";

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;
  private final WebClient sseWebClient;
  private final OAuth2AuthorizedClientRepository authorizedClientRepository;
  private final MeterRegistry meterRegistry;

  /** Live browser-to-backend SSE relays open on this instance (relay-connections gauge source). */
  private final AtomicInteger relayConnections = new AtomicInteger();

  /**
   * Binds the {@code basetool_notification_relay_connections} gauge to the live relay count once
   * the bean is constructed (#1041 item 17). Zero here while users are online means the
   * browser-to-backend notification push is dead and clients fell back to the unread-count poll.
   */
  @PostConstruct
  void registerRelayGauge() {
    Gauge.builder(
            MetricNames.NOTIFICATION_RELAY_CONNECTIONS,
            relayConnections,
            AtomicInteger::doubleValue)
        .description("Open browser-to-backend notification SSE relays on this instance.")
        .register(meterRegistry);
  }

  /**
   * Creates the {@link SseEmitter} backing a new browser relay, with the registry's 30-minute
   * timeout. Extracted as a seam so a test can substitute a mock emitter and assert the relay's
   * initial request-thread commit (mirrors {@code NotificationStreamService.newEmitter()} on the
   * backend).
   *
   * @return a fresh emitter holding the browser connection open for {@link #STREAM_TIMEOUT_MS}
   */
  protected SseEmitter newEmitter() {
    return new SseEmitter(STREAM_TIMEOUT_MS);
  }

  /**
   * Renders the full notifications page: the newest {@value #PAGE_LIMIT} notifications plus the
   * paging facts (total count, more-pages flag) that drive the "showing X of Y" hint and the
   * load-more control, so a cap-exceeding inbox is visibly — never silently — truncated
   * (REQ-NOTIF-019). Fail-soft to an empty list on a backend hiccup.
   *
   * @param model the view model
   * @return the notifications template name
   */
  @GetMapping
  public String page(Model model) {
    try {
      PageResponse<NotificationDto> firstPage = loadPage(0);
      List<NotificationViewDto> views = toViews(firstPage);
      model.addAttribute("notifications", views);
      model.addAttribute("notifTotal", firstPage == null ? 0L : firstPage.totalElements());
      model.addAttribute("notifHasMore", hasMore(firstPage));
    } catch (ReauthenticationRequiredException e) {
      // Let GlobalExceptionHandler bounce the user through a fresh Keycloak login (302) rather than
      // rendering an empty inbox on a dead session.
      throw e;
    } catch (Exception e) {
      log.debug("Failed to load notifications page", e);
      model.addAttribute("notifications", List.of());
      model.addAttribute("notifTotal", 0L);
      model.addAttribute("notifHasMore", false);
      model.addAttribute("error", "notifications.error.load");
    }
    return "notifications";
  }

  /**
   * Returns one further inbox page for the load-more control on the {@code /notifications} page
   * (REQ-NOTIF-019). Items are localized server-side exactly like the initial render, so appended
   * entries are indistinguishable from server-rendered ones.
   *
   * @param page the zero-based page index to fetch (page 0 is the initial server render)
   * @return the localized page slice with the total count and the more-pages flag
   */
  @ResponseBody
  @GetMapping(value = "/page-items", headers = "X-Requested-With=XMLHttpRequest")
  public NotificationPageSliceDto pageItems(@RequestParam(defaultValue = "1") int page) {
    PageResponse<NotificationDto> result = loadPage(Math.max(0, page));
    return new NotificationPageSliceDto(
        toViews(result), result == null ? 0L : result.totalElements(), hasMore(result));
  }

  /**
   * Returns the most recent notifications for the bell dropdown as JSON.
   *
   * @return the localized notification view DTOs
   */
  @ResponseBody
  @GetMapping(value = "/recent", headers = "X-Requested-With=XMLHttpRequest")
  public List<NotificationViewDto> recent() {
    return loadView(DROPDOWN_LIMIT);
  }

  /**
   * Relays the backend notification SSE stream to the browser (REQ-NOTIF-010). The browser opens an
   * {@code EventSource} here; this controller forwards each backend event over the resilience-free
   * {@code sseWebClient}. Best-effort: if the backend stream errors, the emitter completes with the
   * error and the browser's polling fallback keeps the badge fresh.
   *
   * <p>The OAuth2 bearer is resolved <b>read-only</b> on the servlet thread and set as a plain
   * {@code Authorization} header on the upstream call. The {@code sseWebClient} carries no OAuth2
   * exchange filter (see the {@code sseWebClient} bean in {@code WebClientConfig}), so this
   * long-lived relay is structurally incapable of asking the {@code OAuth2AuthorizedClientManager}
   * to refresh — it can neither rotate the session's online refresh token nor write a stale one
   * back, which Keycloak's reuse detection would otherwise punish by revoking the whole SSO session
   * and forcing an interactive re-login (REQ-SEC-012). The snapshot token is relayed verbatim even
   * when already expired: the backend rejects it, the stream fails soft, and the always-on
   * unread-count poll — not this relay — keeps the token fresh and drives any re-authentication.
   * When no usable token is bound the stream fails soft immediately.
   *
   * @param request the current servlet request, used to read the session-stored authorized client
   * @param authentication the authenticated principal owning the session
   * @return the SSE emitter writing to the browser
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(HttpServletRequest request, Authentication authentication) {
    SseEmitter emitter = newEmitter();
    OAuth2AuthorizedClient authorizedClient =
        authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request);
    if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
      // No usable token snapshot (e.g. a freshly-lost session): fail soft. The 60s unread-count
      // poll runs the same backend call through BackendApiClient and drives re-authentication.
      emitter.complete();
      return emitter;
    }
    String bearerToken = authorizedClient.getAccessToken().getTokenValue();
    // Commit the SSE response NOW, on the request thread, with an initial keep-alive comment.
    // Load-bearing (ADR-0113) — do NOT remove as "redundant" next to the forwarded backend
    // `connected`: this relay's first real write is forward() below, invoked on a reactor-netty
    // event-loop thread, and Spring Web 7 + Tomcat 11 do NOT commit an async SSE response whose
    // first write lands on a non-container thread (spring-ai #6169) — without this the status line
    // + headers never reach the browser/NPM and every stream 60s-header-times-out (the
    // 100%-dead-SSE
    // incident of 2026-07-20). Spring replays this pre-initialize send on the request (dispatch)
    // thread when it initializes the emitter, committing the response there, on a container thread
    // —
    // the same request-thread-first-write pattern the backend's
    // NotificationStreamService.subscribe()
    // already uses. A comment (not a named event) is invisible to EventSource, so it only flushes
    // the
    // headers; the forwarded backend events (incl. the backend's own `connected`) follow normally.
    try {
      emitter.send(SseEmitter.event().comment("ready"));
    } catch (IOException | RuntimeException e) {
      // Browser already gone before we could commit: fail soft, do not wire the relay.
      log.debug(
          "Notification stream initial commit failed ({}); completing",
          e.getClass().getSimpleName());
      emitter.complete();
      return emitter;
    }
    // Count this relay for the whole lifetime of the upstream subscription. doFinally fires exactly
    // once on any terminal signal — upstream complete/error, or a cancel when the browser
    // disconnects and onCompletion/onTimeout dispose the subscription below — so it stays balanced.
    relayConnections.incrementAndGet();
    Disposable subscription =
        sseWebClient
            .get()
            .uri(BACKEND_BASE + "/stream")
            .headers(headers -> headers.setBearerAuth(bearerToken))
            .retrieve()
            .bodyToFlux(SSE_TYPE)
            .doFinally(signal -> relayConnections.decrementAndGet())
            .subscribe(
                event -> forward(emitter, event),
                error -> handleStreamError(emitter, error),
                emitter::complete);
    emitter.onCompletion(subscription::dispose);
    emitter.onTimeout(
        () -> {
          subscription.dispose();
          emitter.complete();
        });
    emitter.onError(error -> subscription.dispose());
    return emitter;
  }

  /**
   * Returns the caller's unread count for the always-on badge poll.
   *
   * @return the unread count payload (fail-soft to zero on a backend hiccup)
   */
  @ResponseBody
  @GetMapping(value = "/unread-count", headers = "X-Requested-With=XMLHttpRequest")
  public NotificationCountResponse unreadCount() {
    return new NotificationCountResponse(currentUnreadCount());
  }

  /**
   * Marks one notification read (AJAX relay).
   *
   * @param id notification id
   * @return 200 on success, or the relayed backend error
   */
  @ResponseBody
  @PostMapping(value = "/{id}/read", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> markRead(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.post(BACKEND_BASE + "/" + id + "/read", null, NotificationDto.class);
      return ResponseEntity.ok(new NotificationCountResponse(currentUnreadCount()));
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (Exception e) {
      log.error("Mark-read {} (ajax) failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Marks all of the caller's notifications read (AJAX relay).
   *
   * @return the bulk result, or the relayed backend error
   */
  @ResponseBody
  @PostMapping(value = "/read-all", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> markAllRead() {
    try {
      NotificationBulkResultDto result =
          backendApiClient.post(BACKEND_BASE + "/read-all", null, NotificationBulkResultDto.class);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (Exception e) {
      log.error("Mark-all-read (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Deletes one notification, read or unread (AJAX relay).
   *
   * @param id notification id
   * @return the resulting unread count, or the relayed backend error
   */
  @ResponseBody
  @DeleteMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> delete(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete(BACKEND_BASE + "/" + id, Void.class);
      return ResponseEntity.ok(new NotificationCountResponse(currentUnreadCount()));
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (Exception e) {
      log.error("Delete notification {} (ajax) failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Deletes all of the caller's already-read notifications (AJAX relay).
   *
   * @return the bulk result, or the relayed backend error
   */
  @ResponseBody
  @DeleteMapping(value = "/read", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> clearRead() {
    try {
      NotificationBulkResultDto result =
          backendApiClient.delete(BACKEND_BASE + "/read", NotificationBulkResultDto.class);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (Exception e) {
      log.error("Clear-read (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  private List<NotificationViewDto> loadView(int limit) {
    List<NotificationDto> dtos =
        backendApiClient.get(
            BACKEND_BASE + "/recent?limit={limit}", LIST_TYPE, Integer.valueOf(limit));
    if (dtos == null) {
      return List.of();
    }
    Locale locale = LocaleContextHolder.getLocale();
    return dtos.stream().map(dto -> toView(dto, locale)).toList();
  }

  /**
   * Fetches one page of the caller's inbox from the paginated backend listing, newest first. Used
   * by the initial page render (page 0) and the load-more relay, unlike the bell dropdown which
   * keeps the lighter {@code /recent} endpoint.
   *
   * @param page the zero-based page index
   * @return the backend page response, or {@code null} when the backend returned none
   */
  private PageResponse<NotificationDto> loadPage(int page) {
    return backendApiClient.get(
        BACKEND_BASE + "?page={page}&size={size}&sort=createdAt,desc",
        PAGE_TYPE,
        Integer.valueOf(page),
        Integer.valueOf(PAGE_LIMIT));
  }

  /**
   * Localizes a backend page's content into view DTOs; empty on a {@code null} page/content.
   *
   * @param result the backend page response, may be {@code null}
   * @return the localized views of the page content
   */
  private List<NotificationViewDto> toViews(PageResponse<NotificationDto> result) {
    if (result == null || result.content() == null) {
      return List.of();
    }
    Locale locale = LocaleContextHolder.getLocale();
    return result.content().stream().map(dto -> toView(dto, locale)).toList();
  }

  /**
   * Whether at least one further page exists after the given one.
   *
   * @param result the backend page response, may be {@code null}
   * @return {@code true} when more pages follow
   */
  private static boolean hasMore(PageResponse<NotificationDto> result) {
    return result != null && result.page() + 1 < result.totalPages();
  }

  private NotificationViewDto toView(NotificationDto dto, Locale locale) {
    return new NotificationViewDto(
        dto.id(),
        render(dto.type(), dto.params(), locale),
        dto.read(),
        dto.createdAt() == null ? "" : DISPLAY_FORMAT.format(dto.createdAt()),
        dto.entityType(),
        dto.entityId());
  }

  private String render(String type, Map<String, String> params, Locale locale) {
    String key = "notifications.type." + type;
    String template = messageSource.getMessage(key, null, key, locale);
    if (template == null || template.equals(key)) {
      template =
          messageSource.getMessage("notifications.type.generic", null, "Notification", locale);
    }
    if (params != null) {
      for (Map.Entry<String, String> entry : params.entrySet()) {
        template =
            template.replace(
                "{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
      }
    }
    return template;
  }

  private long currentUnreadCount() {
    try {
      NotificationCountResponse response =
          backendApiClient.get(BACKEND_BASE + "/unread-count", NotificationCountResponse.class);
      return response != null && response.count() != null ? response.count() : 0L;
    } catch (ReauthenticationRequiredException e) {
      // Surface a 401 + X-Reauthenticate (via GlobalExceptionHandler) so the always-on badge poll
      // re-logs the user in instead of silently reporting zero on a dead session.
      throw e;
    } catch (Exception e) {
      log.debug("Failed to load unread count", e);
      return 0L;
    }
  }

  /**
   * Terminates the relayed SSE stream on a backend error. When the error is a re-authentication
   * signal (the session's OAuth2 token is gone, see {@link
   * ReauthenticationRequiredException#isReauthSignal}), a named {@code reauth} event carrying the
   * Keycloak login path is pushed so the browser can redirect the whole window instead of entering
   * an {@code EventSource} reconnect loop against a dead session; the stream then completes
   * cleanly. Any other error also completes the emitter <b>cleanly</b> (not {@code
   * completeWithError}): SSE push is best-effort (REQ-NOTIF-010) and the unread-count poll is the
   * guaranteed fallback, so a dropped/unavailable backend stream is not an application fault.
   * {@code completeWithError} would re-dispatch the error through the MVC {@code @ExceptionHandler}
   * and log a spurious ERROR per dropped stream — the dominant source of frontend ERROR-log noise
   * while the backend/Keycloak has a blip; a clean completion just lets the browser reconnect.
   *
   * @param emitter the browser-facing emitter to terminate
   * @param error the error raised by the backend stream subscription
   */
  private static void handleStreamError(SseEmitter emitter, Throwable error) {
    if (ReauthenticationRequiredException.isReauthSignal(error)) {
      log.debug("Notification stream needs re-authentication; signalling the browser to re-login");
      try {
        emitter.send(
            SseEmitter.event().name("reauth").data(ReauthenticationRequiredException.REAUTH_PATH));
        emitter.complete();
      } catch (IOException | RuntimeException sendFailure) {
        emitter.complete();
      }
      return;
    }
    // Best-effort SSE: complete cleanly (browser reconnects, poll keeps the badge fresh) instead of
    // completeWithError(), which re-dispatches through the MVC exception handler and logs an ERROR
    // for every transient backend-stream drop (REQ-NOTIF-010). DEBUG only.
    log.debug(
        "Notification stream dropped ({}); completing cleanly, poll fallback keeps the badge fresh",
        error.getClass().getSimpleName());
    emitter.complete();
  }

  private static void forward(SseEmitter emitter, ServerSentEvent<String> event) {
    try {
      SseEmitter.SseEventBuilder builder = SseEmitter.event();
      if (event.event() != null) {
        builder.name(event.event());
      }
      if (event.comment() != null) {
        builder.comment(event.comment());
      }
      if (event.data() != null) {
        builder.data(event.data());
      }
      emitter.send(builder);
    } catch (IOException | RuntimeException e) {
      // REQ-OBS-001 / REQ-NOTIF-010: a send failure here is almost always a routine client
      // disconnect (broken pipe when the viewer closes the tab mid-event). completeWithError()
      // re-dispatches through the MVC @ExceptionHandler and logs a spurious ERROR per dropped
      // stream — the dominant source of frontend ERROR-log noise during a backend/Keycloak blip.
      // Complete cleanly instead (onCompletion disposes the upstream subscription), mirroring
      // handleStreamError(); the poll fallback keeps the badge fresh.
      log.debug(
          "Notification stream send failed ({}); completing cleanly", e.getClass().getSimpleName());
      emitter.complete();
    }
  }
}
