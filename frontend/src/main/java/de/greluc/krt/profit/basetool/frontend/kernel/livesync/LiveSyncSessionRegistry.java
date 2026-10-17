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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import de.greluc.krt.profit.basetool.frontend.kernel.security.CurrentUser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * The lifecycle of the {@code /ws/sync} sockets of one replica (REQ-FE-015, REQ-SEC-028): admission
 * at connect, the per-user socket cap, the backpressure decorator, the keepalive sweep and the
 * socket-lifetime timer, plus the session attributes they leave behind.
 */
@Slf4j(topic = "de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncWebSocketHandler")
final class LiveSyncSessionRegistry {

  /**
   * Maximum time in milliseconds a single send may block before the {@link
   * ConcurrentWebSocketSessionDecorator} terminates the peer.
   */
  private static final int SEND_TIME_LIMIT_MS = 5_000;

  /** Maximum bytes buffered for a slow peer before the decorator terminates it. */
  private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

  /** Maximum close-reason length in UTF-8 bytes, the most a close frame can carry. */
  private static final int MAX_CLOSE_REASON_BYTES = 123;

  /**
   * Session-attribute key holding a multiplexed socket's set of subscribed canonical topics (a
   * {@code Set<String>}). Drives the per-session topic cap, idempotent re-subscribe and close-time
   * room cleanup.
   */
  private static final String ATTR_SUBSCRIPTIONS = "livesync.subscriptions";

  private static final String ATTR_USER_ID = "livesync.userId";

  /**
   * Session-attribute key ({@link Boolean}) marking a socket that incremented its user's socket
   * count, so the close path decrements exactly once.
   */
  private static final String ATTR_USER_COUNTED = "livesync.userCounted";

  /**
   * Session-attribute key ({@link Long}) holding the monotonic nanosecond reading taken when the
   * socket was accepted, so the close path can record its lifetime.
   */
  private static final String ATTR_OPENED_NANOS = "livesync.openedNanos";

  private static final String ATTR_DISPLAY_NAME = "livesync.displayName";

  /**
   * Session-attribute key for the {@link ConcurrentWebSocketSessionDecorator} wrapping the raw
   * socket; the decorator is what the rooms hold, resolved via {@link
   * #decorated(WebSocketSession)}.
   */
  private static final String ATTR_DECORATED = "livesync.decorated";

  private final MeterRegistry meterRegistry;
  private final LongSupplier nanoClock;

  /**
   * Records how long each closing socket had been open (see {@link
   * MetricNames#LIVESYNC_SOCKET_LIFETIME}).
   */
  private final Timer socketLifetime;

  /** Every open socket's decorator, subscribed or not; the set the keepalive sweep pings. */
  private final Set<WebSocketSession> liveSessions = ConcurrentHashMap.newKeySet();

  /**
   * Live socket count per user (Keycloak {@code sub}) for the per-user socket cap; an entry is
   * removed at zero.
   */
  private final Map<String, Integer> socketsByUser = new ConcurrentHashMap<>();

  /**
   * Creates the registry and binds the socket-lifetime timer.
   *
   * @param meterRegistry the registry the timer and rejection counters bind to
   * @param nanoClock monotonic nanosecond source for the socket lifetime
   */
  LiveSyncSessionRegistry(@NotNull MeterRegistry meterRegistry, @NotNull LongSupplier nanoClock) {
    this.meterRegistry = meterRegistry;
    this.nanoClock = nanoClock;
    this.socketLifetime =
        Timer.builder(MetricNames.LIVESYNC_SOCKET_LIFETIME)
            .description("How long a live-sync WebSocket session stayed open before closing.")
            .register(meterRegistry);
  }

  /**
   * Admits a new {@code /ws/sync} socket: resolves the principal, enforces {@link
   * LiveSyncWebSocketHandler#MAX_SOCKETS_PER_USER}, wraps it in its backpressure decorator and
   * seeds an empty subscription set.
   *
   * <p>Refuses and closes a socket without accepted Terms of Use ({@link
   * LiveSyncWebSocketHandler#ATTR_TERMS_GATE}, REQ-SEC-028), without a resolvable principal, or
   * over the per-user cap.
   *
   * @param session the freshly opened session
   * @throws IOException when closing a refused socket fails
   */
  void open(@NotNull WebSocketSession session) throws IOException {
    String consentUrl =
        (String) session.getAttributes().get(LiveSyncWebSocketHandler.ATTR_TERMS_GATE);
    if (consentUrl != null) {
      log.debug("Live-sync /ws/sync socket refused (Terms of Use not accepted)");
      socketRejectedCounter(MetricNames.SOCKET_REJECTED_TERMS_GATE).increment();
      session.close(termsConsentRequired(consentUrl));
      return;
    }
    Principal principal = session.getPrincipal();
    String userId = principal == null ? null : resolveUserId(principal);
    if (userId == null) {
      log.debug("Live-sync /ws/sync socket refused (no principal)");
      session.close(CloseStatus.NOT_ACCEPTABLE);
      return;
    }
    if (!tryAcquireUserSocket(userId)) {
      log.debug(
          "Live-sync /ws/sync socket refused (per-user cap {})",
          LiveSyncWebSocketHandler.MAX_SOCKETS_PER_USER);
      socketRejectedCounter(MetricNames.SOCKET_REJECTED_USER_CAP).increment();
      session.close(LiveSyncWebSocketHandler.SOCKET_CAP_EXCEEDED);
      return;
    }
    session.getAttributes().put(ATTR_USER_COUNTED, Boolean.TRUE);
    session.getAttributes().put(ATTR_USER_ID, userId);
    session.getAttributes().put(ATTR_OPENED_NANOS, nanoClock.getAsLong());
    session.getAttributes().put(ATTR_DISPLAY_NAME, resolveDisplayName(principal));
    session.getAttributes().put(ATTR_SUBSCRIPTIONS, ConcurrentHashMap.<String>newKeySet());
    WebSocketSession decorated = wrap(session);
    session.getAttributes().put(ATTR_DECORATED, decorated);
    liveSessions.add(decorated);
  }

  /**
   * Books a closed socket out: records its lifetime, releases its user's socket slot and drops it
   * from the keepalive sweep.
   *
   * @param session the closing raw session
   */
  void close(@NotNull WebSocketSession session) {
    if (session.getAttributes().get(ATTR_OPENED_NANOS) instanceof Long openedNanos) {
      socketLifetime.record(nanoClock.getAsLong() - openedNanos, TimeUnit.NANOSECONDS);
    }
    String userId = userId(session);
    if (userId != null && Boolean.TRUE.equals(session.getAttributes().get(ATTR_USER_COUNTED))) {
      releaseUserSocket(userId);
    }
    liveSessions.remove(decorated(session));
  }

  /**
   * Pings every open socket; a socket whose write fails is dropped, and closed sockets are skipped.
   */
  void tickKeepalive() {
    for (WebSocketSession session : List.copyOf(liveSessions)) {
      try {
        if (!session.isOpen()) {
          liveSessions.remove(session);
          continue;
        }
        session.sendMessage(new PingMessage());
      } catch (IOException | RuntimeException e) {
        liveSessions.remove(session);
        log.debug("Live-sync keepalive ping failed; dropping socket from the sweep", e);
      }
    }
  }

  /**
   * The stable user id of an admitted socket's owner.
   *
   * @param session the session
   * @return the Keycloak {@code sub} or principal name, or {@code null} on a refused socket
   */
  @Nullable
  static String userId(@NotNull WebSocketSession session) {
    return (String) session.getAttributes().get(ATTR_USER_ID);
  }

  /**
   * The display name an admitted socket's owner shows in editor presence.
   *
   * @param session the session
   * @return the preferred username or principal name, or {@code null} on a refused socket
   */
  @Nullable
  static String displayName(@NotNull WebSocketSession session) {
    return (String) session.getAttributes().get(ATTR_DISPLAY_NAME);
  }

  /**
   * Resolves a multiplexed socket's subscription set (its subscribed canonical topics), or {@code
   * null} on a socket without one (a refused socket, or before the set is seeded at connect).
   *
   * @param session the session
   * @return the subscription set, or {@code null}
   */
  @Nullable
  @SuppressWarnings("unchecked")
  static Set<String> subscriptions(@NotNull WebSocketSession session) {
    Object value = session.getAttributes().get(ATTR_SUBSCRIPTIONS);
    return value instanceof Set ? (Set<String>) value : null;
  }

  /**
   * Resolves the {@link ConcurrentWebSocketSessionDecorator} registered for a raw session.
   *
   * @param session the raw (or already-decorated) session
   * @return the registered decorator, or {@code session} when none is stored
   */
  @NotNull
  static WebSocketSession decorated(@NotNull WebSocketSession session) {
    return session.getAttributes().get(ATTR_DECORATED) instanceof WebSocketSession ws
        ? ws
        : session;
  }

  /**
   * Builds the {@link LiveSyncWebSocketHandler#TERMS_CONSENT_REQUIRED_CODE} close status with the
   * consent-page URL as reason, omitting the URL when it exceeds {@link #MAX_CLOSE_REASON_BYTES}.
   *
   * @param consentUrl the context-relative consent-page URL
   * @return the close status to refuse the socket with
   */
  @NotNull
  private static CloseStatus termsConsentRequired(@NotNull String consentUrl) {
    boolean fits = consentUrl.getBytes(StandardCharsets.UTF_8).length <= MAX_CLOSE_REASON_BYTES;
    return new CloseStatus(
        LiveSyncWebSocketHandler.TERMS_CONSENT_REQUIRED_CODE, fits ? consentUrl : null);
  }

  /**
   * Wraps a raw socket in a {@link ConcurrentWebSocketSessionDecorator} bounded by send time and
   * buffer size; the decorator shares the raw session's attributes.
   *
   * @param session the raw session
   * @return the decorator around it
   */
  @NotNull
  private static WebSocketSession wrap(@NotNull WebSocketSession session) {
    return new ConcurrentWebSocketSessionDecorator(
        session,
        SEND_TIME_LIMIT_MS,
        SEND_BUFFER_SIZE_LIMIT,
        ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
  }

  /**
   * Atomically claims a per-user socket slot, refusing when the user would exceed {@link
   * LiveSyncWebSocketHandler#MAX_SOCKETS_PER_USER}.
   *
   * @param userId the connecting user's stable id (Keycloak {@code sub})
   * @return {@code true} if a slot was claimed, {@code false} if the user is at the cap
   */
  private boolean tryAcquireUserSocket(@NotNull String userId) {
    int count = socketsByUser.merge(userId, 1, Integer::sum);
    if (count > LiveSyncWebSocketHandler.MAX_SOCKETS_PER_USER) {
      releaseUserSocket(userId);
      return false;
    }
    return true;
  }

  /**
   * Releases a per-user socket slot, removing the entry when the user's last socket closes.
   *
   * @param userId the closing socket owner's stable id
   */
  private void releaseUserSocket(@NotNull String userId) {
    socketsByUser.compute(userId, (_, count) -> (count == null || count <= 1) ? null : count - 1);
  }

  /**
   * Returns the {@code basetool_livesync_socket_rejected_total{reason}} counter for a socket
   * refused at connect.
   *
   * @param reason the bounded refusal reason (e.g. {@link MetricNames#SOCKET_REJECTED_USER_CAP})
   * @return the counter to increment
   */
  private Counter socketRejectedCounter(@NotNull String reason) {
    return meterRegistry.counter(
        MetricNames.LIVESYNC_SOCKET_REJECTED, MetricNames.TAG_REASON, reason);
  }

  @Nullable
  private static String resolveUserId(@NotNull Principal principal) {
    if (principal instanceof AbstractAuthenticationToken token) {
      Object p = token.getPrincipal();
      if (p instanceof OidcUser oidc
          && CurrentUser.userIdText(oidc) != null
          && !CurrentUser.userIdText(oidc).isBlank()) {
        return CurrentUser.userIdText(oidc);
      }
    }
    String name = principal.getName();
    return (name == null || name.isBlank()) ? null : name;
  }

  private static String resolveDisplayName(@NotNull Principal principal) {
    if (principal instanceof AbstractAuthenticationToken token) {
      Object p = token.getPrincipal();
      if (p instanceof OidcUser oidc) {
        String preferred = oidc.getPreferredUsername();
        if (preferred != null && !preferred.isBlank()) {
          return preferred;
        }
      }
    }
    String name = principal.getName();
    return name == null ? "" : name;
  }
}
