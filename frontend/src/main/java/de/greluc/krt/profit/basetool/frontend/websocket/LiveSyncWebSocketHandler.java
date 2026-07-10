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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * <p><b>Topic binding.</b> In this release each socket is bound to exactly one topic, resolved at
 * connect time from the {@link #ATTR_TOPIC} session attribute the handshake interceptor sets from
 * the path (the legacy per-resource endpoints). The wire frame's {@code sections} array is
 * sanitised against the bound topic class's whitelist before it is relayed. (The multiplexed {@code
 * subscribe}/{@code changed}-with-topic protocol on a single {@code /ws/sync} socket layers on top
 * of this in a later step.)
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
   */
  public LiveSyncWebSocketHandler(
      @NotNull LiveSyncPresenceService presenceService,
      @NotNull LiveSyncFanout fanout,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry) {
    this.presenceService = presenceService;
    this.fanout = fanout;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
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
   * Registers a freshly connected socket into the room named by its bound topic. The topic is
   * resolved from the {@link #ATTR_TOPIC} attribute the handshake interceptor set (and already
   * authorized) from the request path; a socket with no valid bound topic or no principal is
   * refused.
   *
   * @param session the freshly opened session
   */
  @Override
  public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
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
    // #1149: wrap the raw socket so a slow/dead peer is bounded by the decorator instead of
    // blocking
    // the fan-out. The decorator is what we register and broadcast to; it shares the raw session's
    // attribute map.
    WebSocketSession decorated =
        new ConcurrentWebSocketSessionDecorator(
            session,
            SEND_TIME_LIMIT_MS,
            SEND_BUFFER_SIZE_LIMIT,
            ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
    session.getAttributes().put(ATTR_DECORATED, decorated);
    // #1150: register under the entry's bin lock so a concurrent afterConnectionClosed cannot unmap
    // the set this add is about to land in (which would strand the viewer in an orphaned set).
    sessionsByTopic.compute(
        topic.canonical(),
        (ignored, set) -> {
          Set<WebSocketSession> mates = (set != null) ? set : ConcurrentHashMap.newKeySet();
          mates.add(decorated);
          return mates;
        });
    if (topic.topicClass().presenceEnabled()) {
      sendSnapshot(decorated, topic);
    }
  }

  /**
   * Parses one client message and applies it. Unknown types are silently ignored to keep the wire
   * format forward-compatible.
   *
   * @param session the session that produced the message
   * @param message the text payload
   */
  @Override
  protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message)
      throws Exception {
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
   * Cleans up a closed session — removes it from its room and, if the user has no other live
   * session on the same topic, drops their presence and broadcasts the resulting state.
   *
   * @param session the closing session
   * @param status close reason (unused; logged for diagnostics)
   */
  @Override
  public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status)
      throws Exception {
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
