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
import de.greluc.krt.profit.basetool.frontend.service.MissionPresenceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
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
 * Native WebSocket handler for the mission-detail presence/awareness feature.
 *
 * <p>Each authenticated browser tab opens one socket against {@code
 * /ws/missions/{missionId}/presence}. The handler:
 *
 * <ul>
 *   <li>extracts the mission id from the URI and the user identity from the {@link Principal}
 *       attached to the WebSocket session by Spring Security;
 *   <li>receives JSON messages from the client of the form {@code {"type":"focus"|"blur"
 *       |"heartbeat", "sectionKey":"..."}} and updates the {@link MissionPresenceService};
 *   <li>after every mutation broadcasts the full snapshot for that mission to every connected
 *       socket on the same mission so all clients converge on the same indicator state;
 *   <li>relays a {@code {"type":"changed","sections":[...]}} signal — emitted by a client when its
 *       user mutates the mission — to every <em>other</em> socket on the same mission, so peers
 *       re-fetch the affected section fragments and see the change without reloading (live
 *       multi-user sync). The payload carries only opaque section keys, never mission data: each
 *       peer re-pulls through its own authenticated, authorization-checked fragment endpoint.
 *       Inbound {@code changed} frames are rate-limited per session (a token bucket) so a crafted
 *       client cannot drive unbounded re-fetch amplification;
 *   <li>runs a scheduled reaper at {@link #REAPER_INTERVAL} that drops entries past TTL and
 *       broadcasts a fresh snapshot to the affected rooms.
 * </ul>
 *
 * <p>The wire format is intentionally minimal — no STOMP, no SockJS. The only server-originated
 * frames are presence snapshots and the relayed {@code changed} signal described above; the latter
 * is sanitised (unknown section keys dropped, count capped) before it is fanned out.
 *
 * <p><b>Concurrency &amp; backpressure:</b> the per-mission session map is a {@link
 * ConcurrentHashMap}, and (un)registration mutates the per-mission set atomically under the map
 * entry's bin lock ({@code compute}/{@code computeIfPresent}) so a concurrent open/close cannot
 * strand a viewer in an orphaned set (#1150). Every socket is wrapped in a {@link
 * ConcurrentWebSocketSessionDecorator} at registration (#1149): the decorator serialises concurrent
 * sends (the Spring WebSocket contract forbids raw concurrent sends) <em>and</em> bounds a slow or
 * dead consumer via a send-time and buffer-size limit — on overflow it TERMINATEs that one socket
 * rather than blocking the broadcasting thread (a Tomcat container thread, or the single shared
 * reaper) for up to Tomcat's ~20&nbsp;s blocking-send timeout. Broadcasts iterate a defensive
 * {@code List.copyOf} of the decorators, so a wedged peer no longer wedges the fan-out for every
 * other viewer and every other mission.
 */
@Slf4j
public class MissionPresenceWebSocketHandler extends TextWebSocketHandler {

  /** How often the reaper runs to drop expired presence entries and broadcast updates. */
  public static final Duration REAPER_INTERVAL = Duration.ofSeconds(10);

  /**
   * Section keys the {@code changed} relay accepts and re-broadcasts. Anything else in an inbound
   * {@code sections} array is dropped, so a client can never make peers re-fetch an arbitrary URL —
   * the keys mirror the {@code MISSION_SECTIONS} seam map in {@code mission-detail.js}, which
   * drives both the acting client's broadcast and the peers' live-sync receiver. A key present
   * there but missing here is silently dropped at the relay and the peers' section stays stale
   * until a manual reload (this happened to steps/objectives/frequencies once) — keep the two in
   * sync whenever a mission section is added.
   */
  private static final Set<String> BROADCASTABLE_SECTIONS =
      Set.of("crew", "finance", "mgmt", "overview", "steps", "objectives", "frequencies");

  /** Hard cap on the number of section keys relayed per {@code changed} frame (abuse guard). */
  private static final int MAX_CHANGED_SECTIONS = 8;

  /**
   * Token-bucket capacity for inbound {@code changed} frames per session — the burst a session may
   * relay before throttling kicks in. Sits far above any human edit cadence (a user driving the UI
   * cannot mutate this fast), so a legitimate rapid-editing viewer never trips it; it only bounds a
   * crafted client emitting {@code changed} frames in a loop. Package-private for the test.
   */
  static final int CHANGED_BURST = 20;

  /** Token-bucket refill rate for inbound {@code changed} frames, in tokens per second. */
  private static final double CHANGED_REFILL_PER_SEC = 10.0;

  /**
   * Hard cap on the accepted length of a client-supplied presence {@code sectionKey}. Legitimate
   * section keys are short panel identifiers (a handful of characters, from {@code
   * [data-panel-key]}); anything longer is a crafted client trying to inflate the per-mission
   * presence map's memory footprint and is dropped.
   */
  private static final int MAX_SECTION_KEY_LENGTH = 64;

  /**
   * Token-bucket capacity for inbound presence control frames ({@code focus} / {@code heartbeat} /
   * {@code blur}) per session. Legitimate presence traffic is sparse — a {@code focus} on entering
   * a panel plus a {@code heartbeat} once per {@code HEARTBEAT_MS} (60s) — so this burst sits far
   * above any human cadence and only bounds a crafted client emitting presence frames in a loop.
   * Without it a single socket could drive unbounded per-mission section-map growth and O(N^2)
   * snapshot-broadcast amplification: the {@code changed} relay is bounded by {@link
   * #CHANGED_BURST} but the presence path was not. Package-private for the test.
   */
  static final int PRESENCE_BURST = 20;

  /** Token-bucket refill rate for inbound presence control frames, in tokens per second. */
  private static final double PRESENCE_REFILL_PER_SEC = 10.0;

  /**
   * Max time (ms) a single presence send may block before the {@link
   * ConcurrentWebSocketSessionDecorator} TERMINATEs a wedged peer instead of parking the
   * broadcasting thread (#1149).
   */
  private static final int SEND_TIME_LIMIT_MS = 5_000;

  /**
   * Max bytes buffered for a slow peer before the decorator TERMINATEs it (#1149). Presence frames
   * are tiny (a snapshot / a handful of section keys), so half a MB already tolerates a long burst
   * before a genuinely dead consumer is dropped.
   */
  private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

  private static final String ATTR_MISSION_ID = "missionPresence.missionId";
  private static final String ATTR_USER_ID = "missionPresence.userId";
  private static final String ATTR_DISPLAY_NAME = "missionPresence.displayName";
  private static final String ATTR_CHANGED_RATE = "missionPresence.changedRate";
  private static final String ATTR_PRESENCE_RATE = "missionPresence.presenceRate";

  /**
   * Session-attribute key holding the {@link ConcurrentWebSocketSessionDecorator} wrapping the raw
   * socket (#1149). The decorator — not the raw session — is what lives in {@link
   * #sessionsByMission} and what every broadcast writes to; the close / relay paths resolve it back
   * from the raw session Spring hands them via {@link #decorated(WebSocketSession)}. Stored in the
   * attributes because the decorator shares the delegate's attribute map.
   */
  private static final String ATTR_DECORATED = "missionPresence.decorated";

  private final MissionPresenceService presenceService;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService reaper;
  private final Counter framesChanged;
  private final Counter framesSnapshot;
  private final Counter droppedThrottled;
  private final Counter droppedSendFailed;

  private final Map<UUID, Set<WebSocketSession>> sessionsByMission = new ConcurrentHashMap<>();

  /**
   * Builds the handler. Spring registers it as a bean (see {@code MissionPresenceWebSocketConfig})
   * and the reaper starts ticking immediately. Binds the {@code basetool_presence_ws_sessions}
   * gauge (summed live sessions across all missions) and pre-resolves the relay frame / drop
   * counters (#1041 item 17) — the presence relay is the component that shipped the silent
   * REQ-FE-010 staleness defect, so its throttle and send-failure branches are made observable.
   *
   * @param presenceService in-memory presence store
   * @param objectMapper Jackson mapper, shared with the rest of the app
   * @param meterRegistry the Micrometer registry the session gauge and relay counters bind to
   */
  public MissionPresenceWebSocketHandler(
      @NotNull MissionPresenceService presenceService,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry) {
    this.presenceService = presenceService;
    this.objectMapper = objectMapper;
    Gauge.builder(
            MetricNames.PRESENCE_WS_SESSIONS,
            sessionsByMission,
            map -> map.values().stream().mapToInt(Set::size).sum())
        .description("Live mission-presence WebSocket sessions summed across all missions.")
        .register(meterRegistry);
    this.framesChanged =
        meterRegistry.counter(
            MetricNames.PRESENCE_RELAY_FRAMES, MetricNames.TAG_TYPE, MetricNames.FRAME_CHANGED);
    this.framesSnapshot =
        meterRegistry.counter(
            MetricNames.PRESENCE_RELAY_FRAMES, MetricNames.TAG_TYPE, MetricNames.FRAME_SNAPSHOT);
    this.droppedThrottled =
        meterRegistry.counter(
            MetricNames.PRESENCE_RELAY_DROPPED,
            MetricNames.TAG_REASON,
            MetricNames.DROPPED_THROTTLED);
    this.droppedSendFailed =
        meterRegistry.counter(
            MetricNames.PRESENCE_RELAY_DROPPED,
            MetricNames.TAG_REASON,
            MetricNames.DROPPED_SEND_FAILED);
    this.reaper =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "mission-presence-reaper");
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
   * Called by Spring after a successful WebSocket handshake. Extracts the mission id from the path
   * and the user identity from the principal; rejects sockets that fail either check.
   *
   * @param session the freshly opened session
   */
  @Override
  public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
    UUID missionId = extractMissionId(session.getUri());
    Principal principal = session.getPrincipal();
    if (missionId == null || principal == null) {
      log.debug(
          "Presence socket refused (missionId={}, hasPrincipal={})", missionId, principal != null);
      session.close(CloseStatus.NOT_ACCEPTABLE);
      return;
    }
    String userId = resolveUserId(principal);
    if (userId == null) {
      session.close(CloseStatus.NOT_ACCEPTABLE);
      return;
    }
    String displayName = resolveDisplayName(principal);
    session.getAttributes().put(ATTR_MISSION_ID, missionId);
    session.getAttributes().put(ATTR_USER_ID, userId);
    session.getAttributes().put(ATTR_DISPLAY_NAME, displayName);
    // #1149: wrap the raw socket so a slow/dead peer is bounded by the decorator's send-time /
    // buffer-size limits (TERMINATE on overflow) instead of blocking the fan-out. The decorator is
    // what we register and broadcast to; it shares the raw session's attribute map.
    WebSocketSession decorated =
        new ConcurrentWebSocketSessionDecorator(
            session,
            SEND_TIME_LIMIT_MS,
            SEND_BUFFER_SIZE_LIMIT,
            ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
    session.getAttributes().put(ATTR_DECORATED, decorated);
    // #1150: register under the map entry's bin lock so a concurrent afterConnectionClosed cannot
    // unmap the set this add is about to land in (which would strand the viewer in an orphaned set,
    // silently dead for the rest of the visit — the socket stays open, so no reconnect fires).
    sessionsByMission.compute(
        missionId,
        (ignored, set) -> {
          Set<WebSocketSession> mates = (set != null) ? set : ConcurrentHashMap.newKeySet();
          mates.add(decorated);
          return mates;
        });
    sendSnapshot(decorated, missionId);
  }

  /**
   * Parses a single client message and applies it to the presence store. Unknown message types are
   * silently ignored to keep wire-format evolution forward-compatible.
   *
   * @param session the session that produced the message
   * @param message the text payload
   */
  @Override
  protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message)
      throws Exception {
    UUID missionId = (UUID) session.getAttributes().get(ATTR_MISSION_ID);
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    if (missionId == null || userId == null) {
      return;
    }

    JsonNode node;
    try {
      node = objectMapper.readTree(message.getPayload());
    } catch (JacksonException e) {
      log.debug("Discarding malformed presence message", e);
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
        // Exclude the acting socket by its registered decorator (what the room set holds), not the
        // raw session Spring handed us — otherwise origin exclusion misses and echoes back (#1149).
        broadcastChanged(missionId, node.get("sections"), decorated(session));
      } else {
        droppedThrottled.increment();
      }
      return;
    }
    String sectionKey = textValue(node, "sectionKey");
    if (sectionKey == null
        || sectionKey.isBlank()
        || sectionKey.length() > MAX_SECTION_KEY_LENGTH) {
      return;
    }
    // The focus/heartbeat/blur presence path is rate-limited per session exactly like the `changed`
    // relay above. Without a bound a crafted client could loop `focus` frames carrying unique
    // section keys, each of which (a) inserts a new entry into the per-mission presence map
    // (unbounded memory until the TTL reap) and (b) forces a full-map snapshot rebuild + broadcast
    // to every viewer of the mission — O(N^2) CPU/bandwidth amplification driven from a single
    // authenticated socket. The MissionPresenceService distinct-section cap bounds absolute map
    // size; this token bucket bounds the growth/broadcast rate.
    if (!allowPresenceFrame(session)) {
      droppedThrottled.increment();
      return;
    }

    boolean mutated;
    switch (type) {
      case "focus", "heartbeat" -> {
        String displayName = (String) session.getAttributes().get(ATTR_DISPLAY_NAME);
        mutated = presenceService.touch(missionId, sectionKey, userId, displayName);
      }
      case "blur" -> mutated = presenceService.clear(missionId, sectionKey, userId);
      default -> {
        return;
      }
    }
    // Always broadcast on focus/blur (state changes); on heartbeat broadcast only when this is the
    // FIRST sighting of the editor (touch returns true) — otherwise the snapshot is identical and
    // the broadcast would just generate noise.
    if (mutated || "blur".equals(type) || "focus".equals(type)) {
      broadcastSnapshot(missionId);
    }
  }

  /**
   * Cleans up the closed session — drops every presence entry the user had on this mission and
   * broadcasts the resulting state so other clients see the indicator disappear.
   *
   * @param session the closing session
   * @param status close reason (unused; logged for diagnostics)
   */
  @Override
  public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status)
      throws Exception {
    UUID missionId = (UUID) session.getAttributes().get(ATTR_MISSION_ID);
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    if (missionId == null || userId == null) {
      return;
    }
    // #1150: remove-and-maybe-unmap in one atomic remapping under the entry's bin lock, so the
    // "set became empty -> drop the entry" decision cannot race a concurrent registration into an
    // orphaned set. Deregister the DECORATOR (what was registered), resolved from the raw session.
    WebSocketSession decorated = decorated(session);
    Set<WebSocketSession> mates =
        sessionsByMission.computeIfPresent(
            missionId,
            (ignored, set) -> {
              set.remove(decorated);
              return set.isEmpty() ? null : set;
            });
    // Only clear the user from presence if they have no OTHER live sessions on the same mission
    // (multiple tabs from the same browser would otherwise wipe each other's heartbeats). A null
    // mates means the set is now empty (last session closed), so there is no other session.
    boolean hasOtherSession =
        mates != null
            && mates.stream().anyMatch(s -> userId.equals(s.getAttributes().get(ATTR_USER_ID)));
    if (!hasOtherSession) {
      List<String> cleared = presenceService.clearAll(missionId, userId);
      if (!cleared.isEmpty()) {
        broadcastSnapshot(missionId);
      }
    }
  }

  /**
   * Reaper tick — drops expired entries from every tracked mission and broadcasts the resulting
   * snapshot to rooms that lost at least one entry. Runs on a single daemon thread; any thrown
   * exception is logged and swallowed so a transient failure does not kill the reaper.
   */
  void tickReaper() {
    try {
      List<MissionPresenceService.MissionSectionRef> affected =
          presenceService.reapExpired(Instant.now());
      if (affected.isEmpty()) {
        return;
      }
      Set<UUID> uniqueMissions = new HashSet<>();
      for (MissionPresenceService.MissionSectionRef ref : affected) {
        uniqueMissions.add(ref.missionId());
      }
      for (UUID missionId : uniqueMissions) {
        broadcastSnapshot(missionId);
      }
    } catch (RuntimeException e) {
      log.warn("Presence reaper tick failed", e);
    }
  }

  private void broadcastSnapshot(@NotNull UUID missionId) {
    Set<WebSocketSession> mates = sessionsByMission.get(missionId);
    if (mates == null || mates.isEmpty()) {
      return;
    }
    String payload;
    try {
      payload = objectMapper.writeValueAsString(buildSnapshot(missionId));
    } catch (JacksonException e) {
      log.warn("Failed to serialise presence snapshot for mission {}", missionId, e);
      return;
    }
    TextMessage message = new TextMessage(payload);
    for (WebSocketSession session : List.copyOf(mates)) {
      if (sendSafe(session, message)) {
        framesSnapshot.increment();
      }
    }
  }

  /**
   * Per-session token-bucket rate limit on inbound {@code changed} frames. Consumes and returns
   * {@code true} when a token is available, or returns {@code false} (dropping the frame) once the
   * session exceeds {@link #CHANGED_BURST} frames refilled at {@link #CHANGED_REFILL_PER_SEC}/s.
   * This bounds the same-mission re-fetch amplification a crafted client could drive by emitting
   * {@code changed} frames in a loop; the limits sit far above any human edit cadence so a
   * legitimate viewer never trips it. Frames from one session are delivered serially by the
   * container, so the unsynchronised bucket state held in the session attributes needs no locking.
   *
   * @param session the session that sent the frame
   * @return {@code true} to relay the frame, {@code false} to drop it as throttled
   */
  private boolean allowChangedFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_CHANGED_RATE, CHANGED_BURST, CHANGED_REFILL_PER_SEC);
  }

  /**
   * Per-session token-bucket rate limit on inbound presence control frames ({@code focus} / {@code
   * heartbeat} / {@code blur}), mirroring {@link #allowChangedFrame}. Bounds the per-mission
   * presence-map growth rate and the snapshot-broadcast amplification a crafted client could
   * otherwise drive by looping {@code focus} frames with unique section keys. Legitimate presence
   * traffic (a focus on panel entry plus a 60s heartbeat) sits far below the limit.
   *
   * @param session the session that sent the frame
   * @return {@code true} to process the frame, {@code false} to drop it as throttled
   */
  private boolean allowPresenceFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_PRESENCE_RATE, PRESENCE_BURST, PRESENCE_REFILL_PER_SEC);
  }

  /**
   * Shared token-bucket rate-limit primitive backing {@link #allowChangedFrame} and {@link
   * #allowPresenceFrame}. Consumes and returns {@code true} when a token is available, or returns
   * {@code false} once the session exceeds {@code burst} frames refilled at {@code refillPerSec}/s.
   * The unsynchronised bucket state lives in the session attributes under {@code attrKey}; frames
   * from one session are delivered serially by the container, so no locking is required.
   *
   * @param session the session that sent the frame
   * @param attrKey the session-attribute key holding this bucket's state
   * @param burst the bucket capacity (maximum tokens)
   * @param refillPerSec the token refill rate per second
   * @return {@code true} to process the frame, {@code false} to drop it as throttled
   */
  private boolean allowFrame(
      @NotNull WebSocketSession session,
      @NotNull String attrKey,
      double burst,
      double refillPerSec) {
    long now = System.nanoTime();
    RateState state;
    if (session.getAttributes().get(attrKey) instanceof RateState existing) {
      state = existing;
    } else {
      state = new RateState(burst, now);
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
   * Relays a client's {@code changed} signal to every other socket on the same mission. The inbound
   * {@code sections} array is sanitised — non-string entries and keys outside {@link
   * #BROADCASTABLE_SECTIONS} are dropped and the count is capped at {@link #MAX_CHANGED_SECTIONS} —
   * so a malicious client can neither inject an arbitrary fetch target nor amplify a single frame
   * into an unbounded fan-out. The originating session is skipped: it already applied its own
   * change locally, so echoing back to it would trigger a redundant re-fetch (and, without the
   * client-side suppression, a relay loop).
   *
   * @param missionId mission the signal belongs to
   * @param sectionsNode the raw {@code sections} JSON node from the client (may be {@code null} or
   *     not an array, in which case nothing is relayed)
   * @param origin the session that sent the signal, excluded from the fan-out
   */
  private void broadcastChanged(
      @NotNull UUID missionId, JsonNode sectionsNode, @NotNull WebSocketSession origin) {
    if (sectionsNode == null || !sectionsNode.isArray() || sectionsNode.isEmpty()) {
      return;
    }
    List<String> sections = new ArrayList<>();
    for (JsonNode element : sectionsNode) {
      if (sections.size() >= MAX_CHANGED_SECTIONS) {
        break;
      }
      if (element != null && element.isString()) {
        String key = element.asString();
        if (BROADCASTABLE_SECTIONS.contains(key) && !sections.contains(key)) {
          sections.add(key);
        }
      }
    }
    if (sections.isEmpty()) {
      return;
    }
    Set<WebSocketSession> mates = sessionsByMission.get(missionId);
    if (mates == null || mates.isEmpty()) {
      return;
    }
    String payload;
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("type", "changed");
      ArrayNode sectionsArray = root.putArray("sections");
      for (String key : sections) {
        sectionsArray.add(key);
      }
      payload = objectMapper.writeValueAsString(root);
    } catch (JacksonException e) {
      log.warn("Failed to serialise change relay for mission {}", missionId, e);
      return;
    }
    TextMessage message = new TextMessage(payload);
    for (WebSocketSession session : List.copyOf(mates)) {
      if (session == origin) {
        continue;
      }
      if (sendSafe(session, message)) {
        framesChanged.increment();
      }
    }
  }

  private void sendSnapshot(@NotNull WebSocketSession session, @NotNull UUID missionId) {
    try {
      String payload = objectMapper.writeValueAsString(buildSnapshot(missionId));
      if (sendSafe(session, new TextMessage(payload))) {
        framesSnapshot.increment();
      }
    } catch (JacksonException e) {
      log.warn("Failed to serialise initial presence snapshot for mission {}", missionId, e);
    }
  }

  private ObjectNode buildSnapshot(@NotNull UUID missionId) {
    Map<String, List<MissionPresenceService.Entry>> snapshot =
        presenceService.snapshot(missionId, Instant.now());
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "presence");
    ObjectNode sections = root.putObject("sections");
    for (Map.Entry<String, List<MissionPresenceService.Entry>> e : snapshot.entrySet()) {
      ArrayNode editors = sections.putArray(e.getKey());
      for (MissionPresenceService.Entry editor : e.getValue()) {
        ObjectNode editorNode = editors.addObject();
        editorNode.put("userId", editor.userId());
        editorNode.put("displayName", editor.displayName());
      }
    }
    return root;
  }

  /**
   * Writes one frame to a session, tolerating a closed or broken peer. A send that throws is
   * counted as a {@code send_failed} relay drop ({@code basetool_presence_relay_dropped_total}) and
   * reported as not sent so the caller does not also count it as a delivered frame.
   *
   * @param session the target session
   * @param message the frame to write
   * @return {@code true} if the frame was written, {@code false} if the session was closed or the
   *     write failed
   */
  private boolean sendSafe(@NotNull WebSocketSession session, @NotNull TextMessage message) {
    if (!session.isOpen()) {
      return false;
    }
    // `session` is a ConcurrentWebSocketSessionDecorator (#1149): it serialises concurrent sends
    // internally and bounds a slow consumer via its send-time / buffer-size limits, so NO external
    // synchronized is used here — that would re-introduce the blocking serial fan-out this fixes. A
    // buffer/time overflow surfaces as SessionLimitExceededException and TERMINATEs that one
    // socket.
    try {
      session.sendMessage(message);
      return true;
    } catch (IOException | IllegalStateException | SessionLimitExceededException e) {
      log.debug("Drop presence frame to closed/broken/overflowed session {}", session.getId(), e);
      droppedSendFailed.increment();
      return false;
    }
  }

  /**
   * Resolves the {@link ConcurrentWebSocketSessionDecorator} registered for a socket (#1149).
   * Spring hands the handler the raw session on message / close callbacks, but the room set holds
   * the decorator — this returns the decorator stored in the shared attribute map, falling back to
   * the given session if none was recorded (e.g. a socket refused before wrapping).
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

  static UUID extractMissionId(URI uri) {
    if (uri == null) {
      return null;
    }
    String path = uri.getPath();
    if (path == null) {
      return null;
    }
    // Expected: /ws/missions/{uuid}/presence
    String[] parts = path.split("/");
    List<String> nonEmpty = new ArrayList<>(parts.length);
    for (String p : parts) {
      if (!p.isEmpty()) {
        nonEmpty.add(p);
      }
    }
    if (nonEmpty.size() != 4
        || !"ws".equals(nonEmpty.get(0))
        || !"missions".equals(nonEmpty.get(1))
        || !"presence".equals(nonEmpty.get(3))) {
      return null;
    }
    try {
      return UUID.fromString(nonEmpty.get(2));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String resolveUserId(@NotNull Principal principal) {
    if (principal
        instanceof org.springframework.security.authentication.AbstractAuthenticationToken token) {
      Object p = token.getPrincipal();
      if (p instanceof OidcUser oidc && oidc.getSubject() != null && !oidc.getSubject().isBlank()) {
        return oidc.getSubject();
      }
    }
    String name = principal.getName();
    return (name == null || name.isBlank()) ? null : name;
  }

  private static String resolveDisplayName(@NotNull Principal principal) {
    if (principal
        instanceof org.springframework.security.authentication.AbstractAuthenticationToken token) {
      Object p = token.getPrincipal();
      if (p instanceof OidcUser oidc) {
        // Privacy / data minimisation: the presence label is derived from the public callsign
        // (preferred_username) only. given_name / family_name / the composite name claim are no
        // longer read here — those claims are removed from the Keycloak tokens.
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
   * Mutable token-bucket state for one session's inbound-frame rate limit — one instance per bucket
   * ({@code changed} and presence) stored in the session attributes, holding the current
   * (fractional) token count and the {@link System#nanoTime()} reading at the last refill. Not
   * thread-safe; relies on the container delivering a single session's frames serially.
   */
  private static final class RateState {
    private double tokens;
    private long lastRefillNanos;

    RateState(double tokens, long lastRefillNanos) {
      this.tokens = tokens;
      this.lastRefillNanos = lastRefillNanos;
    }
  }
}
