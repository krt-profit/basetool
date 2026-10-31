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
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Native-WebSocket relay for tool-wide live sync (REQ-FE-015, ADR-0094): fans a client's {@code
 * changed} section keys out to the other sockets of a {@link LiveSyncTopic} room.
 *
 * <p>Only opaque, whitelisted section keys cross the socket; each peer re-fetches its own data.
 * Rooms are joined by authorized {@code subscribe} frames; publishing needs no subscription.
 * Changes and editor presence are relayed locally first, then across replicas via {@link
 * LiveSyncFanout} (ADR-0126). The handler dispatches the frames and owns the reaper; the socket
 * lifecycle lives in {@link LiveSyncSessionRegistry}, the subscribe flow in {@link
 * LiveSyncSubscriptions}, the rooms in {@link LiveSyncRooms} and the token buckets in {@link
 * LiveSyncRateLimiter}.
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
  static final int MAX_LOGGED_TOPIC_LENGTH = 64;

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
   * Session-attribute key ({@link UUID}) for the caller's own user id captured at handshake, which
   * alone admits a member's personal room.
   */
  public static final String ATTR_SUBJECT = "livesync.subject";

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

  /**
   * Session-attribute key ({@link Instant}) holding the expiry of the access token captured at
   * handshake. A subscribe on a socket whose token has lapsed closes the socket with {@link
   * #TOKEN_EXPIRED}. Public so the interceptor can populate it.
   */
  public static final String ATTR_TOKEN_EXPIRES_AT = "livesync.tokenExpiresAt";

  /**
   * Close status {@code 4401} for a socket whose captured token and authorities have lapsed or
   * whose token a probe saw rejected (401); the client reconnects, and the new handshake refreshes
   * both before its topics are re-subscribed.
   */
  static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4401, "token expired");

  private final LiveSyncPresenceService presenceService;
  private final LiveSyncFanout fanout;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService reaper;

  /**
   * Monotonic nanosecond clock for token-bucket refills and the idle-bucket reaper; {@link
   * System#nanoTime()} in production.
   */
  private final LongSupplier nanoClock;

  private final LiveSyncRateLimiter rateLimiter;
  private final LiveSyncRooms rooms;
  private final LiveSyncSessionRegistry sessions;
  private final LiveSyncSubscriptions subscriptions;

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
   *     RejectedExecutionException} resolves the subscribe as indeterminate
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
   *     RejectedExecutionException} resolves the subscribe as indeterminate
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
    this.nanoClock = nanoClock;
    this.rateLimiter = new LiveSyncRateLimiter(nanoClock);
    this.rooms = new LiveSyncRooms(presenceService, fanout, objectMapper, meterRegistry);
    this.sessions = new LiveSyncSessionRegistry(meterRegistry, nanoClock);
    this.subscriptions =
        new LiveSyncSubscriptions(
            rooms, rateLimiter, authorizer, authExecutor, objectMapper, meterRegistry);
    this.reaper =
        Executors.newSingleThreadScheduledExecutor(r -> {
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
   * Registers a new {@code /ws/sync} socket through {@link LiveSyncSessionRegistry#open}, which
   * refuses a socket without accepted Terms of Use (REQ-SEC-028), without a resolvable principal,
   * or over {@link #MAX_SOCKETS_PER_USER}.
   *
   * @param session the freshly opened session
   */
  @Override
  public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
    sessions.open(session);
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
    String userId = LiveSyncSessionRegistry.userId(session);
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
      case "subscribe" -> subscriptions.subscribe(session, textValue(node, "topic"));
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
    sessions.close(session);
    String userId = LiveSyncSessionRegistry.userId(session);
    WebSocketSession decorated = LiveSyncSessionRegistry.decorated(session);
    Set<String> subs = LiveSyncSessionRegistry.subscriptions(session);
    if (subs == null) {
      return;
    }
    for (String canonical : List.copyOf(subs)) {
      LiveSyncTopic topic = LiveSyncTopic.parse(canonical);
      if (topic == null) {
        continue;
      }
      Set<WebSocketSession> mates = rooms.leave(decorated, canonical);
      if (userId == null || !topic.topicClass().presenceEnabled()) {
        continue;
      }
      boolean hasOtherSession =
          mates != null
              && mates.stream().anyMatch(s -> userId.equals(LiveSyncSessionRegistry.userId(s)));
      if (!hasOtherSession) {
        List<String> cleared = presenceService.clearAll(canonical, userId);
        if (!cleared.isEmpty()) {
          rooms.broadcastLocalPresenceChange(topic);
        }
      }
    }
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
    if (!rateLimiter.allowChangedFrame(session)) {
      rooms.droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      return;
    }
    LiveSyncSections.Filtered filtered =
        LiveSyncSections.sanitise(node.get("sections"), topic.topicClass());
    reportFilteredSections(topic, filtered, "client");
    if (filtered.accepted().isEmpty()) {
      return;
    }
    relayChangedThrottled(topic, filtered.accepted(), LiveSyncSessionRegistry.decorated(session));
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
    Set<String> subs = LiveSyncSessionRegistry.subscriptions(session);
    if (subs == null || !subs.contains(topic.canonical())) {
      return;
    }
    String sectionKey = textValue(node, "sectionKey");
    if (sectionKey == null
        || sectionKey.isBlank()
        || sectionKey.length() > MAX_SECTION_KEY_LENGTH) {
      return;
    }
    if (!rateLimiter.allowPresenceFrame(session)) {
      rooms.droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      return;
    }
    boolean mutated;
    switch (type) {
      case "focus", "heartbeat" -> {
        String displayName = LiveSyncSessionRegistry.displayName(session);
        mutated = presenceService.touch(topic.canonical(), sectionKey, userId, displayName);
      }
      case "blur" -> mutated = presenceService.clear(topic.canonical(), sectionKey, userId);
      default -> {
        return;
      }
    }
    if (mutated || "blur".equals(type) || "focus".equals(type)) {
      rooms.broadcastLocalPresenceChange(topic);
    }
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
    LiveSyncSections.Filtered filtered =
        LiveSyncSections.retainAllowed(sections, topic.topicClass());
    reportFilteredSections(topic, filtered, "fan-out");
    if (filtered.accepted().isEmpty()) {
      return;
    }
    rooms.relayChanged(topic, filtered.accepted(), null);
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
    LiveSyncSections.Filtered filtered =
        LiveSyncSections.retainAllowed(sections, topic.topicClass());
    reportFilteredSections(topic, filtered, "server");
    if (filtered.accepted().isEmpty()) {
      return;
    }
    rooms.relayChanged(topic, filtered.accepted(), null);
    fanout.publish(topic.canonical(), filtered.accepted());
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
      @NotNull LiveSyncTopic topic,
      @NotNull LiveSyncSections.Filtered filtered,
      @NotNull String source) {
    if (filtered.rejected() == 0) {
      return;
    }
    rooms.droppedCounter(topic, MetricNames.DROPPED_SECTION_FILTERED).increment();
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
    if (!rateLimiter.allowTopicChanged(topic)) {
      rooms.droppedCounter(topic, MetricNames.DROPPED_TOPIC_THROTTLED).increment();
      return;
    }
    rooms.relayChanged(topic, sections, origin);
    fanout.publish(topic.canonical(), sections);
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
      rooms.broadcastSnapshot(topic);
    }
  }

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
   * Pings every open socket; a socket whose write fails is dropped, and closed sockets are skipped.
   */
  void tickKeepalive() {
    sessions.tickKeepalive();
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
        rooms.broadcastLocalPresenceChange(topic);
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
        rooms.broadcastSnapshot(topic);
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
   * Drops per-topic {@code changed} buckets idle for at least {@link
   * LiveSyncRateLimiter#TOPIC_BUCKET_IDLE_REAP_NANOS}.
   *
   * @param nowNanos the current monotonic clock reading
   */
  void reapIdleTopicBuckets(long nowNanos) {
    rateLimiter.reapIdleTopicBuckets(nowNanos);
  }

  /**
   * The number of live per-topic {@code changed} buckets (test seam for the reaper: proves an idle
   * bucket is dropped).
   *
   * @return the number of per-topic buckets
   */
  int topicBucketCount() {
    return rateLimiter.topicBucketCount();
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
}
