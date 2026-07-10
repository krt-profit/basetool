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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.socket.CloseStatus;
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
 * Generic native-WebSocket relay for the tool-wide live-sync feature (REQ-FE-015, ADR-0092).
 *
 * <p>Generalises the former per-mission presence relay into a topic-room relay: a socket is bound
 * to a {@link LiveSyncTopic} (its {@link LiveSyncTopicClass} fixes the section whitelist and
 * whether it carries editor-presence dots), and the handler fans a client's {@code
 * {"type":"changed", "sections":[…]}} signal out to every <em>other</em> socket in the same room.
 * Only opaque section keys cross the socket — never entity data: each peer re-pulls the affected
 * fragment through its own authenticated, authorization-checked GET, so redaction and access gates
 * re-apply per viewer.
 *
 * <p><b>Topic binding — two modes.</b> A <em>legacy</em> per-resource socket ({@code
 * /ws/missions/{id}/presence}) is bound to exactly one topic, resolved at connect time from the
 * {@link #ATTR_TOPIC} attribute the handshake interceptor set from the path; every frame operates
 * on that implicit topic. A <em>multiplexed</em> {@code /ws/sync} socket ({@link
 * #ATTR_MULTIPLEXED}) instead binds no topic at connect and manages a set of rooms via {@code
 * subscribe} frames — each authorized asynchronously (off the container thread, on the {@code
 * authExecutor}) by {@link LiveSyncSubscriptionAuthorizer} — while {@code changed} and presence
 * frames carry their own {@code topic}. Publishing a {@code changed} frame needs <b>no</b>
 * subscription (the cross-topic case: a requester notifies a staff queue it may not read), only an
 * authenticated socket, a known topic class and the per-session rate limit; a subscribe is what an
 * <em>inbound</em> relay requires. In both modes the frame's {@code sections} array is sanitised
 * against the topic class's whitelist before it is relayed.
 *
 * <p><b>Cross-replica fan-out.</b> An accepted {@code changed} frame is relayed to this instance's
 * local room first, then handed to {@link LiveSyncFanout#publish(String, List)} so peer replicas
 * relay it to their local rooms; {@link #deliverFromFanout(String, List)} is the consume-side entry
 * a Redis subscriber calls. Because local relay happens first, a fan-out outage degrades to
 * single-instance behaviour, never worse (ADR-0092).
 *
 * <p><b>Concurrency &amp; backpressure</b> (preserved verbatim from the mission relay,
 * #1149/#1150): the per-topic session map is a {@link ConcurrentHashMap} whose sets are mutated
 * atomically under the entry's bin lock ({@code compute}/{@code computeIfPresent}), so a concurrent
 * open/close cannot strand a viewer in an orphaned set. Every socket is wrapped once in a {@link
 * ConcurrentWebSocketSessionDecorator} (send-time and buffer-size bounded, TERMINATE on overflow)
 * so a slow/dead consumer is dropped rather than blocking the serial broadcast loop; broadcasts
 * iterate a defensive {@code List.copyOf}.
 */
@Slf4j
public class LiveSyncWebSocketHandler extends TextWebSocketHandler {

  /** How often the reaper runs to drop expired presence entries and broadcast updates. */
  public static final Duration REAPER_INTERVAL = Duration.ofSeconds(10);

  /** Hard cap on the number of section keys relayed per {@code changed} frame (abuse guard). */
  private static final int MAX_CHANGED_SECTIONS = 16;

  /**
   * Token-bucket capacity for inbound {@code changed} frames per session — the burst a session may
   * relay before throttling kicks in. Sits far above any human edit cadence, so a legitimate
   * rapid-editing viewer never trips it; it only bounds a crafted client emitting {@code changed}
   * frames in a loop. Package-private for the test.
   */
  static final int CHANGED_BURST = 20;

  /** Token-bucket refill rate for inbound {@code changed} frames, in tokens per second. */
  private static final double CHANGED_REFILL_PER_SEC = 10.0;

  /**
   * Max time (ms) a single send may block before the {@link ConcurrentWebSocketSessionDecorator}
   * TERMINATEs a wedged peer instead of parking the broadcasting thread (#1149).
   */
  private static final int SEND_TIME_LIMIT_MS = 5_000;

  /**
   * Max bytes buffered for a slow peer before the decorator TERMINATEs it (#1149). Frames are tiny
   * (a snapshot / a handful of section keys), so half a MB tolerates a long burst before a
   * genuinely dead consumer is dropped.
   */
  private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

  /**
   * Session-attribute key holding the canonical topic string a socket is bound to. Set by the
   * handshake interceptor from the request path (the legacy per-resource endpoints) and read here
   * to resolve the socket's room, section whitelist and presence flag. Public so the interceptor
   * can populate it.
   */
  public static final String ATTR_TOPIC = "livesync.topic";

  /**
   * Session-attribute key ({@link Boolean}) marking a multiplexed {@code /ws/sync} socket, as
   * opposed to a legacy per-resource socket (which instead carries {@link #ATTR_TOPIC}). Set by the
   * {@code /ws/sync} handshake interceptor. Public so the interceptor can populate it.
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
   * Session-attribute key holding a multiplexed socket's set of subscribed canonical topics (a
   * {@code Set<String>}). Drives the per-session topic cap, idempotent re-subscribe and close-time
   * room cleanup. Absent on legacy sockets, which clean up their single {@link #ATTR_TOPIC} room.
   */
  private static final String ATTR_SUBSCRIPTIONS = "livesync.subscriptions";

  /**
   * Hard cap on distinct topics one multiplexed socket may subscribe to, so a crafted client cannot
   * fan one socket across unbounded rooms. A page subscribes to a handful of topics, so this sits
   * far above any legitimate use.
   */
  private static final int MAX_TOPICS_PER_SESSION = 16;

  private static final String ATTR_USER_ID = "livesync.userId";
  private static final String ATTR_DISPLAY_NAME = "livesync.displayName";
  private static final String ATTR_CHANGED_RATE = "livesync.changedRate";

  /**
   * Session-attribute key holding the {@link ConcurrentWebSocketSessionDecorator} wrapping the raw
   * socket (#1149). The decorator — not the raw session — is what lives in {@link #sessionsByTopic}
   * and what every broadcast writes to; the close / relay paths resolve it back from the raw
   * session Spring hands them via {@link #decorated(WebSocketSession)}.
   */
  private static final String ATTR_DECORATED = "livesync.decorated";

  private final LiveSyncPresenceService presenceService;
  private final LiveSyncFanout fanout;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService reaper;
  private final MeterRegistry meterRegistry;
  private final LiveSyncSubscriptionAuthorizer authorizer;
  private final Executor authExecutor;

  private final Map<String, Set<WebSocketSession>> sessionsByTopic = new ConcurrentHashMap<>();

  /**
   * Builds the handler. Binds the {@code basetool_presence_ws_sessions} gauge (live sockets summed
   * across all rooms) and one {@code basetool_livesync_subscriptions{topic_class}} gauge per topic
   * class; the reaper starts ticking immediately.
   *
   * @param presenceService in-memory editor-presence store
   * @param fanout cross-replica fan-out seam (no-op when single-instance)
   * @param objectMapper Jackson mapper for the minimal {@code {type, sections}} wire format
   * @param meterRegistry the Micrometer registry the gauges and relay counters bind to
   * @param authorizer authorizes a multiplexed {@code /ws/sync} subscribe to a resource topic
   * @param authExecutor executor that runs subscribe-authorization probes off the WebSocket
   *     container thread; a {@link RejectedExecutionException} (saturation) fails the subscribe
   *     open
   */
  public LiveSyncWebSocketHandler(
      @NotNull LiveSyncPresenceService presenceService,
      @NotNull LiveSyncFanout fanout,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry,
      @NotNull LiveSyncSubscriptionAuthorizer authorizer,
      @NotNull Executor authExecutor) {
    this.presenceService = presenceService;
    this.fanout = fanout;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.authorizer = authorizer;
    this.authExecutor = authExecutor;
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
    }
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
  }

  /** Shuts the reaper thread down cleanly on application shutdown. */
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
   * Registers a freshly connected socket. A legacy per-resource socket ({@link #ATTR_TOPIC} set)
   * joins its single implicit room; a multiplexed {@code /ws/sync} socket ({@link
   * #ATTR_MULTIPLEXED}) joins no room and waits for {@code subscribe} frames.
   *
   * @param session the freshly opened session
   */
  @Override
  public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
    if (Boolean.TRUE.equals(session.getAttributes().get(ATTR_MULTIPLEXED))) {
      establishMultiplexed(session);
    } else {
      establishLegacy(session);
    }
  }

  /**
   * Registers a legacy per-resource socket into the room named by its bound topic. The topic is
   * resolved from the {@link #ATTR_TOPIC} attribute the handshake interceptor set (and already
   * authorized) from the request path; a socket with no valid bound topic or no principal is
   * refused. Behaviour is unchanged from the original mission-presence relay.
   *
   * @param session the freshly opened legacy session
   */
  private void establishLegacy(@NotNull WebSocketSession session) throws Exception {
    LiveSyncTopic topic = LiveSyncTopic.parse((String) session.getAttributes().get(ATTR_TOPIC));
    Principal principal = session.getPrincipal();
    if (topic == null || principal == null) {
      log.debug("Live-sync socket refused (topic={}, hasPrincipal={})", topic, principal != null);
      session.close(CloseStatus.NOT_ACCEPTABLE);
      return;
    }
    String userId = resolveUserId(principal);
    if (userId == null) {
      session.close(CloseStatus.NOT_ACCEPTABLE);
      return;
    }
    // Store the canonical topic so subsequent frames resolve their room, whitelist and presence
    // flag
    // from the parsed value without re-parsing.
    session.getAttributes().put(ATTR_TOPIC, topic.canonical());
    session.getAttributes().put(ATTR_USER_ID, userId);
    session.getAttributes().put(ATTR_DISPLAY_NAME, resolveDisplayName(principal));
    WebSocketSession decorated = wrap(session);
    session.getAttributes().put(ATTR_DECORATED, decorated);
    joinRoom(decorated, topic);
    if (topic.topicClass().presenceEnabled()) {
      sendSnapshot(decorated, topic);
    }
  }

  /**
   * Registers a multiplexed {@code /ws/sync} socket. It joins no room at connect — its rooms are
   * built by later {@code subscribe} frames — so this only resolves the principal, wraps the socket
   * in its backpressure decorator and seeds an empty subscription set. A socket with no resolvable
   * principal is refused.
   *
   * @param session the freshly opened multiplexed session
   */
  private void establishMultiplexed(@NotNull WebSocketSession session) throws Exception {
    Principal principal = session.getPrincipal();
    String userId = principal == null ? null : resolveUserId(principal);
    if (userId == null) {
      log.debug("Live-sync /ws/sync socket refused (no principal)");
      session.close(CloseStatus.NOT_ACCEPTABLE);
      return;
    }
    session.getAttributes().put(ATTR_USER_ID, userId);
    session.getAttributes().put(ATTR_DISPLAY_NAME, resolveDisplayName(principal));
    session.getAttributes().put(ATTR_SUBSCRIPTIONS, ConcurrentHashMap.<String>newKeySet());
    WebSocketSession decorated = wrap(session);
    session.getAttributes().put(ATTR_DECORATED, decorated);
  }

  /**
   * Wraps a raw socket in a {@link ConcurrentWebSocketSessionDecorator} (#1149): a slow/dead peer
   * is bounded by the decorator's send-time / buffer-size limits (TERMINATE on overflow) instead of
   * blocking the serial fan-out. The decorator is what is registered into rooms and broadcast to;
   * it shares the raw session's attribute map.
   *
   * @param session the raw session
   * @return the backpressure-bounding decorator around it
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
   * Adds a socket's decorator to a topic's room under the entry's bin lock (#1150), so a concurrent
   * close cannot unmap the set this add is about to land in (which would strand the viewer in an
   * orphaned set).
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
   * Dispatches one client message by socket mode: a multiplexed {@code /ws/sync} socket resolves
   * the topic per frame, a legacy socket applies every frame to its single bound topic. Unknown
   * types are silently ignored to keep the wire format forward-compatible.
   *
   * @param session the session that produced the message
   * @param message the text payload
   */
  @Override
  protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message)
      throws Exception {
    if (Boolean.TRUE.equals(session.getAttributes().get(ATTR_MULTIPLEXED))) {
      handleMultiplexedMessage(session, message);
    } else {
      handleLegacyMessage(session, message);
    }
  }

  /**
   * Applies a message on a legacy per-resource socket: every frame operates on the socket's single
   * bound {@link #ATTR_TOPIC}. Behaviour is unchanged from the original mission-presence relay.
   *
   * @param session the legacy session that produced the message
   * @param message the text payload
   */
  private void handleLegacyMessage(
      @NotNull WebSocketSession session, @NotNull TextMessage message) {
    LiveSyncTopic topic = LiveSyncTopic.parse((String) session.getAttributes().get(ATTR_TOPIC));
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    if (topic == null || userId == null) {
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
    // Live multi-user sync: relay the mutating client's "section changed" signal to its peers.
    // Handled before the presence path because the frame carries a "sections" array, not the
    // "sectionKey" the focus/blur/heartbeat messages use.
    if ("changed".equals(type)) {
      if (allowChangedFrame(session)) {
        // Exclude the acting socket by its registered decorator (what the room holds), not the raw
        // session Spring handed us — otherwise origin exclusion misses and echoes back (#1149).
        List<String> sections = sanitiseSections(node.get("sections"), topic.topicClass());
        if (!sections.isEmpty()) {
          relayLocal(topic, sections, decorated(session));
          fanout.publish(topic.canonical(), sections);
        }
      } else {
        droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      }
      return;
    }
    if (!topic.topicClass().presenceEnabled()) {
      return;
    }
    String sectionKey = textValue(node, "sectionKey");
    if (sectionKey == null || sectionKey.isBlank()) {
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
    // Always broadcast on focus/blur (state changes); on heartbeat broadcast only when this is the
    // FIRST sighting of the editor — otherwise the snapshot is identical and the broadcast is
    // noise.
    if (mutated || "blur".equals(type) || "focus".equals(type)) {
      broadcastSnapshot(topic);
    }
  }

  /**
   * Cleans up a closed session by socket mode: a legacy socket leaves its single bound room; a
   * multiplexed socket leaves every room it subscribed to.
   *
   * @param session the closing session
   * @param status close reason (unused; logged for diagnostics)
   */
  @Override
  public void afterConnectionClosed(
      @NotNull WebSocketSession session, @NotNull CloseStatus status) {
    if (Boolean.TRUE.equals(session.getAttributes().get(ATTR_MULTIPLEXED))) {
      closeMultiplexed(session);
    } else {
      closeLegacy(session);
    }
  }

  /**
   * Cleans up a closed legacy socket — removes it from its single bound room and, if the user has
   * no other live session on the same topic, drops their presence and broadcasts the resulting
   * state. Behaviour is unchanged from the original mission-presence relay.
   *
   * @param session the closing legacy session
   */
  private void closeLegacy(@NotNull WebSocketSession session) {
    LiveSyncTopic topic = LiveSyncTopic.parse((String) session.getAttributes().get(ATTR_TOPIC));
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    if (topic == null || userId == null) {
      return;
    }
    // #1150: remove-and-maybe-unmap in one atomic remapping under the entry's bin lock, so the "set
    // became empty -> drop the entry" decision cannot race a concurrent registration into an
    // orphaned set. Deregister the DECORATOR (what was registered), resolved from the raw session.
    WebSocketSession decorated = decorated(session);
    Set<WebSocketSession> mates =
        sessionsByTopic.computeIfPresent(
            topic.canonical(),
            (ignored, set) -> {
              set.remove(decorated);
              return set.isEmpty() ? null : set;
            });
    if (!topic.topicClass().presenceEnabled()) {
      return;
    }
    // Only clear the user from presence if they have no OTHER live session on the same topic
    // (multiple tabs from the same browser would otherwise wipe each other's heartbeats). A null
    // mates means the set is now empty (last session closed), so there is no other session.
    boolean hasOtherSession =
        mates != null
            && mates.stream().anyMatch(s -> userId.equals(s.getAttributes().get(ATTR_USER_ID)));
    if (!hasOtherSession) {
      List<String> cleared = presenceService.clearAll(topic.canonical(), userId);
      if (!cleared.isEmpty()) {
        broadcastSnapshot(topic);
      }
    }
  }

  /**
   * Applies a message on a multiplexed {@code /ws/sync} socket, resolving the topic per frame:
   * {@code subscribe} joins an authorized room, {@code changed} publishes to a topic (no
   * subscription required), and presence frames touch a subscribed presence room.
   *
   * @param session the multiplexed session that produced the message
   * @param message the text payload
   */
  private void handleMultiplexedMessage(
      @NotNull WebSocketSession session, @NotNull TextMessage message) {
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
      default -> {
        // Unknown type: ignore to keep the wire format forward-compatible.
      }
    }
  }

  /**
   * Handles a {@code subscribe} frame: validates the topic, enforces the per-session topic cap and
   * idempotency, then authorizes the subscribe asynchronously on {@link #authExecutor} (so the
   * container thread never blocks on a backend probe). A rejected submission (executor saturated)
   * fails the subscribe open. The room is joined only once {@link #completeSubscribe} confirms an
   * allow.
   *
   * @param session the subscribing session
   * @param node the parsed {@code subscribe} frame
   */
  private void handleSubscribe(@NotNull WebSocketSession session, @NotNull JsonNode node) {
    String rawTopic = textValue(node, "topic");
    LiveSyncTopic topic = LiveSyncTopic.parse(rawTopic);
    if (topic == null) {
      log.debug("Live-sync subscribe to unknown topic '{}' refused", rawTopic);
      sendControlFrame(session, "denied", rawTopic);
      return;
    }
    Set<String> subs = subscriptions(session);
    if (subs == null) {
      return;
    }
    if (subs.contains(topic.canonical())) {
      // Idempotent (re)subscribe — e.g. after a reconnect: the socket already holds this room, so
      // just re-ack so the client can drive its post-reconnect resync.
      sendControlFrame(session, "subscribed", topic.canonical());
      return;
    }
    if (subs.size() >= MAX_TOPICS_PER_SESSION) {
      droppedCounter(topic, MetricNames.DROPPED_TOPIC_CAP).increment();
      sendControlFrame(session, "denied", topic.canonical());
      return;
    }
    // Reserve the slot synchronously so the cap and idempotency hold even while the async probe
    // runs;
    // a DENY (or a close during the probe) removes it again in completeSubscribe.
    subs.add(topic.canonical());
    String token = (String) session.getAttributes().get(ATTR_ACCESS_TOKEN);
    UUID pin = session.getAttributes().get(ATTR_ACTIVE_ORG_UNIT) instanceof UUID u ? u : null;
    try {
      authExecutor.execute(() -> authorizeAndRegister(session, topic, token, pin));
    } catch (RejectedExecutionException e) {
      // Auth executor saturated: fail open (opaque keys only; each fragment re-pull re-authorizes).
      droppedCounter(topic, MetricNames.DROPPED_AUTHORIZE_SATURATED).increment();
      completeSubscribe(session, topic, LiveSyncSubscriptionAuthorizer.Decision.ALLOW);
    }
  }

  /**
   * Runs the subscribe-authorization probe (on {@link #authExecutor}) and applies its verdict. A
   * probe that throws unexpectedly fails open — consistent with the authorizer's own fail-open on
   * transient errors and safe because only opaque keys cross the socket.
   *
   * @param session the subscribing session
   * @param topic the topic being authorized
   * @param token the captured OAuth2 access token (may be {@code null})
   * @param pin the captured active-org-unit pin (may be {@code null})
   */
  private void authorizeAndRegister(
      @NotNull WebSocketSession session, @NotNull LiveSyncTopic topic, String token, UUID pin) {
    LiveSyncSubscriptionAuthorizer.Decision decision;
    try {
      decision = authorizer.authorize(topic, token, pin);
    } catch (RuntimeException e) {
      log.debug(
          "Live-sync subscribe authorization threw for {}; failing open", topic.canonical(), e);
      decision = LiveSyncSubscriptionAuthorizer.Decision.ALLOW;
    }
    completeSubscribe(session, topic, decision);
  }

  /**
   * Finalises a subscribe: on DENY it drops the reserved slot and refuses; on ALLOW it joins the
   * room (skipping a socket that closed while the probe ran), acks {@code subscribed} and sends the
   * initial presence snapshot for a presence-enabled class.
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
    if (decision == LiveSyncSubscriptionAuthorizer.Decision.DENY) {
      if (subs != null) {
        subs.remove(topic.canonical());
      }
      sendControlFrame(session, "denied", topic.canonical());
      subscribeCounter(topic, MetricNames.OUTCOME_DENIED).increment();
      return;
    }
    WebSocketSession decorated = decorated(session);
    if (!decorated.isOpen() || subs == null || !subs.contains(topic.canonical())) {
      // Socket closed (or the subscribe was cleaned up) while the probe ran: nothing to join.
      if (subs != null) {
        subs.remove(topic.canonical());
      }
      return;
    }
    joinRoom(decorated, topic);
    if (!decorated.isOpen()) {
      // Lost the race with a concurrent close between the check and the join: undo so no closed
      // decorator lingers in the room.
      leaveRoom(decorated, topic);
      return;
    }
    sendControlFrame(session, "subscribed", topic.canonical());
    if (topic.topicClass().presenceEnabled()) {
      sendSnapshot(decorated, topic);
    }
    subscribeCounter(topic, MetricNames.OUTCOME_ALLOWED).increment();
  }

  /**
   * Handles a {@code changed} frame on a multiplexed socket. Publishing needs no subscription —
   * only an authenticated socket, a known topic class, the per-session rate limit and the class's
   * section whitelist — so this resolves the frame's own topic, sanitises, relays locally
   * (excluding the origin) and hands the signal to the cross-replica fan-out.
   *
   * @param session the publishing session
   * @param node the parsed {@code changed} frame
   */
  private void handleMultiplexedChanged(@NotNull WebSocketSession session, @NotNull JsonNode node) {
    LiveSyncTopic topic = LiveSyncTopic.parse(textValue(node, "topic"));
    if (topic == null) {
      return;
    }
    if (!allowChangedFrame(session)) {
      droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      return;
    }
    List<String> sections = sanitiseSections(node.get("sections"), topic.topicClass());
    if (sections.isEmpty()) {
      return;
    }
    relayLocal(topic, sections, decorated(session));
    fanout.publish(topic.canonical(), sections);
  }

  /**
   * Handles a presence frame ({@code focus}/{@code blur}/{@code heartbeat}) on a multiplexed
   * socket. Presence is only tracked for a presence-enabled class and only for a room the socket is
   * actually subscribed to; the touch/clear + broadcast logic mirrors the legacy path.
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
    if (sectionKey == null || sectionKey.isBlank()) {
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
      broadcastSnapshot(topic);
    }
  }

  /**
   * Cleans up a closed multiplexed socket — leaves every room it subscribed to and, for a
   * presence-enabled room where the user has no other live session, drops their presence and
   * broadcasts the resulting snapshot.
   *
   * @param session the closing multiplexed session
   */
  private void closeMultiplexed(@NotNull WebSocketSession session) {
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    WebSocketSession decorated = decorated(session);
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
          broadcastSnapshot(topic);
        }
      }
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
   * Sends a tiny control frame ({@code {"type":…,"topic":…}}) — a {@code subscribed} ack or a
   * {@code denied} refusal — to a multiplexed socket, tolerating a closed/broken peer. Control
   * frames are low-volume, so no relay-drop metric is recorded here.
   *
   * @param session the target session (its decorator is resolved and written to)
   * @param type the control-frame type ({@code subscribed} / {@code denied})
   * @param topicString the topic the control frame refers to (echoed as-is; may be an unparseable
   *     value for a denied unknown-topic subscribe)
   */
  private void sendControlFrame(
      @NotNull WebSocketSession session, @NotNull String type, String topicString) {
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
   * null} on a socket without one (a legacy socket, or before the set is seeded).
   *
   * @param session the session
   * @return the subscription set, or {@code null}
   */
  @SuppressWarnings("unchecked")
  private static Set<String> subscriptions(@NotNull WebSocketSession session) {
    // Object -> generic cast is unavoidable reading the WebSocket attribute map
    // (Map<String,Object>);
    // ATTR_SUBSCRIPTIONS is only ever written as a ConcurrentHashMap keySet of topic strings.
    Object value = session.getAttributes().get(ATTR_SUBSCRIPTIONS);
    return value instanceof Set ? (Set<String>) value : null;
  }

  /**
   * Counter {@code basetool_livesync_subscribe_total{topic_class, outcome}} for a subscribe
   * verdict.
   *
   * @param topic the subscribed topic (its class tags the metric)
   * @param outcome {@link MetricNames#OUTCOME_ALLOWED} or {@link MetricNames#OUTCOME_DENIED}
   * @return the counter to increment
   */
  private Counter subscribeCounter(@NotNull LiveSyncTopic topic, @NotNull String outcome) {
    return meterRegistry.counter(
        MetricNames.LIVESYNC_SUBSCRIBE,
        MetricNames.TAG_TOPIC_CLASS,
        topic.topicClass().metricLabel(),
        MetricNames.TAG_OUTCOME,
        outcome);
  }

  /**
   * Relays a {@code changed} signal that arrived from a peer replica via the fan-out (ADR-0092) to
   * this instance's local room. No origin session is excluded — the originator lives on another
   * replica — and nothing is re-published (that would loop).
   *
   * @param canonicalTopic the canonical topic string
   * @param sections the already-sanitised section keys
   */
  public void deliverFromFanout(@NotNull String canonicalTopic, @NotNull List<String> sections) {
    LiveSyncTopic topic = LiveSyncTopic.parse(canonicalTopic);
    if (topic == null || sections.isEmpty()) {
      return;
    }
    relayLocal(topic, sections, null);
  }

  /**
   * Reaper tick — drops expired presence entries from every tracked topic and broadcasts the
   * resulting snapshot to rooms that lost at least one entry. Runs on a single daemon thread; any
   * thrown exception is logged and swallowed so a transient failure does not kill the reaper.
   */
  void tickReaper() {
    try {
      List<LiveSyncPresenceService.TopicSectionRef> affected =
          presenceService.reapExpired(Instant.now());
      if (affected.isEmpty()) {
        return;
      }
      Set<String> uniqueTopics = new HashSet<>();
      for (LiveSyncPresenceService.TopicSectionRef ref : affected) {
        uniqueTopics.add(ref.topic());
      }
      for (String canonical : uniqueTopics) {
        LiveSyncTopic topic = LiveSyncTopic.parse(canonical);
        if (topic != null) {
          broadcastSnapshot(topic);
        }
      }
    } catch (RuntimeException e) {
      log.warn("Live-sync reaper tick failed", e);
    }
  }

  /**
   * Sanitises an inbound {@code sections} array against a topic class's whitelist: non-string
   * entries and keys outside {@link LiveSyncTopicClass#allowedSections()} are dropped, duplicates
   * are collapsed, and the count is capped at {@link #MAX_CHANGED_SECTIONS}. This is what stops a
   * client injecting an arbitrary fetch target or amplifying one frame into an unbounded fan-out.
   *
   * @param sectionsNode the raw {@code sections} node (may be {@code null} or not an array)
   * @param topicClass the class whose whitelist applies
   * @return the accepted, de-duplicated, capped keys (never {@code null})
   */
  private List<String> sanitiseSections(
      JsonNode sectionsNode, @NotNull LiveSyncTopicClass topicClass) {
    List<String> sections = new ArrayList<>();
    if (sectionsNode == null || !sectionsNode.isArray()) {
      return sections;
    }
    Set<String> allowed = topicClass.allowedSections();
    for (JsonNode element : sectionsNode) {
      if (sections.size() >= MAX_CHANGED_SECTIONS) {
        break;
      }
      if (element != null && element.isString()) {
        String key = element.asString();
        if (allowed.contains(key) && !sections.contains(key)) {
          sections.add(key);
        }
      }
    }
    return sections;
  }

  /**
   * Fans a sanitised {@code changed} frame out to every socket in the topic's room except {@code
   * origin} (which already applied its own change; {@code null} when the frame came from a peer
   * replica via the fan-out, where no local origin exists).
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
   * Per-session token-bucket rate limit on inbound {@code changed} frames. Consumes and returns
   * {@code true} when a token is available, or returns {@code false} (dropping the frame) once the
   * session exceeds {@link #CHANGED_BURST} frames refilled at {@link #CHANGED_REFILL_PER_SEC}/s.
   * Frames from one session are delivered serially by the container, so the unsynchronised bucket
   * state held in the session attributes needs no locking.
   *
   * @param session the session that sent the frame
   * @return {@code true} to relay the frame, {@code false} to drop it as throttled
   */
  private boolean allowChangedFrame(@NotNull WebSocketSession session) {
    long now = System.nanoTime();
    ChangedRateState state;
    if (session.getAttributes().get(ATTR_CHANGED_RATE) instanceof ChangedRateState existing) {
      state = existing;
    } else {
      state = new ChangedRateState(CHANGED_BURST, now);
      session.getAttributes().put(ATTR_CHANGED_RATE, state);
    }
    double elapsedSeconds = (now - state.lastRefillNanos) / 1_000_000_000.0;
    state.tokens = Math.min(CHANGED_BURST, state.tokens + elapsedSeconds * CHANGED_REFILL_PER_SEC);
    state.lastRefillNanos = now;
    if (state.tokens >= 1.0) {
      state.tokens -= 1.0;
      return true;
    }
    return false;
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
   * Writes one frame to a session, tolerating a closed or broken peer. A send that throws is
   * counted as a {@code send_failed} relay drop (tagged with the topic class) and reported as not
   * sent so the caller does not also count it as a delivered frame.
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
    // `session` is a ConcurrentWebSocketSessionDecorator (#1149): it serialises concurrent sends
    // and
    // bounds a slow consumer via its send-time / buffer-size limits, so NO external synchronized is
    // used here — that would re-introduce the blocking serial fan-out this fixes. A buffer/time
    // overflow surfaces as SessionLimitExceededException and TERMINATEs that one socket.
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
   * Resolves the {@link ConcurrentWebSocketSessionDecorator} registered for a socket (#1149).
   * Spring hands the handler the raw session on message / close callbacks, but the room holds the
   * decorator — this returns the decorator stored in the shared attribute map, falling back to the
   * given session if none was recorded.
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

  private static String resolveUserId(@NotNull Principal principal) {
    if (principal instanceof AbstractAuthenticationToken token) {
      Object p = token.getPrincipal();
      if (p instanceof OidcUser oidc && oidc.getSubject() != null && !oidc.getSubject().isBlank()) {
        return oidc.getSubject();
      }
    }
    String name = principal.getName();
    return (name == null || name.isBlank()) ? null : name;
  }

  private static String resolveDisplayName(@NotNull Principal principal) {
    if (principal instanceof AbstractAuthenticationToken token) {
      Object p = token.getPrincipal();
      if (p instanceof OidcUser oidc) {
        // Privacy / data minimisation: the presence label is derived from the public callsign
        // (preferred_username) only. given_name / family_name / the composite name claim are not
        // read here — those claims are removed from the Keycloak tokens.
        String preferred = oidc.getPreferredUsername();
        if (preferred != null && !preferred.isBlank()) {
          return preferred;
        }
      }
    }
    String name = principal.getName();
    return name == null ? "" : name;
  }

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
}
