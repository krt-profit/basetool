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
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code subscribe} flow of a {@code /ws/sync} socket (REQ-FE-015, REQ-SEC-028): topic
 * validation, the per-session throttle and topic cap, the asynchronous authorization probe through
 * {@link LiveSyncSubscriptionAuthorizer}, and the {@code subscribed} / {@code denied} control
 * frames.
 */
@Slf4j(topic = "de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncWebSocketHandler")
@RequiredArgsConstructor
final class LiveSyncSubscriptions {

  /**
   * Hard cap on distinct topics one multiplexed socket may subscribe to, so a crafted client cannot
   * fan one socket across unbounded rooms. A page subscribes to a handful of topics, so this sits
   * far above any legitimate use.
   */
  static final int MAX_TOPICS_PER_SESSION = 16;

  /** Safety margin before the token's expiry from which a subscribe already counts as lapsed. */
  private static final Duration TOKEN_EXPIRY_SKEW = Duration.ofSeconds(5);

  private final LiveSyncRooms rooms;
  private final LiveSyncRateLimiter rateLimiter;
  private final LiveSyncSubscriptionAuthorizer authorizer;

  /**
   * Runs subscribe-authorization probes off the container thread; a {@link
   * RejectedExecutionException} resolves the subscribe as indeterminate.
   */
  private final Executor authExecutor;

  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  /**
   * Handles a {@code subscribe} frame: validates the topic, applies the rate limit, topic cap and
   * idempotency, then authorizes asynchronously on {@link #authExecutor}; a saturated executor
   * resolves the subscribe as indeterminate.
   *
   * @param session the subscribing session
   * @param rawTopic the frame's {@code topic} field, or {@code null} when absent
   */
  void subscribe(@NotNull WebSocketSession session, @Nullable String rawTopic) {
    LiveSyncTopic topic = LiveSyncTopic.parse(rawTopic);
    if (topic == null) {
      log.debug(
          "Live-sync subscribe to unknown topic '{}' refused",
          LogSafe.text(rawTopic, LiveSyncWebSocketHandler.MAX_LOGGED_TOPIC_LENGTH));
      meterRegistry.counter(MetricNames.LIVESYNC_INVALID_TOPIC).increment();
      sendControlFrame(session, "denied", rawTopic, null);
      return;
    }
    Set<String> subs = LiveSyncSessionRegistry.subscriptions(session);
    if (subs == null) {
      return;
    }
    if (!rateLimiter.allowSubscribeFrame(session)) {
      rooms.droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      log.debug(
          "Live-sync subscribe to topic {} dropped (per-session throttle)", topic.canonical());
      return;
    }
    if (subs.contains(topic.canonical())) {
      sendControlFrame(session, "subscribed", topic.canonical(), null);
      return;
    }
    if (tokenLapsed(session)) {
      closeQuietly(session, LiveSyncWebSocketHandler.TOKEN_EXPIRED);
      return;
    }
    if (subs.size() >= MAX_TOPICS_PER_SESSION) {
      rooms.droppedCounter(topic, MetricNames.DROPPED_TOPIC_CAP).increment();
      sendControlFrame(session, "denied", topic.canonical(), null);
      return;
    }
    subs.add(topic.canonical());
    String token = (String) session.getAttributes().get(LiveSyncWebSocketHandler.ATTR_ACCESS_TOKEN);
    UUID pin =
        session.getAttributes().get(LiveSyncWebSocketHandler.ATTR_ACTIVE_ORG_UNIT) instanceof UUID u
            ? u
            : null;
    Set<String> authorities = capturedAuthorities(session);
    UUID subject =
        session.getAttributes().get(LiveSyncWebSocketHandler.ATTR_SUBJECT) instanceof UUID u
            ? u
            : null;
    try {
      authExecutor.execute(
          () -> authorizeAndRegister(session, topic, token, pin, authorities, subject));
    } catch (RejectedExecutionException _) {
      LiveSyncSubscriptionAuthorizer.Decision verdict =
          LiveSyncSubscriptionAuthorizer.indeterminate(topic);
      rooms.droppedCounter(topic, MetricNames.DROPPED_AUTHORIZE_SATURATED).increment();
      log.warn(
          "Live-sync subscribe access check for topic {} was not scheduled (auth executor"
              + " saturated); resolved as {}",
          topic.canonical(),
          verdict);
      completeSubscribe(session, topic, verdict);
    }
  }

  /**
   * Runs the subscribe-authorization probe and applies its verdict; an unexpected exception
   * resolves via {@link LiveSyncSubscriptionAuthorizer#indeterminate(LiveSyncTopic)}, and a
   * fail-closed indeterminate verdict is logged at WARN.
   *
   * @param session the subscribing session
   * @param topic the topic being authorized
   * @param token the captured OAuth2 access token (may be {@code null})
   * @param pin the captured active-org-unit pin (may be {@code null})
   * @param authorities the captured authorities for a local role check (may be {@code null})
   * @param subject the captured user id for a personal room (may be {@code null})
   */
  private void authorizeAndRegister(
      @NotNull WebSocketSession session,
      @NotNull LiveSyncTopic topic,
      String token,
      UUID pin,
      Set<String> authorities,
      UUID subject) {
    LiveSyncSubscriptionAuthorizer.Decision decision;
    try {
      decision = authorizer.authorize(topic, token, pin, authorities, subject);
      if (decision == LiveSyncSubscriptionAuthorizer.Decision.DENY_INDETERMINATE) {
        log.warn(
            "Live-sync subscribe to topic {} failed closed because its access check was"
                + " indeterminate; this tab gets no live updates for it until it reconnects",
            topic.canonical());
      }
    } catch (RuntimeException e) {
      decision = LiveSyncSubscriptionAuthorizer.indeterminate(topic);
      if (decision == LiveSyncSubscriptionAuthorizer.Decision.DENY_INDETERMINATE) {
        log.warn(
            "Live-sync subscribe to topic {} failed closed: its access check threw",
            topic.canonical(),
            e);
      } else {
        log.debug(
            "Live-sync subscribe access check threw for authentication-only topic {}",
            topic.canonical(),
            e);
      }
    }
    completeSubscribe(session, topic, decision);
  }

  /**
   * Finalises a subscribe: on a deny releases the reserved slot and sends {@code denied} with its
   * reason; on an allow joins the room, acks {@code subscribed} and sends the initial presence
   * snapshot where applicable. Logs nothing itself.
   *
   * @param session the subscribing session
   * @param topic the authorized topic
   * @param decision the authorizer verdict
   */
  private void completeSubscribe(
      @NotNull WebSocketSession session,
      @NotNull LiveSyncTopic topic,
      @NotNull LiveSyncSubscriptionAuthorizer.Decision decision) {
    Set<String> subs = LiveSyncSessionRegistry.subscriptions(session);
    if (decision.denied()) {
      if (subs != null) {
        subs.remove(topic.canonical());
      }
      String reason = denyReason(decision);
      sendControlFrame(session, "denied", topic.canonical(), reason);
      subscribeCounter(topic, MetricNames.OUTCOME_DENIED, reason).increment();
      if (decision == LiveSyncSubscriptionAuthorizer.Decision.DENY_TOKEN_REJECTED) {
        closeQuietly(session, LiveSyncWebSocketHandler.TOKEN_EXPIRED);
      }
      return;
    }
    WebSocketSession decorated = LiveSyncSessionRegistry.decorated(session);
    if (!decorated.isOpen() || subs == null || !subs.contains(topic.canonical())) {
      if (subs != null) {
        subs.remove(topic.canonical());
      }
      return;
    }
    rooms.join(decorated, topic);
    if (!decorated.isOpen()) {
      rooms.leave(decorated, topic.canonical());
      return;
    }
    sendControlFrame(session, "subscribed", topic.canonical(), null);
    if (topic.topicClass().presenceEnabled()) {
      rooms.sendSnapshot(decorated, topic);
    }
    subscribeCounter(topic, MetricNames.OUTCOME_ALLOWED, MetricNames.REASON_NONE).increment();
  }

  /**
   * Maps a refusal to its bounded {@code reason} value, used as metric tag and wire field
   * (REQ-OBS-006).
   *
   * @param decision the refusing verdict
   * @return {@link MetricNames#SUBSCRIBE_DENY_INDETERMINATE} for a fail-closed indeterminate
   *     refusal, {@link MetricNames#SUBSCRIBE_DENY_AUTHZ} for an explicit authorization denial
   */
  @NotNull
  private static String denyReason(@NotNull LiveSyncSubscriptionAuthorizer.Decision decision) {
    return decision == LiveSyncSubscriptionAuthorizer.Decision.DENY
        ? MetricNames.SUBSCRIBE_DENY_AUTHZ
        : MetricNames.SUBSCRIBE_DENY_INDETERMINATE;
  }

  /**
   * Sends a {@code subscribed} or {@code denied} control frame, tolerating a closed peer; a frame
   * without a {@code reason} is one the client treats as terminal.
   *
   * @param session the target session (its decorator is written to)
   * @param type the control-frame type ({@code subscribed} / {@code denied})
   * @param topicString the topic echoed as-is; may be unparseable for a denied subscribe
   * @param reason the bounded refusal reason, or {@code null} to omit the field
   */
  private void sendControlFrame(
      @NotNull WebSocketSession session,
      @NotNull String type,
      String topicString,
      @Nullable String reason) {
    WebSocketSession target = LiveSyncSessionRegistry.decorated(session);
    if (!target.isOpen()) {
      return;
    }
    String payload;
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("type", type);
      if (topicString != null) {
        root.put("topic", topicString);
      }
      if (reason != null) {
        root.put("reason", reason);
      }
      payload = objectMapper.writeValueAsString(root);
    } catch (JacksonException e) {
      log.warn("Failed to serialise live-sync control frame ({})", type, e);
      return;
    }
    try {
      target.sendMessage(new TextMessage(payload));
    } catch (IOException | IllegalStateException | SessionLimitExceededException e) {
      log.debug("Failed to send live-sync control frame to session {}", target.getId(), e);
    }
  }

  /**
   * Reports whether the access token captured at handshake has expired, or is about to within
   * {@link #TOKEN_EXPIRY_SKEW}; a socket without a recorded expiry is never lapsed here.
   *
   * @param session the session
   * @return {@code true} when the captured token and authorities are stale
   */
  private static boolean tokenLapsed(@NotNull WebSocketSession session) {
    return session.getAttributes().get(LiveSyncWebSocketHandler.ATTR_TOKEN_EXPIRES_AT)
            instanceof Instant expiresAt
        && !Instant.now().plus(TOKEN_EXPIRY_SKEW).isBefore(expiresAt);
  }

  /**
   * Closes a session with the given status, tolerating one that is already gone.
   *
   * @param session the session to close
   * @param status the close status carrying the client's reconnect instruction
   */
  private static void closeQuietly(@NotNull WebSocketSession session, @NotNull CloseStatus status) {
    try {
      session.close(status);
    } catch (IOException | RuntimeException e) {
      log.debug("Live-sync socket close with {} failed", status.getCode(), e);
    }
  }

  /**
   * Resolves the authorities captured at handshake ({@link
   * LiveSyncWebSocketHandler#ATTR_AUTHORITIES}).
   *
   * @param session the session
   * @return the captured authority names, or {@code null} when none were captured
   */
  @Nullable
  private static Set<String> capturedAuthorities(@NotNull WebSocketSession session) {
    Object value = session.getAttributes().get(LiveSyncWebSocketHandler.ATTR_AUTHORITIES);
    if (!(value instanceof Set<?> raw)) {
      return null;
    }
    return raw.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Returns the {@code basetool_livesync_subscribe_total{topic_class, outcome, reason}} counter for
   * a subscribe verdict.
   *
   * @param topic the subscribed topic (its class tags the metric)
   * @param outcome {@link MetricNames#OUTCOME_ALLOWED} or {@link MetricNames#OUTCOME_DENIED}
   * @param reason the bounded deny reason, or {@link MetricNames#REASON_NONE} on an allow
   * @return the counter to increment
   */
  private Counter subscribeCounter(
      @NotNull LiveSyncTopic topic, @NotNull String outcome, @NotNull String reason) {
    return meterRegistry.counter(
        MetricNames.LIVESYNC_SUBSCRIBE,
        MetricNames.TAG_TOPIC_CLASS,
        topic.topicClass().metricLabel(),
        MetricNames.TAG_OUTCOME,
        outcome,
        MetricNames.TAG_REASON,
        reason);
  }
}
