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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendSideChannels;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import de.greluc.krt.profit.basetool.frontend.kernel.security.TermsAcceptanceGateFilter;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationBulkResultDto;
import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationCountResponse;
import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationDto;
import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationPageSliceDto;
import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationViewDto;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;

/**
 * Frontend page + AJAX relay for the per-user notification inbox. The browser never talks to the
 * backend directly: this controller proxies to the backend REST API (which derives the recipient
 * from the session's JWT) and localizes each notification's text server-side via {@link
 * MessageSource} so the page and the bell dropdown render identical strings.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class NotificationPageController {

  private static final int PAGE_LIMIT = 50;
  private static final int DROPDOWN_LIMIT = 10;

  /** The detail page of the record a notification is about, by the notification's entity type. */
  private static final Map<String, String> ENTITY_PAGES =
      Map.of(
          "MISSION",
          "/missions/",
          "OPERATION",
          "/operations/",
          "JOB_ORDER",
          "/orders/",
          "REFINERY_ORDER",
          "/refinery-orders/",
          "BANK_ACCOUNT_GRANT",
          "/bank/accounts/",
          "BANK_HOLDER",
          "/bank/holders/",
          "PURCHASING_DEAL",
          "/purchasing/deals/");

  /** The types whose record is gone, so the row has nothing to link to. */
  private static final Set<String> UNLINKED_TYPES = Set.of("MISSION_DELETED", "BANK_GRANT_REVOKED");

  /** The page each notification type links to when its subject is a page rather than a record. */
  private static final Map<String, String> PAGE_TARGETS =
      Map.ofEntries(
          Map.entry("MATERIAL_EXCHANGE_INTEREST_REGISTERED", "/materialboerse"),
          Map.entry("MATERIAL_REQUEST_FULFILLMENT_SIGNALLED", "/materialboerse"),
          Map.entry("MATERIAL_EXCHANGE_OFFER_UNAVAILABLE", "/materialboerse"),
          Map.entry("MATERIAL_REQUEST_UNAVAILABLE", "/materialboerse"),
          Map.entry("EXCHANGE_INSTALLATION_CONNECTED", "/connected-apps"),
          Map.entry("EXCHANGE_BULK_UNDO_APPLIED", "/connected-apps"),
          Map.entry("INVENTORY_TRANSFERRED_TO_USER", "/inventory/my"),
          Map.entry("INVENTORY_TRANSFERRED_FROM_USER", "/inventory/my"),
          Map.entry("INVENTORY_BOOKED_OUT_BY_OTHER", "/inventory/my"),
          Map.entry("INVENTORY_POOL_CHANGED_BY_OTHER", "/inventory/my"),
          Map.entry("BANK_BOOKING_REQUEST_APPROVED", "/bank/requests"),
          Map.entry("EXCHANGE_CLIENT_SUSPENDED", "/connected-apps"),
          Map.entry("EXCHANGE_CLIENT_ACTIVATED", "/connected-apps"),
          Map.entry("EXCHANGE_CLIENT_UPDATE_REQUIRED", "/connected-apps"),
          Map.entry("EXCHANGE_CLIENT_CAPABILITY_REMOVED", "/connected-apps"),
          Map.entry("EXCHANGE_SWITCHED_OFF", "/connected-apps"),
          Map.entry("SUPPLY_DONATION_PLEDGED", "/supply/donations/incoming"),
          Map.entry("SUPPLY_DONATION_RECEIVED", "/supply/donations/mine"),
          Map.entry("SUPPLY_DONATION_DECLINED", "/supply/donations/mine"),
          Map.entry("SUPPLY_NEED_ORDER_COVERED", "/supply/needs"),
          Map.entry("PURCHASING_NEED_RELEASED", "/purchasing/deals"),
          Map.entry("PURCHASING_PAUSED", "/purchasing/deals"),
          Map.entry("PURCHASING_RESUMED", "/purchasing/deals"),
          Map.entry("PURCHASING_DELEGATE_APPOINTED", "/purchasing/deals"),
          Map.entry("PURCHASING_DELEGATE_REVOKED", "/purchasing/deals"),
          Map.entry("HANGAR_SHIP_ASSIGNED", "/hangar"),
          Map.entry("HANGAR_FITTED_RESET", "/hangar"),
          Map.entry("HANGAR_CHANGED_BY_ADMIN", "/hangar"),
          Map.entry("HANGAR_SHIPS_ORG_UNIT_CHANGED_BY_ADMIN", "/hangar"),
          Map.entry("BLUEPRINT_CHANGED_BY_ADMIN", "/personal-inventory/blueprints"),
          Map.entry("BLUEPRINT_PURGED_BY_ADMIN", "/personal-inventory/blueprints"),
          Map.entry("ACCOUNT_DELETION_REQUEST_DECLINED", "/profile"),
          Map.entry("ACCOUNT_DELETION_REQUESTED", "/admin/deletion-requests"),
          Map.entry("DISCORD_REGISTRATION_PENDING", "/admin/discord-registrations"));

  /** The suffix that marks a render parameter as a code with a localized word (REQ-NOTIF-028). */
  private static final String CODE_SUFFIX = "Code";

  /** Render parameters that carry an amount and are shown with thousands separators. */
  private static final Set<String> AMOUNT_PARAMETERS =
      Set.of("amount", "fee", "balance", "total", "open", "donation");

  /**
   * A plain, locale-free decimal as the backend stores it, e.g. {@code 1500000} or {@code 12.50}.
   */
  private static final Pattern PLAIN_AMOUNT = Pattern.compile("-?\\d+(\\.\\d+)?");

  private static final int DIGIT_GROUP = 3;

  private static final DateTimeFormatter DISPLAY_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
  private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
  private static final String REGISTRATION_ID = "keycloak";

  /** Upper bound on cause-chain traversal in {@link #isTermsGateSignal(Throwable)} (loop guard). */
  private static final int MAX_CAUSE_DEPTH = 25;

  private final NotificationBackendClient notificationClient;
  private final MessageSource messageSource;
  private final BackendSideChannels backendSideChannels;
  private final OAuth2AuthorizedClientManager authorizedClientManager;
  private final MeterRegistry meterRegistry;

  /** Live browser-to-backend SSE relays open on this instance (relay-connections gauge source). */
  private final AtomicInteger relayConnections = new AtomicInteger();

  /** Binds the {@code basetool_notification_relay_connections} gauge to the live relay count. */
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
   * Creates the {@link SseEmitter} backing a new browser relay; overridable in tests.
   *
   * @return a fresh emitter holding the browser connection open for {@link #STREAM_TIMEOUT_MS}
   */
  @org.jetbrains.annotations.NotNull
  protected SseEmitter newEmitter() {
    return new SseEmitter(STREAM_TIMEOUT_MS);
  }

  /**
   * Renders the notifications page with the newest {@value #PAGE_LIMIT} notifications, the total
   * count and the more-pages flag (REQ-NOTIF-019); falls back to an empty list on a backend error.
   *
   * @param model the view model
   * @return the notifications template name
   */
  @org.jetbrains.annotations.NotNull
  @GetMapping
  public String page(Model model) {
    if (!SectionFallback.run(
        log,
        "notifications",
        () -> {
          PageResponse<NotificationDto> firstPage = loadPage(0);
          List<NotificationViewDto> views = toViews(firstPage);
          model.addAttribute("notifications", views);
          model.addAttribute("notifTotal", firstPage == null ? 0L : firstPage.totalElements());
          model.addAttribute("notifHasMore", hasMore(firstPage));
        })) {
      model.addAttribute("notifications", List.of());
      model.addAttribute("notifTotal", 0L);
      model.addAttribute("notifHasMore", false);
      model.addAttribute("error", "notifications.error.load");
    }
    return "notification/notifications";
  }

  /**
   * Returns one further server-localized inbox page for the load-more control (REQ-NOTIF-019).
   *
   * @param page the zero-based page index to fetch (page 0 is the initial server render)
   * @return the localized page slice with the total count and the more-pages flag
   */
  @org.jetbrains.annotations.NotNull
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
   * Relays the backend notification SSE stream to the browser (REQ-NOTIF-010).
   *
   * <p>The bearer token is obtained once, at open, through the single-flight authorized-client
   * manager, so an expired one is refreshed first (REQ-SEC-012), and sent as a plain {@code
   * Authorization} header. Without a usable token, or on a backend error, the stream fails soft.
   *
   * @param request the current servlet request, handed to the manager to load and store the
   *     session's authorized client
   * @param response the current servlet response, handed to the manager alongside the request
   * @param authentication the authenticated principal owning the session
   * @return the SSE emitter writing to the browser
   */
  @org.jetbrains.annotations.NotNull
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    SseEmitter emitter = newEmitter();
    OAuth2AuthorizedClient authorizedClient;
    try {
      authorizedClient =
          authorizedClientManager.authorize(
              OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                  .principal(authentication)
                  .attribute(HttpServletRequest.class.getName(), request)
                  .attribute(HttpServletResponse.class.getName(), response)
                  .build());
    } catch (RuntimeException e) {
      log.debug(
          "Notification stream could not obtain an access token ({}); completing",
          e.getClass().getSimpleName());
      emitter.complete();
      return emitter;
    }
    if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
      emitter.complete();
      return emitter;
    }
    final String bearerToken = authorizedClient.getAccessToken().getTokenValue();
    try {
      emitter.send(SseEmitter.event().comment("ready"));
    } catch (IOException | RuntimeException e) {
      log.debug(
          "Notification stream initial commit failed ({}); completing",
          e.getClass().getSimpleName());
      emitter.complete();
      return emitter;
    }
    Disposable.Swap subscription = Disposables.swap();
    emitter.onCompletion(subscription::dispose);
    emitter.onTimeout(
        () -> {
          subscription.dispose();
          completeQuietly(emitter);
        });
    emitter.onError(_ -> subscription.dispose());
    String consentUrl = request.getContextPath() + TermsAcceptanceGateFilter.CONSENT_PATH;
    relayConnections.incrementAndGet();
    subscription.update(
        backendSideChannels
            .notificationStream(bearerToken)
            .doFinally(_ -> relayConnections.decrementAndGet())
            .subscribe(
                event -> forward(emitter, event, subscription),
                error -> handleStreamError(emitter, error, consentUrl),
                () -> completeQuietly(emitter)));
    return emitter;
  }

  /**
   * Returns the caller's unread count for the always-on badge poll.
   *
   * @return the unread count payload (fail-soft to zero on a backend hiccup)
   */
  @org.jetbrains.annotations.NotNull
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
      notificationClient.markRead(id);
      return ResponseEntity.ok(new NotificationCountResponse(currentUnreadCount()));
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
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
      NotificationBulkResultDto result = notificationClient.markAllRead();
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
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
      notificationClient.delete(id);
      return ResponseEntity.ok(new NotificationCountResponse(currentUnreadCount()));
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
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
      NotificationBulkResultDto result = notificationClient.clearRead();
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    }
  }

  private List<NotificationViewDto> loadView(int limit) {
    List<NotificationDto> dtos = notificationClient.recent(limit);
    if (dtos == null) {
      return List.of();
    }
    Locale locale = LocaleContextHolder.getLocale();
    return dtos.stream().map(dto -> toView(dto, locale)).toList();
  }

  /**
   * Fetches one page of the caller's inbox from the paginated backend listing, newest first.
   *
   * @param page the zero-based page index
   * @return the backend page response, or {@code null} when the backend returned none
   */
  private PageResponse<NotificationDto> loadPage(int page) {
    return notificationClient.page(page, PAGE_LIMIT);
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

  @org.jetbrains.annotations.NotNull
  private NotificationViewDto toView(
      @org.jetbrains.annotations.NotNull NotificationDto dto, Locale locale) {
    return new NotificationViewDto(
        dto.id(),
        render(dto.type(), dto.params(), locale),
        dto.read(),
        dto.createdAt() == null ? "" : DISPLAY_FORMAT.format(dto.createdAt()),
        dto.entityType(),
        dto.entityId(),
        targetOf(dto.type(), dto.entityType(), dto.entityId()));
  }

  /**
   * Resolves the page a notification is about, for the inbox row link.
   *
   * @param type the notification type
   * @param entityType the originating aggregate's type tag
   * @param entityId the originating aggregate's id
   * @return a same-origin path, or {@code null} when the web app has no page for the type that its
   *     seeded recipients can open
   */
  static @Nullable String targetOf(
      @Nullable String type, @Nullable String entityType, @Nullable UUID entityId) {
    if (type == null) {
      return null;
    }
    String fixed = PAGE_TARGETS.get(type);
    if (fixed != null || UNLINKED_TYPES.contains(type)) {
      return fixed;
    }
    String prefix = entityType == null ? null : ENTITY_PAGES.get(entityType);
    return prefix != null && entityId != null ? prefix + entityId : null;
  }

  /**
   * Renders a notification into the member's language: the type's template with every parameter
   * filled in, a parameter named {@code <name>Code} also giving {@code {<name>}} its localized word
   * (REQ-NOTIF-028).
   *
   * @param type the notification type
   * @param params the stored render parameters, or {@code null}
   * @param locale the member's locale
   * @return the rendered text
   */
  String render(String type, Map<String, String> params, Locale locale) {
    String key = "notifications.type." + type;
    String template = messageSource.getMessage(key, null, key, locale);
    if (template == null || template.equals(key)) {
      template =
          messageSource.getMessage("notifications.type.generic", null, "Notification", locale);
    }
    if (params != null) {
      for (Map.Entry<String, String> entry : params.entrySet()) {
        String value = entry.getValue() == null ? "" : entry.getValue();
        if (AMOUNT_PARAMETERS.contains(entry.getKey())) {
          value = groupAmount(value, locale);
        }
        template = template.replace("{" + entry.getKey() + "}", value);
        if (isCodeParameter(entry.getKey()) && entry.getValue() != null) {
          String name = entry.getKey().substring(0, entry.getKey().length() - CODE_SUFFIX.length());
          String valueKey = "notifications.value." + name + "." + entry.getValue();
          String word = messageSource.getMessage(valueKey, null, entry.getValue(), locale);
          template = template.replace("{" + name + "}", word == null ? entry.getValue() : word);
        }
      }
    }
    return template;
  }

  /**
   * Formats a plain decimal amount with the thousands and decimal separators of the member's
   * language ({@code 1500000} becomes {@code 1.500.000} in German and {@code 1,500,000} in English,
   * {@code 12.50} becomes {@code 12,50} or {@code 12.50}); any other text is returned unchanged.
   *
   * @param value the stored parameter value
   * @param locale the member's locale
   * @return the grouped amount, or {@code value} when it is not a plain decimal
   */
  static String groupAmount(String value, Locale locale) {
    if (!PLAIN_AMOUNT.matcher(value).matches()) {
      return value;
    }
    DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
    char groupSeparator = symbols.getGroupingSeparator();
    char decimalSeparator = symbols.getDecimalSeparator();
    boolean negative = value.startsWith("-");
    String unsigned = negative ? value.substring(1) : value;
    int dot = unsigned.indexOf('.');
    String integer = dot < 0 ? unsigned : unsigned.substring(0, dot);
    StringBuilder grouped = new StringBuilder(integer.length() + integer.length() / DIGIT_GROUP);
    for (int i = 0; i < integer.length(); i++) {
      if (i > 0 && (integer.length() - i) % DIGIT_GROUP == 0) {
        grouped.append(groupSeparator);
      }
      grouped.append(integer.charAt(i));
    }
    if (dot >= 0) {
      grouped.append(decimalSeparator).append(unsigned, dot + 1, unsigned.length());
    }
    return negative ? "-" + grouped : grouped.toString();
  }

  /**
   * Whether a parameter carries a code that has a localized word, by its {@code Code} suffix.
   *
   * @param name the parameter name
   * @return {@code true} for a name such as {@code changeCode}
   */
  private static boolean isCodeParameter(String name) {
    return name.length() > CODE_SUFFIX.length() && name.endsWith(CODE_SUFFIX);
  }

  private long currentUnreadCount() {
    NotificationCountResponse response =
        SectionFallback.load(log, "unread-count", notificationClient::unreadCount, null);
    return response != null && response.count() != null ? response.count() : 0L;
  }

  /**
   * Terminates the relayed SSE stream on a backend error.
   *
   * <p>A re-authentication signal first sends a named {@code reauth} event carrying the login path.
   * Every error completes the emitter cleanly, never with {@code completeWithError}.
   *
   * @param emitter the browser-facing emitter to terminate
   * @param error the error raised by the backend stream subscription
   */
  private static void handleStreamError(SseEmitter emitter, Throwable error, String consentUrl) {
    if (ReauthenticationRequiredException.isReauthSignal(error)) {
      log.debug("Notification stream needs re-authentication; signalling the browser to re-login");
      try {
        emitter.send(
            SseEmitter.event().name("reauth").data(ReauthenticationRequiredException.REAUTH_PATH));
        completeQuietly(emitter);
      } catch (IOException | RuntimeException sendFailure) {
        completeQuietly(emitter);
      }
      return;
    }
    if (isTermsGateSignal(error)) {
      log.debug("Notification stream is behind the consent gate; handing the browser off to it");
      try {
        emitter.send(
            SseEmitter.event().name(TermsAcceptanceGateFilter.SSE_GATE_EVENT).data(consentUrl));
        completeQuietly(emitter);
      } catch (IOException | RuntimeException sendFailure) {
        completeQuietly(emitter);
      }
      return;
    }
    log.debug(
        "Notification stream dropped ({}); completing cleanly, poll fallback keeps the badge fresh",
        error.getClass().getSimpleName());
    completeQuietly(emitter);
  }

  /**
   * Reports whether {@code error} or its cause chain is the backend's 403 refusal for missing Terms
   * of Use consent, matched on the problem {@code code} (REQ-SEC-028).
   *
   * @param error the error raised by the backend stream subscription
   * @return {@code true} when the backend refused the stream for missing consent
   */
  private static boolean isTermsGateSignal(Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof WebClientResponseException response
          && response.getStatusCode() == HttpStatus.FORBIDDEN
          && response
              .getResponseBodyAsString(StandardCharsets.UTF_8)
              .contains(BackendServiceException.CODE_TERMS_NOT_ACCEPTED)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * Writes one backend event to the browser; a failed write stops the upstream subscription.
   *
   * <p>An {@link IOException} means the browser is gone and the container already runs its error
   * dispatch, so the emitter is left to it; any other failure completes the emitter quietly.
   *
   * @param emitter the browser-facing emitter
   * @param event the backend event to relay
   * @param subscription the upstream subscription to cancel when the write fails
   */
  private static void forward(
      SseEmitter emitter, ServerSentEvent<String> event, Disposable subscription) {
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
    } catch (IOException e) {
      log.debug(
          "Notification stream send failed ({}); the browser is gone",
          e.getClass().getSimpleName());
      subscription.dispose();
    } catch (RuntimeException e) {
      log.debug(
          "Notification stream send failed ({}); completing cleanly", e.getClass().getSimpleName());
      subscription.dispose();
      completeQuietly(emitter);
    }
  }

  /**
   * Completes the emitter from a non-container thread, tolerating a request the container has
   * already ended.
   *
   * @param emitter the browser-facing emitter to complete
   */
  private static void completeQuietly(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (IllegalStateException e) {
      log.debug(
          "Notification stream already ended by the container ({}); nothing to complete",
          e.getClass().getSimpleName());
    }
  }
}
