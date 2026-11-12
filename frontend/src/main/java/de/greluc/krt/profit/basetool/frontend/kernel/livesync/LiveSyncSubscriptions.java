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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
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
 * {@link LiveSyncSubscriptionAuthorizer}, the {@code subscribed} / {@code denied} control frames,
 * and the re-authorization of the rooms the socket holds, which evicts it from a room it may no
 * longer read.
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

  /** Margin before a captured token's expiry from which it already counts as lapsed. */
  private static final Duration TOKEN_EXPIRY_SKEW = Duration.ofSeconds(5);

  /**
   * How often the rooms one socket holds are authorized again while it holds them, for every class
   * that needs a check.
   */
  static final Duration REAUTHORIZE_INTERVAL = Duration.ofMinutes(2);

  /**
   * Session-attribute key ({@link AtomicLong}) holding the {@link #nanoClock} reading from which
   * the socket's checked rooms are due for their re-authorization, or {@link #REAUTHORIZING} while
   * one runs.
   */
  private static final String ATTR_REAUTHORIZE_DUE = "livesync.reauthorizeDue";

  /** The {@link #ATTR_REAUTHORIZE_DUE} value while a socket's rooms are being re-authorized. */
  private static final long REAUTHORIZING = Long.MAX_VALUE;

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

  /** Monotonic nanosecond clock the re-authorization schedule of a socket's rooms reads. */
  private final LongSupplier nanoClock;

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
    HandshakeCapture capture = HandshakeCapture.of(session);
    try {
      authExecutor.execute(() -> authorizeAndRegister(session, topic, capture));
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
   * Re-checks the rooms a socket holds whose class needs a check (REQ-FE-015): closes the socket
   * with {@link LiveSyncWebSocketHandler#TOKEN_EXPIRED} once its captured token has lapsed, and
   * otherwise, at most once per {@link #REAUTHORIZE_INTERVAL}, re-runs the authorizer for those
   * rooms on {@link #authExecutor} with what the handshake captured. A saturated executor keeps the
   * rooms until the next interval; a socket without a checked room is left alone.
   *
   * @param session the socket, as the session registry holds it
   * @param nowNanos the current {@link #nanoClock} reading
   */
  void reauthorize(@NotNull WebSocketSession session, long nowNanos) {
    List<LiveSyncTopic> checked = checkedRooms(session);
    if (checked.isEmpty()) {
      return;
    }
    if (tokenLapsed(session)) {
      countReauthorized(checked, MetricNames.REAUTHORIZE_TOKEN_EXPIRED);
      log.debug(
          "Live-sync socket holding {} checked room(s) closed: its captured token lapsed",
          checked.size());
      closeQuietly(session, LiveSyncWebSocketHandler.TOKEN_EXPIRED);
      return;
    }
    AtomicLong due = claimDueReauthorization(session, nowNanos);
    if (due == null) {
      return;
    }
    HandshakeCapture capture = HandshakeCapture.of(session);
    try {
      authExecutor.execute(() -> reauthorizeRooms(session, checked, capture, due));
    } catch (RejectedExecutionException _) {
      countReauthorized(checked, MetricNames.REAUTHORIZE_INDETERMINATE);
      log.debug(
          "Live-sync re-authorization of {} room(s) was not scheduled (auth executor saturated);"
              + " they stay until the next check",
          checked.size());
      due.set(nowNanos + REAUTHORIZE_INTERVAL.toNanos());
    }
  }

  /**
   * Runs the subscribe-authorization probe and applies its verdict; an unexpected exception
   * resolves via {@link LiveSyncSubscriptionAuthorizer#indeterminate(LiveSyncTopic)}, and a
   * fail-closed indeterminate verdict is logged at WARN.
   *
   * @param session the subscribing session
   * @param topic the topic being authorized
   * @param capture what the socket's handshake captured
   */
  private void authorizeAndRegister(
      @NotNull WebSocketSession session,
      @NotNull LiveSyncTopic topic,
      @NotNull HandshakeCapture capture) {
    LiveSyncSubscriptionAuthorizer.Decision decision;
    try {
      decision = capture.authorize(authorizer, topic);
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
   * Lists the rooms a socket holds whose class needs an authorization check; a subscribe still
   * being authorized holds no room yet.
   *
   * @param session the socket
   * @return the checked rooms, empty for a closed socket or one that holds none
   */
  @NotNull
  @Unmodifiable
  private List<LiveSyncTopic> checkedRooms(@NotNull WebSocketSession session) {
    Set<String> subs = LiveSyncSessionRegistry.subscriptions(session);
    if (subs == null || !session.isOpen()) {
      return List.of();
    }
    WebSocketSession decorated = LiveSyncSessionRegistry.decorated(session);
    return subs.stream()
        .map(LiveSyncTopic::parse)
        .filter(Objects::nonNull)
        .filter(topic -> topic.topicClass().needsCheck())
        .filter(topic -> rooms.holds(decorated, topic.canonical()))
        .toList();
  }

  /**
   * Claims a socket's re-authorization when it is due, starting the socket's schedule one {@link
   * #REAUTHORIZE_INTERVAL} from now the first time it is seen holding a checked room.
   *
   * @param session the socket
   * @param nowNanos the current {@link #nanoClock} reading
   * @return the claimed schedule, set to {@link #REAUTHORIZING}, or {@code null} when no check is
   *     due
   */
  @Nullable
  private static AtomicLong claimDueReauthorization(
      @NotNull WebSocketSession session, long nowNanos) {
    long next = nowNanos + REAUTHORIZE_INTERVAL.toNanos();
    Map<String, Object> attributes = session.getAttributes();
    Object stored = attributes.computeIfAbsent(ATTR_REAUTHORIZE_DUE, _ -> new AtomicLong(next));
    if (!(stored instanceof AtomicLong due)) {
      return null;
    }
    long dueNanos = due.get();
    // Monotonic readings compare by their difference, which survives the counter's wrap-around.
    if (dueNanos == REAUTHORIZING || nowNanos - dueNanos < 0) {
      return null;
    }
    return due.compareAndSet(dueNanos, REAUTHORIZING) ? due : null;
  }

  /**
   * Re-runs the authorizer for a socket's checked rooms and applies each verdict: an explicit
   * denial evicts the socket from that room, a rejected token closes the socket with {@link
   * LiveSyncWebSocketHandler#TOKEN_EXPIRED}, and an allowed or indeterminate verdict keeps the room
   * until the next check.
   *
   * @param session the socket
   * @param checked the checked rooms it held when the check was scheduled
   * @param capture what the socket's handshake captured
   * @param due the socket's schedule, set one {@link #REAUTHORIZE_INTERVAL} ahead when this ends
   */
  private void reauthorizeRooms(
      @NotNull WebSocketSession session,
      @NotNull List<LiveSyncTopic> checked,
      @NotNull HandshakeCapture capture,
      @NotNull AtomicLong due) {
    try {
      for (LiveSyncTopic topic : checked) {
        if (!session.isOpen()) {
          return;
        }
        LiveSyncSubscriptionAuthorizer.Decision verdict = reauthorizationVerdict(topic, capture);
        reauthorizeCounter(topic, reauthorizeOutcome(verdict)).increment();
        if (verdict == LiveSyncSubscriptionAuthorizer.Decision.DENY) {
          evict(session, topic);
        } else if (verdict == LiveSyncSubscriptionAuthorizer.Decision.DENY_TOKEN_REJECTED) {
          closeQuietly(session, LiveSyncWebSocketHandler.TOKEN_EXPIRED);
          return;
        }
      }
    } finally {
      due.set(nanoClock.getAsLong() + REAUTHORIZE_INTERVAL.toNanos());
    }
  }

  /**
   * Re-runs the authorizer for one room a socket holds; an unexpected exception resolves via {@link
   * LiveSyncSubscriptionAuthorizer#indeterminate(LiveSyncTopic)}, which keeps the room.
   *
   * @param topic the held room
   * @param capture what the socket's handshake captured
   * @return the authorizer's verdict
   */
  @NotNull
  private LiveSyncSubscriptionAuthorizer.Decision reauthorizationVerdict(
      @NotNull LiveSyncTopic topic, @NotNull HandshakeCapture capture) {
    try {
      return capture.authorize(authorizer, topic);
    } catch (RuntimeException e) {
      log.debug(
          "Live-sync re-authorization of topic {} threw; the room stays until the next check",
          topic.canonical(),
          e);
      return LiveSyncSubscriptionAuthorizer.indeterminate(topic);
    }
  }

  /**
   * Takes a socket out of a room its re-authorization denied: it leaves the room, its presence
   * there is dropped, the topic slot is freed for a new subscribe, and it gets the terminal {@code
   * denied} frame.
   *
   * @param session the socket
   * @param topic the denied room
   */
  private void evict(@NotNull WebSocketSession session, @NotNull LiveSyncTopic topic) {
    rooms.depart(
        LiveSyncSessionRegistry.decorated(session), topic, LiveSyncSessionRegistry.userId(session));
    Set<String> subs = LiveSyncSessionRegistry.subscriptions(session);
    if (subs != null) {
      subs.remove(topic.canonical());
    }
    sendControlFrame(session, "denied", topic.canonical(), MetricNames.SUBSCRIBE_DENY_AUTHZ);
    log.debug(
        "Live-sync socket left topic {}: its access was re-checked and denied", topic.canonical());
  }

  /**
   * Maps a re-authorization verdict to its bounded {@code outcome} metric value (REQ-OBS-006).
   *
   * @param verdict the authorizer's verdict on a held room
   * @return the outcome the room's re-check is counted under
   */
  @NotNull
  private static String reauthorizeOutcome(
      @NotNull LiveSyncSubscriptionAuthorizer.Decision verdict) {
    return switch (verdict) {
      case ALLOW -> MetricNames.OUTCOME_ALLOWED;
      case DENY -> MetricNames.REAUTHORIZE_EVICTED;
      case DENY_INDETERMINATE -> MetricNames.REAUTHORIZE_INDETERMINATE;
      case DENY_TOKEN_REJECTED -> MetricNames.REAUTHORIZE_TOKEN_EXPIRED;
    };
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

  /**
   * Counts one re-check outcome for each of a socket's checked rooms.
   *
   * @param checked the rooms the outcome applies to
   * @param outcome the bounded outcome value
   */
  private void countReauthorized(@NotNull List<LiveSyncTopic> checked, @NotNull String outcome) {
    checked.forEach(topic -> reauthorizeCounter(topic, outcome).increment());
  }

  /**
   * Returns the {@code basetool_livesync_reauthorize_total{topic_class, outcome}} counter for the
   * re-check of one room a socket holds.
   *
   * @param topic the held room (its class tags the metric)
   * @param outcome the bounded re-check outcome
   * @return the counter to increment
   */
  private Counter reauthorizeCounter(@NotNull LiveSyncTopic topic, @NotNull String outcome) {
    return meterRegistry.counter(
        MetricNames.LIVESYNC_REAUTHORIZE,
        MetricNames.TAG_TOPIC_CLASS,
        topic.topicClass().metricLabel(),
        MetricNames.TAG_OUTCOME,
        outcome);
  }

  /**
   * What the {@code /ws/sync} handshake captured on a socket to authorize its rooms, read once per
   * subscribe or re-check.
   *
   * @param token the OAuth2 access token, or {@code null} when none was captured
   * @param pin the active-org-unit pin, or {@code null}
   * @param authorities the caller's authority names, or {@code null} when none were captured
   * @param subject the caller's own user id, or {@code null}
   */
  record HandshakeCapture(
      @Nullable String token,
      @Nullable UUID pin,
      @Nullable Set<String> authorities,
      @Nullable UUID subject) {

    /**
     * Prints the captured values with the access token redacted, so a log line never carries it
     * (REQ-OBS-004).
     *
     * @return the record's components, the token shown only as present or absent
     */
    @Override
    @NotNull
    public String toString() {
      return "HandshakeCapture[token="
          + (token == null ? "null" : "<redacted>")
          + ", pin="
          + pin
          + ", authorities="
          + authorities
          + ", subject="
          + subject
          + "]";
    }

    /**
     * Reads what {@link LiveSyncSyncHandshakeInterceptor} stored on a socket.
     *
     * @param session the socket
     * @return the captured values, each {@code null} when absent
     */
    @NotNull
    static HandshakeCapture of(@NotNull WebSocketSession session) {
      Map<String, Object> attributes = session.getAttributes();
      return new HandshakeCapture(
          attribute(attributes, LiveSyncWebSocketHandler.ATTR_ACCESS_TOKEN, String.class),
          attribute(attributes, LiveSyncWebSocketHandler.ATTR_ACTIVE_ORG_UNIT, UUID.class),
          authorityNames(attributes.get(LiveSyncWebSocketHandler.ATTR_AUTHORITIES)),
          attribute(attributes, LiveSyncWebSocketHandler.ATTR_SUBJECT, UUID.class));
    }

    /**
     * Asks the authorizer whether these captured values admit a topic.
     *
     * @param authorizer the subscribe authorizer
     * @param topic the topic to authorize
     * @return the authorizer's verdict
     */
    @NotNull
    LiveSyncSubscriptionAuthorizer.Decision authorize(
        @NotNull LiveSyncSubscriptionAuthorizer authorizer, @NotNull LiveSyncTopic topic) {
      return authorizer.authorize(topic, token, pin, authorities, subject);
    }

    /**
     * Reads one session attribute of the expected type.
     *
     * @param attributes the socket's attributes
     * @param key the attribute key
     * @param type the type the handshake stores under it
     * @param <T> the attribute's type
     * @return the attribute, or {@code null} when it is absent or of another type
     */
    @Nullable
    private static <T> T attribute(
        @NotNull Map<String, Object> attributes, @NotNull String key, @NotNull Class<T> type) {
      Object value = attributes.get(key);
      return type.isInstance(value) ? type.cast(value) : null;
    }

    /**
     * Narrows the captured {@link LiveSyncWebSocketHandler#ATTR_AUTHORITIES} attribute to its
     * authority names.
     *
     * @param value the attribute value
     * @return the authority names, or {@code null} when none were captured
     */
    @Nullable
    private static Set<String> authorityNames(@Nullable Object value) {
      if (!(value instanceof Set<?> raw)) {
        return null;
      }
      return raw.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .collect(Collectors.toUnmodifiableSet());
    }
  }
}
