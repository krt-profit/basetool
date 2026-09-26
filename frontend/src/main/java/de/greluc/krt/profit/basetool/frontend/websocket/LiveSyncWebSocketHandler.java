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

package de.greluc.krt.profit.basetool.frontend.websocket;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.frontend.service.LiveSyncPresenceService;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Native-WebSocket relay for tool-wide live sync (REQ-FE-015, ADR-0094): fans a client's {@code
 * changed} section keys out to the other sockets of a {@link LiveSyncTopic} room.
 *
 * <p>Only opaque, whitelisted section keys cross the socket; each peer re-fetches its own data.
 * Rooms are joined by authorized {@code subscribe} frames; publishing needs no subscription.
 * Changes and editor presence are relayed locally first, then across replicas via {@link
 * LiveSyncFanout} (ADR-0126). Per-session and per-topic token buckets, a per-user socket cap,
 * backpressure decorators and a keepalive ping bound abuse and idle timeouts.
 */
@Slf4j
public class LiveSyncWebSocketHandler extends TextWebSocketHandler {

  /** How often the reaper runs to drop expired presence entries and broadcast updates. */
  public static final Duration REAPER_INTERVAL = Duration.ofSeconds(10);

  /**
   * Interval at which every open socket is pinged so the edge proxy's 90&nbsp;s idle timeout does
   * not close it.
   */
  public static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(30);

  /** Hard cap on the number of section keys relayed per {@code changed} frame (abuse guard). */
  private static final int MAX_CHANGED_SECTIONS = 16;

  /**
   * Token-bucket capacity for inbound {@code changed} frames per session — the burst a session may
   * relay before throttling kicks in. Sits far above any human edit cadence, so a legitimate
   * rapid-editing viewer never trips it; it only bounds a crafted client emitting {@code changed}
   * frames in a loop. Package-private for the test.
   */
  static final int CHANGED_BURST = 40;

  /** Token-bucket refill rate for inbound {@code changed} frames, in tokens per second. */
  private static final double CHANGED_REFILL_PER_SEC = 20.0;

  /**
   * Maximum accepted length of a client-supplied presence {@code sectionKey}, also the truncation
   * bound for logged section keys.
   */
  private static final int MAX_SECTION_KEY_LENGTH = 64;

  /**
   * Truncation bound for a client-supplied {@code topic} string rendered into a log line via {@link
   * LogSafe}. A canonical topic is a short class prefix plus at most a UUID (well under this), so a
   * longer value is a crafted client and only the head of it is worth keeping.
   */
  private static final int MAX_LOGGED_TOPIC_LENGTH = 64;

  /**
   * Per-session token-bucket capacity for inbound presence frames ({@code focus} / {@code
   * heartbeat} / {@code blur}).
   */
  static final int PRESENCE_BURST = 20;

  /** Token-bucket refill rate for inbound presence control frames, in tokens per second. */
  private static final double PRESENCE_REFILL_PER_SEC = 10.0;

  /**
   * Per-session token-bucket capacity for inbound {@code subscribe} frames, bounding the rate of
   * authorization probes; 1.5 times {@link #MAX_TOPICS_PER_SESSION}.
   */
  static final int SUBSCRIBE_BURST = 24;

  /** Refill rate for inbound {@code subscribe} frames, in tokens per second. */
  private static final double SUBSCRIBE_REFILL_PER_SEC = 1.0;

  /**
   * Per-topic token-bucket capacity for accepted {@code changed} frames, bounding a room's
   * aggregate relay and fan-out rate across all publishers.
   */
  static final int TOPIC_CHANGED_BURST = 200;

  /** Per-topic refill rate for accepted {@code changed} frames, in tokens per second. */
  private static final double TOPIC_CHANGED_REFILL_PER_SEC = 100.0;

  /** Idle age after which the reaper drops a per-topic {@code changed} bucket. */
  static final long TOPIC_BUCKET_IDLE_REAP_NANOS = TimeUnit.SECONDS.toNanos(60);

  /**
   * Maximum time in milliseconds a single send may block before the {@link
   * ConcurrentWebSocketSessionDecorator} terminates the peer.
   */
  private static final int SEND_TIME_LIMIT_MS = 5_000;

  /** Maximum bytes buffered for a slow peer before the decorator terminates it. */
  private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

  /**
   * Session-attribute key ({@link Boolean}) marking a multiplexed {@code /ws/sync} socket. Set by
   * the {@code /ws/sync} handshake interceptor. Public so the interceptor can populate it.
   */
  public static final String ATTR_MULTIPLEXED = "livesync.multiplexed";

  /**
   * Session-attribute key ({@link String}) holding the OAuth2 access-token snapshot the {@code
   * /ws/sync} handshake interceptor captured on the servlet thread, replayed by {@link
   * LiveSyncSubscriptionAuthorizer} on subscribe-authorization probes. In-memory only, never
   * logged. Public so the interceptor can populate it.
   */
  public static final String ATTR_ACCESS_TOKEN = "livesync.accessToken";

  /**
   * Session-attribute key ({@link UUID}) holding the active-org-unit pin captured at handshake, so
   * a subscribe-authorization probe scopes exactly like the page's own reads. Public so the
   * interceptor can populate it.
   */
  public static final String ATTR_ACTIVE_ORG_UNIT = "livesync.activeOrgUnit";

  /**
   * Session-attribute key ({@code Set<String>}) for the caller's authorities captured at handshake,
   * used for locally role-gated subscribes.
   */
  public static final String ATTR_AUTHORITIES = "livesync.authorities";

  /**
   * Session-attribute key holding a multiplexed socket's set of subscribed canonical topics (a
   * {@code Set<String>}). Drives the per-session topic cap, idempotent re-subscribe and close-time
   * room cleanup.
   */
  private static final String ATTR_SUBSCRIPTIONS = "livesync.subscriptions";

  /**
   * Hard cap on distinct topics one multiplexed socket may subscribe to, so a crafted client cannot
   * fan one socket across unbounded rooms. A page subscribes to a handful of topics, so this sits
   * far above any legitimate use.
   */
  private static final int MAX_TOPICS_PER_SESSION = 16;

  /**
   * Maximum concurrent {@code /ws/sync} sockets per user; a refused socket is closed with {@link
   * #SOCKET_CAP_EXCEEDED}.
   */
  static final int MAX_SOCKETS_PER_USER = 20;

  /**
   * Close status {@code 4029} for a socket refused by {@link #MAX_SOCKETS_PER_USER}; the client
   * backs off its reconnect to the maximum interval.
   */
  static final CloseStatus SOCKET_CAP_EXCEEDED = new CloseStatus(4029, "socket cap exceeded");

  /**
   * Session-attribute key ({@link String}) holding the consent-page URL for a handshake the
   * Terms-of-Use gate marked instead of redirecting (REQ-SEC-028). Set by the {@code /ws/sync}
   * handshake interceptor; its presence means the socket must be refused at connect. Public so the
   * interceptor can populate it.
   */
  public static final String ATTR_TERMS_GATE = "livesync.termsGate";

  /**
   * Close status code {@code 4003} for a socket whose user has not accepted the Terms of Use
   * (REQ-SEC-028).
   *
   * <p>The close reason carries the consent-page URL; the client stops reconnecting and navigates
   * there.
   */
  static final int TERMS_CONSENT_REQUIRED_CODE = 4003;

  /** Maximum close-reason length in UTF-8 bytes, the most a close frame can carry. */
  private static final int MAX_CLOSE_REASON_BYTES = 123;

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
  private static final String ATTR_CHANGED_RATE = "livesync.changedRate";
  private static final String ATTR_PRESENCE_RATE = "livesync.presenceRate";
  private static final String ATTR_SUBSCRIBE_RATE = "livesync.subscribeRate";

  /**
   * Session-attribute key for the {@link ConcurrentWebSocketSessionDecorator} wrapping the raw
   * socket; the decorator is what {@link #sessionsByTopic} holds, resolved via {@link
   * #decorated(WebSocketSession)}.
   */
  private static final String ATTR_DECORATED = "livesync.decorated";

  private final LiveSyncPresenceService presenceService;
  private final LiveSyncFanout fanout;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService reaper;
  private final MeterRegistry meterRegistry;
  private final LiveSyncSubscriptionAuthorizer authorizer;
  private final Executor authExecutor;

  /**
   * Monotonic nanosecond clock for token-bucket refills and the idle-bucket reaper; {@link
   * System#nanoTime()} in production.
   */
  private final LongSupplier nanoClock;

  /**
   * Records how long each closing socket had been open (see {@link
   * MetricNames#LIVESYNC_SOCKET_LIFETIME}).
   */
  private final Timer socketLifetime;

  private final Map<String, Set<WebSocketSession>> sessionsByTopic = new ConcurrentHashMap<>();

  /** Every open socket's decorator, subscribed or not; the set the keepalive sweep pings. */
  private final Set<WebSocketSession> liveSessions = ConcurrentHashMap.newKeySet();

  /**
   * Live socket count per user (Keycloak {@code sub}) for the per-user socket cap; an entry is
   * removed at zero.
   */
  private final Map<String, Integer> socketsByUser = new ConcurrentHashMap<>();

  /**
   * Per-topic {@code changed}-frame token buckets keyed by canonical topic; idle buckets are
   * removed by {@link #reapIdleTopicBuckets(long)}.
   */
  private final Map<String, TopicRateState> changedRateByTopic = new ConcurrentHashMap<>();

  /**
   * Creates the handler, binds its live-sync gauges and socket-lifetime timer, and starts the
   * presence reaper and keepalive sweep.
   *
   * @param presenceService in-memory editor-presence store
   * @param fanout cross-replica fan-out seam (no-op when single-instance)
   * @param objectMapper Jackson mapper for the {@code {type, sections}} wire format
   * @param meterRegistry the registry the gauges and relay counters bind to
   * @param authorizer authorizes a {@code /ws/sync} subscribe to a topic
   * @param authExecutor runs subscribe-authorization probes off the container thread; a {@link
   *     RejectedExecutionException} fails the subscribe open
   */
  public LiveSyncWebSocketHandler(
      @NotNull LiveSyncPresenceService presenceService,
      @NotNull LiveSyncFanout fanout,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry,
      @NotNull LiveSyncSubscriptionAuthorizer authorizer,
      @NotNull Executor authExecutor) {
    this(
        presenceService,
        fanout,
        objectMapper,
        meterRegistry,
        authorizer,
        authExecutor,
        System::nanoTime);
  }

  /**
   * Test constructor that additionally injects the monotonic clock.
   *
   * @param presenceService in-memory editor-presence store
   * @param fanout cross-replica fan-out seam (no-op when single-instance)
   * @param objectMapper Jackson mapper for the {@code {type, sections}} wire format
   * @param meterRegistry the registry the gauges and relay counters bind to
   * @param authorizer authorizes a {@code /ws/sync} subscribe to a topic
   * @param authExecutor runs subscribe-authorization probes off the container thread; a {@link
   *     RejectedExecutionException} fails the subscribe open
   * @param nanoClock monotonic nanosecond source ({@link System#nanoTime()} in production)
   */
  LiveSyncWebSocketHandler(
      @NotNull LiveSyncPresenceService presenceService,
      @NotNull LiveSyncFanout fanout,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry,
      @NotNull LiveSyncSubscriptionAuthorizer authorizer,
      @NotNull Executor authExecutor,
      @NotNull LongSupplier nanoClock) {
    this.presenceService = presenceService;
    this.fanout = fanout;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.authorizer = authorizer;
    this.authExecutor = authExecutor;
    this.nanoClock = nanoClock;
    Gauge.builder(
            MetricNames.PRESENCE_WS_SESSIONS,
            sessionsByTopic,
            map -> map.values().stream().mapToInt(Set::size).sum())
        .description("Live live-sync WebSocket sessions summed across all topic rooms.")
        .register(meterRegistry);
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      Gauge.builder(MetricNames.LIVESYNC_SUBSCRIPTIONS, this, h -> h.subscriptionCount(topicClass))
          .tag(MetricNames.TAG_TOPIC_CLASS, topicClass.metricLabel())
          .description("Live live-sync subscriptions for this topic class.")
          .register(meterRegistry);
      Gauge.builder(MetricNames.LIVESYNC_PEER_ROOMS, this, h -> h.peerRoomCount(topicClass))
          .tag(MetricNames.TAG_TOPIC_CLASS, topicClass.metricLabel())
          .description("Live rooms of this topic class holding two or more subscribers.")
          .register(meterRegistry);
    }
    this.socketLifetime =
        Timer.builder(MetricNames.LIVESYNC_SOCKET_LIFETIME)
            .description("How long a live-sync WebSocket session stayed open before closing.")
            .register(meterRegistry);
    this.reaper =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "livesync-reaper");
              t.setDaemon(true);
              return t;
            });
    this.reaper.scheduleAtFixedRate(
        this::tickReaper,
        REAPER_INTERVAL.toSeconds(),
        REAPER_INTERVAL.toSeconds(),
        TimeUnit.SECONDS);
    this.reaper.scheduleAtFixedRate(
        this::tickKeepalive,
        KEEPALIVE_INTERVAL.toSeconds(),
        KEEPALIVE_INTERVAL.toSeconds(),
        TimeUnit.SECONDS);
  }

  /**
   * Shuts the shared scheduler thread down cleanly on application shutdown, stopping both the
   * presence reaper and the keepalive sweep.
   */
  @PreDestroy
  public void shutdown() {
    reaper.shutdownNow();
  }

  /**
   * Counts live sessions in rooms of a given topic class (backs the per-class subscriptions gauge).
   *
   * @param topicClass the class to count
   * @return the number of live sockets across that class's rooms
   */
  private int subscriptionCount(@NotNull LiveSyncTopicClass topicClass) {
    int total = 0;
    for (Map.Entry<String, Set<WebSocketSession>> room : sessionsByTopic.entrySet()) {
      LiveSyncTopic topic = LiveSyncTopic.parse(room.getKey());
      if (topic != null && topic.topicClass() == topicClass) {
        total += room.getValue().size();
      }
    }
    return total;
  }

  /**
   * Counts the rooms of a topic class holding two or more sockets, for the {@code
   * basetool_livesync_peer_rooms} gauge.
   *
   * @param topicClass the class to count
   * @return the number of rooms of that class with at least two live sockets
   */
  private int peerRoomCount(@NotNull LiveSyncTopicClass topicClass) {
    int rooms = 0;
    for (Map.Entry<String, Set<WebSocketSession>> room : sessionsByTopic.entrySet()) {
      LiveSyncTopic topic = LiveSyncTopic.parse(room.getKey());
      if (topic != null && topic.topicClass() == topicClass && room.getValue().size() >= 2) {
        rooms++;
      }
    }
    return rooms;
  }

  /**
   * Registers a new {@code /ws/sync} socket: resolves the principal, enforces {@link
   * #MAX_SOCKETS_PER_USER}, wraps it in its backpressure decorator and seeds an empty subscription
   * set.
   *
   * <p>Refuses a socket without accepted Terms of Use ({@link #ATTR_TERMS_GATE}, REQ-SEC-028),
   * without a resolvable principal, or over the per-user cap.
   *
   * @param session the freshly opened session
   */
  @Override
  public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
    String consentUrl = (String) session.getAttributes().get(ATTR_TERMS_GATE);
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
      log.debug("Live-sync /ws/sync socket refused (per-user cap {})", MAX_SOCKETS_PER_USER);
      socketRejectedCounter(MetricNames.SOCKET_REJECTED_USER_CAP).increment();
      session.close(SOCKET_CAP_EXCEEDED);
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
   * Builds the {@link #TERMS_CONSENT_REQUIRED_CODE} close status with the consent-page URL as
   * reason, omitting the URL when it exceeds {@link #MAX_CLOSE_REASON_BYTES}.
   *
   * @param consentUrl the context-relative consent-page URL
   * @return the close status to refuse the socket with
   */
  @NotNull
  private static CloseStatus termsConsentRequired(@NotNull String consentUrl) {
    boolean fits = consentUrl.getBytes(StandardCharsets.UTF_8).length <= MAX_CLOSE_REASON_BYTES;
    return new CloseStatus(TERMS_CONSENT_REQUIRED_CODE, fits ? consentUrl : null);
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
   * Adds a socket's decorator to a topic's room atomically, so a concurrent close cannot orphan it.
   *
   * @param decorated the decorator to register (never the raw session)
   * @param topic the room to join
   */
  private void joinRoom(@NotNull WebSocketSession decorated, @NotNull LiveSyncTopic topic) {
    sessionsByTopic.compute(
        topic.canonical(),
        (ignored, set) -> {
          Set<WebSocketSession> mates = (set != null) ? set : ConcurrentHashMap.newKeySet();
          mates.add(decorated);
          return mates;
        });
  }

  /**
   * Dispatches one client frame: {@code subscribe}, {@code changed} or a presence frame; unknown
   * types are ignored.
   *
   * @param session the session that produced the message
   * @param message the text payload
   */
  @Override
  protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message)
      throws Exception {
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    if (userId == null) {
      return;
    }
    JsonNode node;
    try {
      node = objectMapper.readTree(message.getPayload());
    } catch (JacksonException e) {
      log.debug("Discarding malformed live-sync message", e);
      return;
    }
    String type = textValue(node, "type");
    if (type == null) {
      return;
    }
    switch (type) {
      case "subscribe" -> handleSubscribe(session, node);
      case "changed" -> handleMultiplexedChanged(session, node);
      case "focus", "blur", "heartbeat" -> handleMultiplexedPresence(session, node, type, userId);
      default -> {}
    }
  }

  /**
   * Cleans up a closed socket: leaves every room and, in presence-enabled rooms where the user has
   * no other live session, drops their presence and broadcasts the new snapshot.
   *
   * @param session the closing session
   * @param status close reason (logged for diagnostics)
   */
  @Override
  public void afterConnectionClosed(
      @NotNull WebSocketSession session, @NotNull CloseStatus status) {
    recordSocketLifetime(session);
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    if (userId != null && Boolean.TRUE.equals(session.getAttributes().get(ATTR_USER_COUNTED))) {
      releaseUserSocket(userId);
    }
    WebSocketSession decorated = decorated(session);
    liveSessions.remove(decorated);
    Set<String> subs = subscriptions(session);
    if (subs == null) {
      return;
    }
    for (String canonical : List.copyOf(subs)) {
      LiveSyncTopic topic = LiveSyncTopic.parse(canonical);
      if (topic == null) {
        continue;
      }
      Set<WebSocketSession> mates =
          sessionsByTopic.computeIfPresent(
              canonical,
              (ignored, set) -> {
                set.remove(decorated);
                return set.isEmpty() ? null : set;
              });
      if (userId == null || !topic.topicClass().presenceEnabled()) {
        continue;
      }
      boolean hasOtherSession =
          mates != null
              && mates.stream().anyMatch(s -> userId.equals(s.getAttributes().get(ATTR_USER_ID)));
      if (!hasOtherSession) {
        List<String> cleared = presenceService.clearAll(canonical, userId);
        if (!cleared.isEmpty()) {
          broadcastLocalPresenceChange(topic);
        }
      }
    }
  }

  /**
   * Handles a {@code subscribe} frame: validates the topic, applies the rate limit, topic cap and
   * idempotency, then authorizes asynchronously on {@link #authExecutor}; a saturated executor
   * fails the subscribe open.
   *
   * @param session the subscribing session
   * @param node the parsed {@code subscribe} frame
   */
  private void handleSubscribe(@NotNull WebSocketSession session, @NotNull JsonNode node) {
    String rawTopic = textValue(node, "topic");
    LiveSyncTopic topic = LiveSyncTopic.parse(rawTopic);
    if (topic == null) {
      log.debug(
          "Live-sync subscribe to unknown topic '{}' refused",
          LogSafe.text(rawTopic, MAX_LOGGED_TOPIC_LENGTH));
      meterRegistry.counter(MetricNames.LIVESYNC_INVALID_TOPIC).increment();
      sendControlFrame(session, "denied", rawTopic);
      return;
    }
    Set<String> subs = subscriptions(session);
    if (subs == null) {
      return;
    }
    if (!allowSubscribeFrame(session)) {
      droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      log.debug(
          "Live-sync subscribe to topic {} dropped (per-session throttle)", topic.canonical());
      return;
    }
    if (subs.contains(topic.canonical())) {
      sendControlFrame(session, "subscribed", topic.canonical());
      return;
    }
    if (subs.size() >= MAX_TOPICS_PER_SESSION) {
      droppedCounter(topic, MetricNames.DROPPED_TOPIC_CAP).increment();
      sendControlFrame(session, "denied", topic.canonical());
      return;
    }
    subs.add(topic.canonical());
    String token = (String) session.getAttributes().get(ATTR_ACCESS_TOKEN);
    UUID pin = session.getAttributes().get(ATTR_ACTIVE_ORG_UNIT) instanceof UUID u ? u : null;
    Set<String> authorities = capturedAuthorities(session);
    try {
      authExecutor.execute(() -> authorizeAndRegister(session, topic, token, pin, authorities));
    } catch (RejectedExecutionException e) {
      LiveSyncSubscriptionAuthorizer.Decision verdict =
          LiveSyncSubscriptionAuthorizer.failOpen(topic);
      droppedCounter(topic, MetricNames.DROPPED_AUTHORIZE_SATURATED).increment();
      log.warn(
          "Live-sync subscribe authorization for topic {} was not scheduled (auth executor"
              + " saturated); resolved as {}",
          topic.canonical(),
          verdict);
      completeSubscribe(session, topic, verdict);
    }
  }

  /**
   * Runs the subscribe-authorization probe and applies its verdict; an unexpected exception
   * resolves via {@link LiveSyncSubscriptionAuthorizer#failOpen(LiveSyncTopic)}, and a fail-closed
   * indeterminate verdict is logged at WARN.
   *
   * @param session the subscribing session
   * @param topic the topic being authorized
   * @param token the captured OAuth2 access token (may be {@code null})
   * @param pin the captured active-org-unit pin (may be {@code null})
   * @param authorities the captured authorities for a local role check (may be {@code null})
   */
  private void authorizeAndRegister(
      @NotNull WebSocketSession session,
      @NotNull LiveSyncTopic topic,
      String token,
      UUID pin,
      Set<String> authorities) {
    LiveSyncSubscriptionAuthorizer.Decision decision;
    try {
      decision = authorizer.authorize(topic, token, pin, authorities);
      if (decision == LiveSyncSubscriptionAuthorizer.Decision.DENY_INDETERMINATE) {
        log.warn(
            "Live-sync subscribe to topic {} failed closed on an indeterminate authorization"
                + " outcome; this tab gets no live updates for it until it reconnects",
            topic.canonical());
      }
    } catch (RuntimeException e) {
      decision = LiveSyncSubscriptionAuthorizer.failOpen(topic);
      if (decision == LiveSyncSubscriptionAuthorizer.Decision.DENY_INDETERMINATE) {
        log.warn(
            "Live-sync subscribe to topic {} failed closed: the authorization probe threw",
            topic.canonical(),
            e);
      } else {
        log.debug(
            "Live-sync subscribe authorization threw for {} (failing open by class)",
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
    Set<String> subs = subscriptions(session);
    if (decision.denied()) {
      if (subs != null) {
        subs.remove(topic.canonical());
      }
      String reason = denyReason(decision);
      sendControlFrame(session, "denied", topic.canonical(), reason);
      subscribeCounter(topic, MetricNames.OUTCOME_DENIED, reason).increment();
      return;
    }
    WebSocketSession decorated = decorated(session);
    if (!decorated.isOpen() || subs == null || !subs.contains(topic.canonical())) {
      if (subs != null) {
        subs.remove(topic.canonical());
      }
      return;
    }
    joinRoom(decorated, topic);
    if (!decorated.isOpen()) {
      leaveRoom(decorated, topic);
      return;
    }
    sendControlFrame(session, "subscribed", topic.canonical());
    if (topic.topicClass().presenceEnabled()) {
      sendSnapshot(decorated, topic);
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
    return decision == LiveSyncSubscriptionAuthorizer.Decision.DENY_INDETERMINATE
        ? MetricNames.SUBSCRIBE_DENY_INDETERMINATE
        : MetricNames.SUBSCRIBE_DENY_AUTHZ;
  }

  /**
   * Handles a {@code changed} frame: resolves its topic, sanitises the sections, relays locally
   * (excluding the origin) and hands it to the cross-replica fan-out; no subscription is required.
   *
   * @param session the publishing session
   * @param node the parsed {@code changed} frame
   */
  private void handleMultiplexedChanged(@NotNull WebSocketSession session, @NotNull JsonNode node) {
    String rawTopic = textValue(node, "topic");
    LiveSyncTopic topic = LiveSyncTopic.parse(rawTopic);
    if (topic == null) {
      log.debug(
          "Discarding live-sync changed frame for unknown topic '{}'",
          LogSafe.text(rawTopic, MAX_LOGGED_TOPIC_LENGTH));
      return;
    }
    if (!allowChangedFrame(session)) {
      droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      return;
    }
    FilteredSections filtered = sanitiseSections(node.get("sections"), topic.topicClass());
    reportFilteredSections(topic, filtered, "client");
    if (filtered.accepted().isEmpty()) {
      return;
    }
    relayChangedThrottled(topic, filtered.accepted(), decorated(session));
  }

  /**
   * Handles a presence frame for a subscribed, presence-enabled room: {@code focus}/{@code
   * heartbeat} touch the editor entry, {@code blur} clears it, and any change broadcasts a
   * snapshot.
   *
   * @param session the session
   * @param node the parsed presence frame
   * @param type the frame type ({@code focus}/{@code blur}/{@code heartbeat})
   * @param userId the socket owner's stable user id
   */
  private void handleMultiplexedPresence(
      @NotNull WebSocketSession session,
      @NotNull JsonNode node,
      @NotNull String type,
      @NotNull String userId) {
    LiveSyncTopic topic = LiveSyncTopic.parse(textValue(node, "topic"));
    if (topic == null || !topic.topicClass().presenceEnabled()) {
      return;
    }
    Set<String> subs = subscriptions(session);
    if (subs == null || !subs.contains(topic.canonical())) {
      return;
    }
    String sectionKey = textValue(node, "sectionKey");
    if (sectionKey == null
        || sectionKey.isBlank()
        || sectionKey.length() > MAX_SECTION_KEY_LENGTH) {
      return;
    }
    if (!allowPresenceFrame(session)) {
      droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      return;
    }
    boolean mutated;
    switch (type) {
      case "focus", "heartbeat" -> {
        String displayName = (String) session.getAttributes().get(ATTR_DISPLAY_NAME);
        mutated = presenceService.touch(topic.canonical(), sectionKey, userId, displayName);
      }
      case "blur" -> mutated = presenceService.clear(topic.canonical(), sectionKey, userId);
      default -> {
        return;
      }
    }
    if (mutated || "blur".equals(type) || "focus".equals(type)) {
      broadcastLocalPresenceChange(topic);
    }
  }

  /**
   * Removes a socket's decorator from a topic's room under the entry's bin lock, unmapping the
   * entry when it becomes empty. The inverse of {@link #joinRoom}.
   *
   * @param decorated the decorator to deregister
   * @param topic the room to leave
   */
  private void leaveRoom(@NotNull WebSocketSession decorated, @NotNull LiveSyncTopic topic) {
    sessionsByTopic.computeIfPresent(
        topic.canonical(),
        (ignored, set) -> {
          set.remove(decorated);
          return set.isEmpty() ? null : set;
        });
  }

  /**
   * Sends a {@code subscribed} or {@code denied} control frame without a {@code reason}, which the
   * client treats as terminal.
   *
   * @param session the target session (its decorator is written to)
   * @param type the control-frame type ({@code subscribed} / {@code denied})
   * @param topicString the topic echoed as-is; may be unparseable for a denied subscribe
   */
  private void sendControlFrame(
      @NotNull WebSocketSession session, @NotNull String type, String topicString) {
    sendControlFrame(session, type, topicString, null);
  }

  /**
   * Sends a control frame, tolerating a closed peer; a {@code denied} frame carries its bounded
   * reason from {@link #denyReason(LiveSyncSubscriptionAuthorizer.Decision)} when known.
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
    WebSocketSession target = decorated(session);
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
   * Resolves a multiplexed socket's subscription set (its subscribed canonical topics), or {@code
   * null} on a socket without one (a refused socket, or before the set is seeded at connect).
   *
   * @param session the session
   * @return the subscription set, or {@code null}
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private static Set<String> subscriptions(@NotNull WebSocketSession session) {
    Object value = session.getAttributes().get(ATTR_SUBSCRIPTIONS);
    return value instanceof Set ? (Set<String>) value : null;
  }

  /**
   * Resolves the authorities captured at handshake ({@link #ATTR_AUTHORITIES}).
   *
   * @param session the session
   * @return the captured authority names, or {@code null} when none were captured
   */
  @Nullable
  private static Set<String> capturedAuthorities(@NotNull WebSocketSession session) {
    Object value = session.getAttributes().get(ATTR_AUTHORITIES);
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

  /**
   * Relays a {@code changed} signal from a peer replica to this instance's room without
   * re-publishing it; the sections are re-checked against the class whitelist.
   *
   * @param canonicalTopic the canonical topic string
   * @param sections the section keys from the peer replica
   */
  public void deliverFromFanout(@NotNull String canonicalTopic, @NotNull List<String> sections) {
    LiveSyncTopic topic = LiveSyncTopic.parse(canonicalTopic);
    if (topic == null) {
      return;
    }
    FilteredSections filtered = retainAllowed(sections, topic.topicClass());
    reportFilteredSections(topic, filtered, "fan-out");
    if (filtered.accepted().isEmpty()) {
      return;
    }
    relayLocal(topic, filtered.accepted(), null);
  }

  /**
   * Publishes a server-originated {@code changed} signal (REQ-FE-015) to the local room and the
   * fan-out, e.g. for an anonymous guest order create; sections are whitelist-filtered.
   *
   * @param canonicalTopic the canonical topic string (unknown topics are ignored)
   * @param sections the section keys to relay
   */
  public void publishFromServer(@NotNull String canonicalTopic, @NotNull List<String> sections) {
    LiveSyncTopic topic = LiveSyncTopic.parse(canonicalTopic);
    if (topic == null) {
      return;
    }
    FilteredSections filtered = retainAllowed(sections, topic.topicClass());
    reportFilteredSections(topic, filtered, "server");
    if (filtered.accepted().isEmpty()) {
      return;
    }
    relayLocal(topic, filtered.accepted(), null);
    fanout.publish(topic.canonical(), filtered.accepted());
  }

  /**
   * Keeps only whitelisted section keys, de-duplicated and capped; the {@link List} counterpart of
   * {@link #sanitiseSections(JsonNode, LiveSyncTopicClass)}.
   *
   * @param sections the raw section keys
   * @param topicClass the class whose whitelist applies
   * @return the accepted keys plus the rejection evidence
   */
  @NotNull
  private static FilteredSections retainAllowed(
      @NotNull List<String> sections, @NotNull LiveSyncTopicClass topicClass) {
    Set<String> allowed = topicClass.allowedSections();
    List<String> result = new ArrayList<>();
    int rejected = 0;
    String firstRejected = null;
    for (String section : sections) {
      if (result.size() >= MAX_CHANGED_SECTIONS) {
        break;
      }
      if (section == null) {
        continue;
      }
      if (!allowed.contains(section)) {
        rejected++;
        if (firstRejected == null) {
          firstRejected = section;
        }
        continue;
      }
      if (!result.contains(section)) {
        result.add(section);
      }
    }
    return new FilteredSections(result, rejected, firstRejected);
  }

  /**
   * Counts, once per frame, the section keys a {@code changed} frame lost to the whitelist and logs
   * one DEBUG line with the first rejected key via {@link LogSafe} (REQ-FE-010).
   *
   * @param topic the targeted topic (its class tags the drop counter)
   * @param filtered the filter outcome; zero rejections report nothing
   * @param source the publish path for the log line: {@code client}, {@code server} or {@code
   *     fan-out}
   */
  private void reportFilteredSections(
      @NotNull LiveSyncTopic topic, @NotNull FilteredSections filtered, @NotNull String source) {
    if (filtered.rejected() == 0) {
      return;
    }
    droppedCounter(topic, MetricNames.DROPPED_SECTION_FILTERED).increment();
    log.debug(
        "Live-sync {} changed frame for topic {} lost {} section key(s) to the {} whitelist"
            + " (first: '{}')",
        source,
        topic.canonical(),
        filtered.rejected(),
        topic.topicClass().metricLabel(),
        LogSafe.text(filtered.firstRejectedKey(), MAX_SECTION_KEY_LENGTH));
  }

  /**
   * Outcome of filtering one frame's section keys against a class whitelist; duplicates and
   * non-string entries do not count as rejected.
   *
   * @param accepted the accepted, de-duplicated, {@link #MAX_CHANGED_SECTIONS}-capped keys
   * @param rejected how many keys the whitelist refused
   * @param firstRejectedKey the first refused key, or {@code null} when none was refused
   */
  private record FilteredSections(List<String> accepted, int rejected, String firstRejectedKey) {}

  /**
   * Reaper tick: drops expired local presence and broadcasts it, drops remote partitions older than
   * {@link LiveSyncPresenceService#REMOTE_PARTITION_TTL}, and re-gossips every tracked topic's
   * presence (ADR-0126). Exceptions are logged and swallowed.
   */
  void tickReaper() {
    try {
      reapIdleTopicBuckets(nanoClock.getAsLong());
      Set<String> mirrored = broadcastLocallyExpiredPresence();
      broadcastRemotelyExpiredPresence();
      gossipTrackedPresence(mirrored);
    } catch (RuntimeException e) {
      log.warn("Live-sync reaper tick failed", e);
    }
  }

  /**
   * Records a closing socket's lifetime, if it ever got one — a socket refused at connect (consent
   * gate, missing principal, per-user cap) never carries the open stamp and is skipped, so a
   * refusal cannot masquerade as a zero-second connection and pull the distribution down.
   *
   * @param session the closing raw session
   */
  private void recordSocketLifetime(@NotNull WebSocketSession session) {
    if (session.getAttributes().get(ATTR_OPENED_NANOS) instanceof Long openedNanos) {
      socketLifetime.record(nanoClock.getAsLong() - openedNanos, TimeUnit.NANOSECONDS);
    }
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
   * Reaps this instance's expired presence entries and, for every room that lost one, broadcasts
   * the fresh snapshot locally and gossips it to peers.
   *
   * @return the canonical topics already gossiped by this step, so {@link
   *     #gossipTrackedPresence(Set)} does not publish them a second time in the same tick
   */
  @NotNull
  private Set<String> broadcastLocallyExpiredPresence() {
    List<LiveSyncPresenceService.TopicSectionRef> affected =
        presenceService.reapExpired(Instant.now());
    Set<String> uniqueTopics = new HashSet<>();
    for (LiveSyncPresenceService.TopicSectionRef ref : affected) {
      uniqueTopics.add(ref.topic());
    }
    for (String canonical : uniqueTopics) {
      LiveSyncTopic topic = LiveSyncTopic.parse(canonical);
      if (topic != null) {
        broadcastLocalPresenceChange(topic);
      }
    }
    return uniqueTopics;
  }

  /**
   * Drops peer presence partitions not re-gossiped within {@link
   * LiveSyncPresenceService#REMOTE_PARTITION_TTL} and broadcasts the result locally without
   * publishing.
   */
  private void broadcastRemotelyExpiredPresence() {
    for (String canonical : presenceService.reapExpiredRemote(Instant.now())) {
      LiveSyncTopic topic = LiveSyncTopic.parse(canonical);
      if (topic != null) {
        broadcastSnapshot(topic);
      }
    }
  }

  /**
   * Re-gossips this instance's presence snapshot for every topic it still tracks, skipping the ones
   * already published earlier in the same tick.
   *
   * @param alreadyMirrored canonical topics published by {@link #broadcastLocallyExpiredPresence()}
   */
  private void gossipTrackedPresence(@NotNull Set<String> alreadyMirrored) {
    Instant now = Instant.now();
    for (String canonical : presenceService.trackedTopics()) {
      if (alreadyMirrored.contains(canonical)) {
        continue;
      }
      fanout.publishPresence(canonical, presenceService.localSnapshot(canonical, now));
    }
  }

  /**
   * Sanitises an inbound {@code sections} array: drops non-strings and keys outside {@link
   * LiveSyncTopicClass#allowedSections()}, collapses duplicates and caps at {@link
   * #MAX_CHANGED_SECTIONS}.
   *
   * @param sectionsNode the raw {@code sections} node (may be {@code null} or not an array)
   * @param topicClass the class whose whitelist applies
   * @return the accepted keys plus the rejection evidence (never {@code null})
   */
  @NotNull
  private static FilteredSections sanitiseSections(
      JsonNode sectionsNode, @NotNull LiveSyncTopicClass topicClass) {
    List<String> sections = new ArrayList<>();
    if (sectionsNode == null || !sectionsNode.isArray()) {
      return new FilteredSections(sections, 0, null);
    }
    Set<String> allowed = topicClass.allowedSections();
    int rejected = 0;
    String firstRejected = null;
    for (JsonNode element : sectionsNode) {
      if (sections.size() >= MAX_CHANGED_SECTIONS) {
        break;
      }
      if (element == null || !element.isString()) {
        continue;
      }
      String key = element.asString();
      if (!allowed.contains(key)) {
        rejected++;
        if (firstRejected == null) {
          firstRejected = key;
        }
        continue;
      }
      if (!sections.contains(key)) {
        sections.add(key);
      }
    }
    return new FilteredSections(sections, rejected, firstRejected);
  }

  /**
   * Sends a sanitised {@code changed} frame to every socket in the topic's room except {@code
   * origin}.
   *
   * @param topic the topic whose room receives the frame
   * @param sections the sanitised section keys
   * @param origin the local originating session to exclude, or {@code null}
   */
  private void relayLocal(
      @NotNull LiveSyncTopic topic, @NotNull List<String> sections, WebSocketSession origin) {
    Set<WebSocketSession> mates = sessionsByTopic.get(topic.canonical());
    if (mates == null || mates.isEmpty()) {
      return;
    }
    String payload;
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("type", "changed");
      root.put("topic", topic.canonical());
      ArrayNode sectionsArray = root.putArray("sections");
      for (String key : sections) {
        sectionsArray.add(key);
      }
      payload = objectMapper.writeValueAsString(root);
    } catch (JacksonException e) {
      log.warn("Failed to serialise change relay for topic {}", topic.canonical(), e);
      return;
    }
    TextMessage message = new TextMessage(payload);
    Counter frames = frameCounter(topic, MetricNames.FRAME_CHANGED);
    for (WebSocketSession session : List.copyOf(mates)) {
      if (session == origin) {
        continue;
      }
      if (sendSafe(session, message, topic)) {
        frames.increment();
      }
    }
  }

  /**
   * Applies a peer replica's presence snapshot (ADR-0126) and re-broadcasts locally when the merged
   * view changed, without re-publishing; only presence-enabled topics and well-formed keys are
   * accepted.
   *
   * @param canonicalTopic the canonical topic string (unknown or non-presence topics are ignored)
   * @param originId the publishing replica's instance id, which keys its partition
   * @param sections that replica's complete editor set per section; an empty map drops its
   *     partition
   */
  public void deliverPresenceFromFanout(
      @NotNull String canonicalTopic,
      @NotNull String originId,
      @NotNull Map<String, List<LiveSyncPresenceService.PresenceEditor>> sections) {
    LiveSyncTopic topic = LiveSyncTopic.parse(canonicalTopic);
    if (topic == null || !topic.topicClass().presenceEnabled()) {
      return;
    }
    Map<String, List<LiveSyncPresenceService.PresenceEditor>> accepted = new LinkedHashMap<>();
    for (Map.Entry<String, List<LiveSyncPresenceService.PresenceEditor>> entry :
        sections.entrySet()) {
      String sectionKey = entry.getKey();
      if (sectionKey == null
          || sectionKey.isBlank()
          || sectionKey.length() > MAX_SECTION_KEY_LENGTH
          || entry.getValue().isEmpty()) {
        continue;
      }
      accepted.put(sectionKey, entry.getValue());
    }
    if (presenceService.applyRemote(topic.canonical(), originId, accepted, Instant.now())) {
      broadcastSnapshot(topic);
    }
  }

  /**
   * Broadcasts a topic's merged presence snapshot locally and gossips this instance's part to peer
   * replicas (ADR-0126); called on every local presence change.
   *
   * @param topic the topic whose presence changed locally
   */
  private void broadcastLocalPresenceChange(@NotNull LiveSyncTopic topic) {
    fanout.publishPresence(
        topic.canonical(), presenceService.localSnapshot(topic.canonical(), Instant.now()));
    broadcastSnapshot(topic);
  }

  private void broadcastSnapshot(@NotNull LiveSyncTopic topic) {
    Set<WebSocketSession> mates = sessionsByTopic.get(topic.canonical());
    if (mates == null || mates.isEmpty()) {
      return;
    }
    String payload;
    try {
      payload = objectMapper.writeValueAsString(buildSnapshot(topic));
    } catch (JacksonException e) {
      log.warn("Failed to serialise presence snapshot for topic {}", topic.canonical(), e);
      return;
    }
    TextMessage message = new TextMessage(payload);
    Counter frames = frameCounter(topic, MetricNames.FRAME_SNAPSHOT);
    for (WebSocketSession session : List.copyOf(mates)) {
      if (sendSafe(session, message, topic)) {
        frames.increment();
      }
    }
  }

  /**
   * Per-session rate limit on inbound {@code changed} frames ({@link #CHANGED_BURST} refilled at
   * {@link #CHANGED_REFILL_PER_SEC}/s).
   *
   * @param session the session that sent the frame
   * @return {@code true} to relay the frame, {@code false} to drop it as throttled
   */
  private boolean allowChangedFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_CHANGED_RATE, CHANGED_BURST, CHANGED_REFILL_PER_SEC);
  }

  /**
   * Per-session rate limit on inbound presence frames ({@code focus} / {@code heartbeat} / {@code
   * blur}).
   *
   * @param session the session that sent the frame
   * @return {@code true} to process the frame, {@code false} to drop it as throttled
   */
  private boolean allowPresenceFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_PRESENCE_RATE, PRESENCE_BURST, PRESENCE_REFILL_PER_SEC);
  }

  /**
   * Per-session rate limit on inbound {@code subscribe} frames, bounding authorization probes to
   * {@link #authExecutor}.
   *
   * @param session the session that sent the frame
   * @return {@code true} to process the subscribe, {@code false} to drop it as throttled
   */
  private boolean allowSubscribeFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_SUBSCRIBE_RATE, SUBSCRIBE_BURST, SUBSCRIBE_REFILL_PER_SEC);
  }

  /**
   * Shared per-session token bucket, stored unsynchronised in the session attributes since one
   * session's frames arrive serially.
   *
   * @param session the session that sent the frame
   * @param attrKey the session-attribute key holding this bucket's state
   * @param burst the bucket capacity
   * @param refillPerSec the token refill rate per second
   * @return {@code true} to process the frame, {@code false} to drop it as throttled
   */
  private boolean allowFrame(
      @NotNull WebSocketSession session, @NotNull String attrKey, int burst, double refillPerSec) {
    long now = nanoClock.getAsLong();
    ChangedRateState state;
    if (session.getAttributes().get(attrKey) instanceof ChangedRateState existing) {
      state = existing;
    } else {
      state = new ChangedRateState(burst, now);
      session.getAttributes().put(attrKey, state);
    }
    double elapsedSeconds = (now - state.lastRefillNanos) / 1_000_000_000.0;
    state.tokens = Math.min(burst, state.tokens + elapsedSeconds * refillPerSec);
    state.lastRefillNanos = now;
    if (state.tokens >= 1.0) {
      state.tokens -= 1.0;
      return true;
    }
    return false;
  }

  /**
   * Relays a sanitised {@code changed} frame through the per-topic throttle: on success to the
   * local room (excluding {@code origin}) and the fan-out, otherwise counts a {@link
   * MetricNames#DROPPED_TOPIC_THROTTLED} drop.
   *
   * @param topic the topic being published to
   * @param sections the sanitised, non-empty section keys
   * @param origin the local originating session to exclude from the relay
   */
  private void relayChangedThrottled(
      @NotNull LiveSyncTopic topic,
      @NotNull List<String> sections,
      @NotNull WebSocketSession origin) {
    if (!allowTopicChanged(topic)) {
      droppedCounter(topic, MetricNames.DROPPED_TOPIC_THROTTLED).increment();
      return;
    }
    relayLocal(topic, sections, origin);
    fanout.publish(topic.canonical(), sections);
  }

  /**
   * Per-topic rate limit on accepted {@code changed} frames; the shared bucket is updated under its
   * own monitor.
   *
   * @param topic the topic the frame targets
   * @return {@code true} to relay the frame, {@code false} to drop it as throttled
   */
  private boolean allowTopicChanged(@NotNull LiveSyncTopic topic) {
    long now = nanoClock.getAsLong();
    TopicRateState state =
        changedRateByTopic.computeIfAbsent(
            topic.canonical(), ignored -> new TopicRateState(TOPIC_CHANGED_BURST, now));
    synchronized (state) {
      double elapsedSeconds = (now - state.lastRefillNanos) / 1_000_000_000.0;
      state.tokens =
          Math.min(
              TOPIC_CHANGED_BURST, state.tokens + elapsedSeconds * TOPIC_CHANGED_REFILL_PER_SEC);
      state.lastRefillNanos = now;
      if (state.tokens >= 1.0) {
        state.tokens -= 1.0;
        return true;
      }
      return false;
    }
  }

  /**
   * Drops per-topic {@code changed} buckets idle for at least {@link
   * #TOPIC_BUCKET_IDLE_REAP_NANOS}.
   *
   * @param nowNanos the current {@link System#nanoTime()} reading
   */
  void reapIdleTopicBuckets(long nowNanos) {
    long cutoff = nowNanos - TOPIC_BUCKET_IDLE_REAP_NANOS;
    changedRateByTopic
        .entrySet()
        .removeIf(
            entry -> {
              synchronized (entry.getValue()) {
                return entry.getValue().lastRefillNanos <= cutoff;
              }
            });
  }

  /**
   * The number of live per-topic {@code changed} buckets (test seam for the reaper: proves an idle
   * bucket is dropped).
   *
   * @return the size of {@link #changedRateByTopic}
   */
  int topicBucketCount() {
    return changedRateByTopic.size();
  }

  /**
   * Atomically claims a per-user socket slot, refusing when the user would exceed {@link
   * #MAX_SOCKETS_PER_USER}.
   *
   * @param userId the connecting user's stable id (Keycloak {@code sub})
   * @return {@code true} if a slot was claimed, {@code false} if the user is at the cap
   */
  private boolean tryAcquireUserSocket(@NotNull String userId) {
    int count = socketsByUser.merge(userId, 1, Integer::sum);
    if (count > MAX_SOCKETS_PER_USER) {
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
    socketsByUser.compute(
        userId, (ignored, count) -> (count == null || count <= 1) ? null : count - 1);
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

  private void sendSnapshot(@NotNull WebSocketSession session, @NotNull LiveSyncTopic topic) {
    try {
      String payload = objectMapper.writeValueAsString(buildSnapshot(topic));
      if (sendSafe(session, new TextMessage(payload), topic)) {
        frameCounter(topic, MetricNames.FRAME_SNAPSHOT).increment();
      }
    } catch (JacksonException e) {
      log.warn("Failed to serialise initial presence snapshot for topic {}", topic.canonical(), e);
    }
  }

  private ObjectNode buildSnapshot(@NotNull LiveSyncTopic topic) {
    Map<String, List<LiveSyncPresenceService.Entry>> snapshot =
        presenceService.snapshot(topic.canonical(), Instant.now());
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "presence");
    root.put("topic", topic.canonical());
    ObjectNode sections = root.putObject("sections");
    for (Map.Entry<String, List<LiveSyncPresenceService.Entry>> e : snapshot.entrySet()) {
      ArrayNode editors = sections.putArray(e.getKey());
      for (LiveSyncPresenceService.Entry editor : e.getValue()) {
        ObjectNode editorNode = editors.addObject();
        editorNode.put("userId", editor.userId());
        editorNode.put("displayName", editor.displayName());
      }
    }
    return root;
  }

  /**
   * Writes one frame to a session; a failed send is counted as a {@code send_failed} relay drop.
   *
   * @param session the target session
   * @param message the frame to write
   * @param topic the topic whose class tags the drop metric
   * @return {@code true} if the frame was written, {@code false} if the session was closed or the
   *     write failed
   */
  private boolean sendSafe(
      @NotNull WebSocketSession session,
      @NotNull TextMessage message,
      @NotNull LiveSyncTopic topic) {
    if (!session.isOpen()) {
      return false;
    }
    try {
      session.sendMessage(message);
      return true;
    } catch (IOException | IllegalStateException | SessionLimitExceededException e) {
      log.debug("Drop live-sync frame to closed/broken/overflowed session {}", session.getId(), e);
      droppedCounter(topic, MetricNames.DROPPED_SEND_FAILED).increment();
      return false;
    }
  }

  private Counter frameCounter(@NotNull LiveSyncTopic topic, @NotNull String type) {
    return meterRegistry.counter(
        MetricNames.PRESENCE_RELAY_FRAMES,
        MetricNames.TAG_TYPE,
        type,
        MetricNames.TAG_TOPIC_CLASS,
        topic.topicClass().metricLabel());
  }

  private Counter droppedCounter(@NotNull LiveSyncTopic topic, @NotNull String reason) {
    return meterRegistry.counter(
        MetricNames.PRESENCE_RELAY_DROPPED,
        MetricNames.TAG_REASON,
        reason,
        MetricNames.TAG_TOPIC_CLASS,
        topic.topicClass().metricLabel());
  }

  /**
   * Resolves the {@link ConcurrentWebSocketSessionDecorator} registered for a raw session.
   *
   * @param session the raw (or already-decorated) session
   * @return the registered decorator, or {@code session} when none is stored
   */
  @NotNull
  private static WebSocketSession decorated(@NotNull WebSocketSession session) {
    return session.getAttributes().get(ATTR_DECORATED) instanceof WebSocketSession ws
        ? ws
        : session;
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

  @Nullable
  private static String textValue(@NotNull JsonNode node, @NotNull String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      return null;
    }
    String s = value.asString();
    return Objects.equals(s, "") ? null : s;
  }

  /**
   * Mutable per-session token-bucket state for the {@code changed}-frame rate limit: the current
   * (fractional) token count and the {@link System#nanoTime()} reading at the last refill. Stored
   * in the WebSocket session attributes and touched only from the single-threaded per-session
   * message delivery, so it needs no synchronisation.
   */
  private static final class ChangedRateState {
    private double tokens;
    private long lastRefillNanos;

    ChangedRateState(double tokens, long lastRefillNanos) {
      this.tokens = tokens;
      this.lastRefillNanos = lastRefillNanos;
    }
  }

  /**
   * Per-topic token-bucket state for the {@code changed}-frame throttle, shared across publishers
   * and mutated under its own monitor.
   */
  private static final class TopicRateState {
    private double tokens;
    private long lastRefillNanos;

    TopicRateState(double tokens, long lastRefillNanos) {
      this.tokens = tokens;
      this.lastRefillNanos = lastRefillNanos;
    }
  }
}
