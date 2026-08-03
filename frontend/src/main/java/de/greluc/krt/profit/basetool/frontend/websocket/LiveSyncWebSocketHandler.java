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

import de.greluc.krt.profit.basetool.frontend.logging.LogSafe;
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
 * Generic native-WebSocket relay for the tool-wide live-sync feature (REQ-FE-015, ADR-0094).
 *
 * <p>Generalises the former per-mission presence relay into a topic-room relay: a socket is bound
 * to a {@link LiveSyncTopic} (its {@link LiveSyncTopicClass} fixes the section whitelist and
 * whether it carries editor-presence dots), and the handler fans a client's {@code
 * {"type":"changed", "sections":[…]}} signal out to every <em>other</em> socket in the same room.
 * Only opaque section keys cross the socket — never entity data: each peer re-pulls the affected
 * fragment through its own authenticated, authorization-checked GET, so redaction and access gates
 * re-apply per viewer.
 *
 * <p><b>Topic binding.</b> Every socket is a multiplexed {@code /ws/sync} socket ({@link
 * #ATTR_MULTIPLEXED}): it binds no topic at connect and manages a set of rooms via {@code
 * subscribe} frames — each authorized asynchronously (off the container thread, on the {@code
 * authExecutor}) by {@link LiveSyncSubscriptionAuthorizer} — while {@code changed} and presence
 * frames carry their own {@code topic}. (The one-release per-resource legacy aliases {@code
 * /ws/missions/{id}/presence} and {@code /ws/materialboerse/board}, and the single-topic connect
 * binding they used, were removed in #1236/#1182.) Publishing a {@code changed} frame needs
 * <b>no</b> subscription (the cross-topic case: a requester notifies a staff queue it may not
 * read), only an authenticated socket, a known topic class and the per-session rate limit; a
 * subscribe is what an <em>inbound</em> relay requires. The frame's {@code sections} array is
 * sanitised against the topic class's whitelist before it is relayed.
 *
 * <p><b>Cross-replica fan-out.</b> An accepted {@code changed} frame is relayed to this instance's
 * local room first, then handed to {@link LiveSyncFanout#publish(String, List)} so peer replicas
 * relay it to their local rooms; {@link #deliverFromFanout(String, List)} is the consume-side entry
 * a Redis subscriber calls. Because local relay happens first, a fan-out outage degrades to
 * single-instance behaviour, never worse (ADR-0094).
 *
 * <p><b>Cross-replica presence</b> (ADR-0126, #1237). Editor-presence dots follow the same
 * local-first shape on a second channel, but carry <em>state</em> rather than a signal: every local
 * presence change broadcasts locally and then gossips this instance's complete snapshot for the
 * topic via {@link LiveSyncFanout#publishPresence(String, Map)}, the reaper re-gossips each tracked
 * topic every tick, and {@link #deliverPresenceFromFanout(String, String, Map)} replaces the
 * publishing replica's partition in the presence store and re-broadcasts the merged dots. Full
 * snapshots rather than deltas make the mirror converge after a dropped message with no ordering or
 * acknowledgement assumptions; consume never re-publishes, so replicas cannot echo each other.
 *
 * <p><b>Abuse bounds</b> (F2 / #1243). Publishing needs no subscription, so two levers are capped
 * independently: a <b>per-user socket cap</b> ({@link #MAX_SOCKETS_PER_USER}) bounds how many
 * multiplexed sockets one user may hold (a refused socket is closed with {@link
 * #SOCKET_CAP_EXCEEDED}), and a <b>per-topic token bucket</b> ({@link #TOPIC_CHANGED_BURST} burst /
 * {@code TOPIC_CHANGED_REFILL_PER_SEC}/s, on top of the per-session bucket) bounds a room's
 * aggregate relay + fan-out rate regardless of how many sockets publish to it. Both sit far above
 * any legitimate use, so they only clamp a crafted flood; both degrade to a bounded re-fetch rate,
 * never data loss.
 *
 * <p><b>Per-session frame buckets.</b> All three inbound frame types carry the same per-session
 * token bucket ({@link #allowFrame}): {@code changed} ({@link #CHANGED_BURST}), presence ({@link
 * #PRESENCE_BURST}) and {@code subscribe} ({@link #SUBSCRIBE_BURST}). The subscribe bucket is what
 * bounds the rate at which a socket can submit authorization probes to the {@code authExecutor} —
 * the per-session topic cap does not, because a denied subscribe releases its reserved slot, so a
 * subscribe → deny → subscribe cycle never reaches the cap.
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
  static final int CHANGED_BURST = 40;

  /** Token-bucket refill rate for inbound {@code changed} frames, in tokens per second. */
  private static final double CHANGED_REFILL_PER_SEC = 20.0;

  /**
   * Hard cap on the accepted length of a client-supplied presence {@code sectionKey}. Legitimate
   * keys are short panel identifiers; anything longer is a crafted client trying to bloat the
   * per-topic presence map's memory footprint and is dropped (#1245 presence-WS hardening).
   *
   * <p>Doubles as the truncation bound when a rejected {@code changed}-frame section key is put
   * through {@link LogSafe} for the whitelist-filter DEBUG line: the same "a section key is a short
   * panel identifier" assumption applies, so a longer value is hostile and there is nothing to gain
   * from logging the rest of it.
   */
  private static final int MAX_SECTION_KEY_LENGTH = 64;

  /**
   * Truncation bound for a client-supplied {@code topic} string rendered into a log line via {@link
   * LogSafe}. A canonical topic is a short class prefix plus at most a UUID (well under this), so a
   * longer value is a crafted client and only the head of it is worth keeping.
   */
  private static final int MAX_LOGGED_TOPIC_LENGTH = 64;

  /**
   * Token-bucket capacity for inbound presence control frames ({@code focus} / {@code heartbeat} /
   * {@code blur}) per session. Legitimate presence traffic is sparse — a {@code focus} on entering
   * a panel plus a heartbeat once a minute — so this burst sits far above any human cadence and
   * only bounds a crafted client emitting presence frames in a loop (which each insert a per-topic
   * presence entry and force a full-map snapshot rebuild + broadcast: O(N²) amplification). The
   * {@code changed} path had a bucket; the presence path did not until #1245. Package-private for
   * the test.
   */
  static final int PRESENCE_BURST = 20;

  /** Token-bucket refill rate for inbound presence control frames, in tokens per second. */
  private static final double PRESENCE_REFILL_PER_SEC = 10.0;

  /**
   * Token-bucket capacity for inbound {@code subscribe} frames per session — the third frame type
   * to get the same per-session bucket the {@code changed} and presence paths already had.
   *
   * <p>The per-session topic cap ({@link #MAX_TOPICS_PER_SESSION}) bounds how many rooms a socket
   * <em>holds</em>, but not how many subscribe frames it <em>sends</em>: {@link #completeSubscribe}
   * releases the reserved slot on a deny, so a subscribe → deny → subscribe cycle never reaches the
   * cap and was previously unbounded — and every non-idempotent subscribe submits an authorization
   * probe to {@link #authExecutor}. This bucket bounds that submission rate, and with it the rate
   * at which the saturation branch of {@link #handleSubscribe} can author a log line.
   *
   * <p>Sized at 1.5× the topic cap so a page that legitimately subscribes to the maximum number of
   * rooms the instant its socket opens never trips it; a reconnect re-subscribes on a
   * <em>fresh</em> socket with a fresh, full bucket, so the reconnect storm is unaffected either.
   * Package-private for the test.
   */
  static final int SUBSCRIBE_BURST = 24;

  /**
   * Token-bucket refill rate for inbound {@code subscribe} frames, in tokens per second.
   * Deliberately an order of magnitude below the {@code changed} / presence refills: a socket
   * subscribes to each of its rooms once and then has essentially no legitimate need for further
   * subscribe frames, so the sustained allowance only has to cover the occasional re-subscribe, not
   * a cadence.
   */
  private static final double SUBSCRIBE_REFILL_PER_SEC = 1.0;

  /**
   * Per-<em>topic</em> token-bucket capacity for accepted {@code changed} frames (F2 / #1243). The
   * per-session bucket ({@link #CHANGED_BURST}) bounds one socket; this second bucket, keyed by the
   * canonical topic, bounds a room's <em>aggregate</em> relay + fan-out rate across <b>all</b>
   * publishers, so no set of sockets can amplify one (chiefly global) room's fragment-refetch
   * fan-out. Sized far above any realistic mutation cadence for a room — even a busy queue or bank
   * sees a handful of writes/s, well under this — so a legitimate 200-user room never trips it; it
   * only clamps a crafted flood. Package-private for the test.
   */
  static final int TOPIC_CHANGED_BURST = 200;

  /** Per-topic refill rate for accepted {@code changed} frames, in tokens per second (F2/#1243). */
  private static final double TOPIC_CHANGED_REFILL_PER_SEC = 100.0;

  /**
   * Idle age past which the reaper drops a per-topic {@code changed} bucket (F2/#1243). A bucket
   * untouched this long has fully refilled (30/s ≫ 60 in 60 s), so dropping it and recreating a
   * fresh full bucket on the next publish is behaviourally identical — this just bounds the
   * per-topic map to the set of recently-active rooms instead of accreting one entry per distinct
   * mission/operation/order ever edited. Package-private for the test.
   */
  static final long TOPIC_BUCKET_IDLE_REAP_NANOS = TimeUnit.SECONDS.toNanos(60);

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
   * Session-attribute key ({@code Set<String>}) holding the caller's authorities captured at
   * handshake, used to authorize a subscribe to a locally role-gated global room (the {@code bank}
   * staff and {@code orgunit-bank} rooms) without a backend call. Public so the interceptor can
   * populate it.
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
   * Hard cap on concurrent multiplexed {@code /ws/sync} sockets one user (Keycloak {@code sub}) may
   * hold (F2 / #1243). One tab opens exactly one such socket (the lazy singleton in {@code
   * krt-live-sync.js}), so this sits far above any legitimate multi-tab use — with headroom for the
   * brief overlap while a reconnecting tab's old socket is still closing — yet bounds the {@code K}
   * in the {@code K sockets × publish-rate × room-viewers} amplification lever. A refused socket is
   * closed with {@link #SOCKET_CAP_EXCEEDED}. Package-private for the test.
   */
  static final int MAX_SOCKETS_PER_USER = 20;

  /**
   * Application-defined WebSocket close status ({@code 4029}) for a socket refused by the per-user
   * cap ({@link #MAX_SOCKETS_PER_USER}). The client ({@code krt-live-sync.js}) recognises this code
   * and backs its reconnect off to the maximum interval rather than hammering, recovering quietly
   * once another tab closes and frees a slot.
   */
  static final CloseStatus SOCKET_CAP_EXCEEDED = new CloseStatus(4029, "socket cap exceeded");

  private static final String ATTR_USER_ID = "livesync.userId";

  /**
   * Session-attribute key ({@link Boolean}) marking a socket that incremented its user's
   * live-socket count, so the close path decrements exactly once. A socket refused by the per-user
   * cap never sets it (it decrements inline), so its later {@code afterConnectionClosed} does not
   * double-decrement.
   */
  private static final String ATTR_USER_COUNTED = "livesync.userCounted";

  private static final String ATTR_DISPLAY_NAME = "livesync.displayName";
  private static final String ATTR_CHANGED_RATE = "livesync.changedRate";
  private static final String ATTR_PRESENCE_RATE = "livesync.presenceRate";
  private static final String ATTR_SUBSCRIBE_RATE = "livesync.subscribeRate";

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

  /**
   * Monotonic nanosecond clock backing every token-bucket refill and the idle-bucket reaper.
   * Production wires {@link System#nanoTime()}; the throttle tests inject a frozen/steppable
   * supplier so a burst's refill is deterministic (real wall-clock elapsed during the emit loop
   * otherwise refills a few tokens and makes an exact per-topic-burst assertion flaky under CI
   * load).
   */
  private final LongSupplier nanoClock;

  private final Map<String, Set<WebSocketSession>> sessionsByTopic = new ConcurrentHashMap<>();

  /**
   * Live multiplexed-socket count per user (Keycloak {@code sub}), backing the per-user socket cap
   * (F2 / #1243). Incremented atomically at connect, decremented at close; an entry is removed when
   * it reaches zero, so the map is bounded by the number of currently connected users.
   */
  private final Map<String, Integer> socketsByUser = new ConcurrentHashMap<>();

  /**
   * Per-topic {@code changed}-frame token buckets, backing the per-topic publish throttle (F2 /
   * #1243). Keyed by canonical topic; each bucket serialises its own token math under its instance
   * monitor. Buckets are reaped by {@link #reapIdleTopicBuckets(long)} so the map stays bounded to
   * recently-active rooms.
   */
  private final Map<String, TopicRateState> changedRateByTopic = new ConcurrentHashMap<>();

  /**
   * Builds the handler. Binds the {@code basetool_presence_ws_sessions} gauge (live sockets summed
   * across all rooms) and, per topic class, one {@code
   * basetool_livesync_subscriptions{topic_class}} gauge (sockets in that class) plus one {@code
   * basetool_livesync_peer_rooms{topic_class}} gauge (rooms of that class holding two or more
   * sockets); the reaper starts ticking immediately.
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
   * Test seam of {@link #LiveSyncWebSocketHandler(LiveSyncPresenceService, LiveSyncFanout,
   * ObjectMapper, MeterRegistry, LiveSyncSubscriptionAuthorizer, Executor)} that additionally
   * injects the monotonic {@code nanoClock} backing the token-bucket refills and idle-bucket
   * reaper, so a throttle test can freeze time and assert an exact per-topic-burst bound rather
   * than tolerating wall-clock refill. Behaviour is otherwise identical.
   *
   * @param presenceService in-memory editor-presence store
   * @param fanout cross-replica fan-out seam (no-op when single-instance)
   * @param objectMapper Jackson mapper for the minimal {@code {type, sections}} wire format
   * @param meterRegistry the Micrometer registry the gauges and relay counters bind to
   * @param authorizer authorizes a multiplexed {@code /ws/sync} subscribe to a resource topic
   * @param authExecutor executor that runs subscribe-authorization probes off the WebSocket
   *     container thread; a {@link RejectedExecutionException} (saturation) fails the subscribe
   *     open
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
   * Counts the rooms of a given topic class that currently hold two or more sockets (backs the
   * per-class {@code basetool_livesync_peer_rooms} gauge, #1238).
   *
   * <p>Deliberately distinct from {@link #subscriptionCount(LiveSyncTopicClass)}: that sums sockets
   * across the class, which cannot tell two peers sharing one room (peer-sync live, a {@code
   * changed} relay is possible) from two separate single-viewer rooms (peer-sync inert, {@link
   * #relayLocal} skips the origin so nothing can ever be relayed). Only this count makes a {@code
   * changed}-frame flatline interpretable.
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
   * Registers a freshly connected multiplexed {@code /ws/sync} socket. It joins no room at connect
   * — its rooms are built by later {@code subscribe} frames — so this only resolves the principal,
   * enforces the per-user socket cap (F2 / #1243), wraps the socket in its backpressure decorator
   * and seeds an empty subscription set. A socket with no resolvable principal, or one that would
   * put the user over {@link #MAX_SOCKETS_PER_USER}, is refused.
   *
   * @param session the freshly opened session
   */
  @Override
  public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
    Principal principal = session.getPrincipal();
    String userId = principal == null ? null : resolveUserId(principal);
    if (userId == null) {
      log.debug("Live-sync /ws/sync socket refused (no principal)");
      session.close(CloseStatus.NOT_ACCEPTABLE);
      return;
    }
    if (!tryAcquireUserSocket(userId)) {
      // Per-user socket cap: bound the number of concurrent /ws/sync sockets one user holds so the
      // K in the changed-publish amplification lever (K sockets × rate × room viewers) is bounded.
      // The count was undone in tryAcquireUserSocket, and ATTR_USER_COUNTED is left unset so
      // the ensuing afterConnectionClosed does not decrement again.
      log.debug("Live-sync /ws/sync socket refused (per-user cap {})", MAX_SOCKETS_PER_USER);
      socketRejectedCounter(MetricNames.SOCKET_REJECTED_USER_CAP).increment();
      session.close(SOCKET_CAP_EXCEEDED);
      return;
    }
    session.getAttributes().put(ATTR_USER_COUNTED, Boolean.TRUE);
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
   * Dispatches one client message on a multiplexed {@code /ws/sync} socket, resolving the topic per
   * frame: {@code subscribe} joins an authorized room, {@code changed} publishes to a topic (no
   * subscription required), and presence frames touch a subscribed presence room. Unknown types are
   * silently ignored to keep the wire format forward-compatible.
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
      default -> {
        // Unknown type: ignore to keep the wire format forward-compatible.
      }
    }
  }

  /**
   * Cleans up a closed multiplexed socket — leaves every room it subscribed to and, for a
   * presence-enabled room where the user has no other live session, drops their presence and
   * broadcasts the resulting snapshot.
   *
   * @param session the closing session
   * @param status close reason (unused; logged for diagnostics)
   */
  @Override
  public void afterConnectionClosed(
      @NotNull WebSocketSession session, @NotNull CloseStatus status) {
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    // Release the per-user socket slot exactly once (F2 / #1243) — only for a socket that actually
    // acquired one (a cap-refused socket already released it inline and left ATTR_USER_COUNTED
    // unset). Done before the subscription-set check so it runs even for a socket closed between
    // establish and its first subscribe.
    if (userId != null && Boolean.TRUE.equals(session.getAttributes().get(ATTR_USER_COUNTED))) {
      releaseUserSocket(userId);
    }
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
          broadcastLocalPresenceChange(topic);
        }
      }
    }
  }

  /**
   * Handles a {@code subscribe} frame: validates the topic, applies the per-session subscribe rate
   * limit, enforces the per-session topic cap and idempotency, then authorizes the subscribe
   * asynchronously on {@link #authExecutor} (so the container thread never blocks on a backend
   * probe). A rejected submission (executor saturated) fails the subscribe open. The room is joined
   * only once {@link #completeSubscribe} confirms an allow.
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
      // #1239: an unknown/unparseable subscribe topic is the signature of a client/server
      // topic-vocabulary skew — count it so the drift is visible. No topic_class tag: the topic did
      // not parse, so it belongs to no class (a dedicated unlabelled meter, not a topic_class
      // sentinel — REQ-OBS-011).
      meterRegistry.counter(MetricNames.LIVESYNC_INVALID_TOPIC).increment();
      sendControlFrame(session, "denied", rawTopic);
      return;
    }
    Set<String> subs = subscriptions(session);
    if (subs == null) {
      return;
    }
    // Rate-limit the subscribe path per session with the same bucket primitive the changed and
    // presence paths use — the topic cap alone does not bound it, because completeSubscribe
    // releases the reserved slot on a deny (see SUBSCRIBE_BURST). Dropped silently, never answered
    // with a `denied` control frame: the client treats a deny as terminal for the topic, so
    // answering a throttled frame that way would turn a transient burst into a permanently dead
    // room. The client's next reconnect re-subscribes against a fresh bucket.
    if (!allowSubscribeFrame(session)) {
      droppedCounter(topic, MetricNames.DROPPED_THROTTLED).increment();
      log.debug(
          "Live-sync subscribe to topic {} dropped (per-session throttle)", topic.canonical());
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
    Set<String> authorities = capturedAuthorities(session);
    try {
      authExecutor.execute(() -> authorizeAndRegister(session, topic, token, pin, authorities));
    } catch (RejectedExecutionException e) {
      // Auth executor saturated: indeterminate verdict. Fail in the class's direction — open for a
      // non-presence class (opaque keys only; each fragment re-pull re-authorizes), closed for a
      // presence class so the editor-identity snapshot is never leaked on an unverified subscribe
      // (F1).
      LiveSyncSubscriptionAuthorizer.Decision verdict =
          LiveSyncSubscriptionAuthorizer.failOpen(topic);
      droppedCounter(topic, MetricNames.DROPPED_AUTHORIZE_SATURATED).increment();
      // WARN, not DEBUG. The earlier justification here — "a client cannot provoke it" — was wrong:
      // reaching this branch needs a saturated executor AND an inbound subscribe frame, and the
      // subscribe path used to be the one frame type with no rate limit (the topic cap does not
      // bound it, because a denied subscribe releases its reserved slot). A crafted client could
      // therefore cycle subscribe → deny → subscribe, push the queue to rejection and author these
      // lines at will. It is bucketed now, exactly like the changed and presence paths
      // (SUBSCRIBE_BURST / SUBSCRIBE_REFILL_PER_SEC), and the per-user socket cap
      // (MAX_SOCKETS_PER_USER) bounds how many buckets one user can hold — so the sustained line
      // rate a single caller can drive is a small constant per second, and only while the executor
      // is already saturated. That is a genuine infrastructure symptom, not a flood vector, and it
      // matters: saturation silently degrades authorization for every subscribe landing on this
      // instance, and on a presence class it fails closed, costing that tab live updates for the
      // topic until it reconnects. The deny counter deliberately carries no `saturated` reason
      // value — the authorize_saturated relay-drop series above is that signal.
      log.warn(
          "Live-sync subscribe authorization for topic {} was not scheduled (auth executor"
              + " saturated); resolved as {}",
          topic.canonical(),
          verdict);
      completeSubscribe(session, topic, verdict);
    }
  }

  /**
   * Runs the subscribe-authorization probe (on {@link #authExecutor}) and applies its verdict. A
   * probe that throws unexpectedly is an indeterminate verdict, resolved in the class's fail
   * direction ({@link LiveSyncSubscriptionAuthorizer#failOpen(LiveSyncTopic)}) — open for a
   * non-presence class, closed for a presence one (F1) — consistent with the authorizer's own
   * transient-error handling.
   *
   * <p>This is where a completed probe's indeterminate <em>fail-closed</em> verdict is reported at
   * WARN (the executor-saturation branch of {@link #handleSubscribe} reports its own, and no other
   * path logs one): the refusal costs that tab every peer update for the topic until it reconnects,
   * and the client retries such a deny exactly <em>once</em> on its next reconnect — so a backend
   * blip outlasting that one retry still leaves the tab on the manual-refresh pill. That outcome is
   * backend-triggered and bounded to one line per tab per topic, so it is not a log-flood vector —
   * unlike an explicit permission deny, which is a routine, user-triggerable verdict and stays at
   * DEBUG in the authorizer.
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
        // The authorizer logged the underlying transient (status code / exception) at DEBUG as
        // probe detail; this is the single line stating that it became a user-visible, terminal
        // refusal — the level the outcome warrants (REQ-OBS-001).
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
   * Finalises a subscribe: on either deny flavour it drops the reserved slot and refuses — with the
   * flavour's bounded {@code reason} on the {@code denied} frame, so the client can retry a
   * fail-closed indeterminate refusal once and treat an authorization refusal as terminal; on ALLOW
   * it joins the room (skipping a socket that closed while the probe ran), acks {@code subscribed}
   * and sends the initial presence snapshot for a presence-enabled class.
   *
   * <p>Dropping the reserved slot is why the per-session topic cap cannot bound the subscribe rate,
   * and therefore why {@link #handleSubscribe} rate-limits the frame itself.
   *
   * <p>Deliberately emits no log line of its own — every refusal is already reported by whoever
   * produced the verdict (the authorizer at DEBUG for an explicit deny, {@link
   * #authorizeAndRegister} at WARN for a fail-closed indeterminate one, {@link #handleSubscribe} at
   * WARN for executor saturation), so routing the logging through here as well would double-log the
   * same failure (REQ-OBS-001).
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
      // The refusal flavour rides the wire frame, not just the metric: an indeterminate (fail-
      // closed) deny is an availability symptom the client retries once on its next reconnect,
      // while an authz deny stays terminal. Same bounded vocabulary as the deny metric's tag.
      String reason = denyReason(decision);
      sendControlFrame(session, "denied", topic.canonical(), reason);
      subscribeCounter(topic, MetricNames.OUTCOME_DENIED, reason).increment();
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
    subscribeCounter(topic, MetricNames.OUTCOME_ALLOWED, MetricNames.REASON_NONE).increment();
  }

  /**
   * Maps a refusal to its bounded {@code reason} value, so a permission verdict and a fail-closed
   * availability symptom stop sharing one indistinguishable {@code outcome=denied} series — and, on
   * the wire, one indistinguishable {@code denied} control frame. The same value is used for both:
   * a closed two-element vocabulary that is safe as a metric tag (REQ-OBS-006) and stable enough
   * for the client to branch on.
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
   * Handles a {@code changed} frame on a multiplexed socket. Publishing needs no subscription —
   * only an authenticated socket, a known topic class, the per-session rate limit and the class's
   * section whitelist — so this resolves the frame's own topic, sanitises, relays locally
   * (excluding the origin) and hands the signal to the cross-replica fan-out.
   *
   * @param session the publishing session
   * @param node the parsed {@code changed} frame
   */
  private void handleMultiplexedChanged(@NotNull WebSocketSession session, @NotNull JsonNode node) {
    String rawTopic = textValue(node, "topic");
    LiveSyncTopic topic = LiveSyncTopic.parse(rawTopic);
    if (topic == null) {
      // The publish-side face of the client/server topic-vocabulary skew the subscribe path counts:
      // the acting client believes in a topic this server does not know, so its peers never hear of
      // the change (REQ-FE-010) and the drop is otherwise completely silent. Not counted — the
      // relay-drop meter requires a `topic_class` an unparseable topic has none of, and the
      // unlabelled invalid-topic meter is defined for the subscribe path, so widening it here would
      // change what its dashboards and alerts mean. DEBUG because the frame is client-supplied and
      // therefore an attacker-triggerable flood at INFO/WARN.
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
   * Handles a presence frame ({@code focus}/{@code blur}/{@code heartbeat}) on a multiplexed
   * socket. Presence is only tracked for a presence-enabled class and only for a room the socket is
   * actually subscribed to; a {@code focus}/{@code heartbeat} touches the editor entry and a {@code
   * blur} clears it, and any state change fans a fresh snapshot to the room.
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
    // Rate-limit the presence path per session (#1245) — see allowPresenceFrame for the rationale.
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
   * Sends a tiny control frame ({@code {"type":…,"topic":…}}) — a {@code subscribed} ack or a
   * {@code denied} refusal — to a multiplexed socket, carrying no {@code reason}. Used for the two
   * refusals decided before an authorization verdict exists (an unparseable topic, the per-session
   * topic cap): both are terminal for the client by nature, and an absent {@code reason} is exactly
   * what its terminal default keys off.
   *
   * @param session the target session (its decorator is resolved and written to)
   * @param type the control-frame type ({@code subscribed} / {@code denied})
   * @param topicString the topic the control frame refers to (echoed as-is; may be an unparseable
   *     value for a denied unknown-topic subscribe)
   */
  private void sendControlFrame(
      @NotNull WebSocketSession session, @NotNull String type, String topicString) {
    sendControlFrame(session, type, topicString, null);
  }

  /**
   * Sends a tiny control frame to a multiplexed socket, tolerating a closed/broken peer. Control
   * frames are low-volume, so no relay-drop metric is recorded here.
   *
   * <p>A {@code denied} frame carries the refusal's {@code reason} when one is known, so the client
   * can tell an <em>authorization</em> verdict ({@link MetricNames#SUBSCRIBE_DENY_AUTHZ} — the
   * caller may genuinely not read this room, so the refusal is terminal) from a <em>fail-closed
   * indeterminate</em> one ({@link MetricNames#SUBSCRIBE_DENY_INDETERMINATE} — an availability
   * symptom of a backend blip, which {@code krt-live-sync.js} retries exactly once on its next
   * reconnect). Before the tag rode along, both flavours were the same opaque {@code denied} frame,
   * so a 30-second blip stripped live sync from that tab permanently. The value comes from {@link
   * #denyReason(LiveSyncSubscriptionAuthorizer.Decision)}, i.e. the same closed, bounded vocabulary
   * the deny metric's {@code reason} tag uses — never free text.
   *
   * @param session the target session (its decorator is resolved and written to)
   * @param type the control-frame type ({@code subscribed} / {@code denied})
   * @param topicString the topic the control frame refers to (echoed as-is; may be an unparseable
   *     value for a denied unknown-topic subscribe)
   * @param reason the bounded refusal reason to carry, or {@code null} to omit the field
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
  @SuppressWarnings("unchecked")
  private static Set<String> subscriptions(@NotNull WebSocketSession session) {
    // Object -> generic cast is unavoidable reading the WebSocket attribute map
    // (Map<String,Object>);
    // ATTR_SUBSCRIPTIONS is only ever written as a ConcurrentHashMap keySet of topic strings.
    Object value = session.getAttributes().get(ATTR_SUBSCRIPTIONS);
    return value instanceof Set ? (Set<String>) value : null;
  }

  /**
   * Resolves the authorities captured at handshake ({@link #ATTR_AUTHORITIES}) as a {@code
   * Set<String>}, or {@code null} when none were captured — then a locally role-gated subscribe
   * fails open. Rebuilt (rather than cast) so no unchecked cast is needed reading the untyped
   * attribute map.
   *
   * @param session the session
   * @return the captured authority names, or {@code null}
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
   * Counter {@code basetool_livesync_subscribe_total{topic_class, outcome, reason}} for a subscribe
   * verdict. Micrometer requires a uniform tag-key set per meter name, so the {@code allowed} row
   * carries {@link MetricNames#REASON_NONE} rather than omitting the tag.
   *
   * @param topic the subscribed topic (its class tags the metric)
   * @param outcome {@link MetricNames#OUTCOME_ALLOWED} or {@link MetricNames#OUTCOME_DENIED}
   * @param reason the bounded deny reason ({@link MetricNames#SUBSCRIBE_DENY_AUTHZ} / {@link
   *     MetricNames#SUBSCRIBE_DENY_INDETERMINATE}), or {@link MetricNames#REASON_NONE} on an allow
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
   * Relays a {@code changed} signal that arrived from a peer replica via the fan-out (ADR-0094) to
   * this instance's local room. No origin session is excluded — the originator lives on another
   * replica — and nothing is re-published (that would loop).
   *
   * <p>The section keys are re-validated against the topic class's whitelist here too. The
   * publishing replica already sanitised them before it put the frame on Redis, so this is
   * defense-in-depth — it keeps the relay robust to a malformed or older-version peer, or a
   * tampered Redis payload, matching the whitelist-on-ingest posture of the two client/server
   * publish paths ({@link #handleMultiplexedChanged}, {@link #publishFromServer}).
   *
   * @param canonicalTopic the canonical topic string
   * @param sections the section keys from the peer replica (re-filtered to the class whitelist)
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
   * Publishes a <em>server-originated</em> {@code changed} signal (REQ-FE-015, ADR-0094): relays it
   * to this instance's local room (no origin to exclude — there is no acting socket) and hands it
   * to the cross-replica fan-out. This is the seam a controller uses when the mutating actor has no
   * socket to publish from — chiefly an <b>anonymous guest order create</b>, which must still poke
   * the staff {@code orders} queue every logged-in viewer is subscribed to. The sections are
   * validated against the topic class's whitelist just like a client frame.
   *
   * @param canonicalTopic the canonical topic string (unknown topics are ignored)
   * @param sections the section keys to relay (filtered to the class whitelist)
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
   * Keeps only the section keys that belong to a class's whitelist, de-duplicated and capped — the
   * {@link List}-input counterpart of {@link #sanitiseSections(JsonNode, LiveSyncTopicClass)} for a
   * server-originated publish or a peer-replica delivery.
   *
   * @param sections the raw section keys
   * @param topicClass the class whose whitelist applies
   * @return the accepted keys plus the rejection evidence {@link #reportFilteredSections} needs
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
   * Reports the section keys one {@code changed} frame lost to the topic class's whitelist — the
   * REQ-FE-010 defect class made observable.
   *
   * <p>An acting client (or a server publish, or a peer replica) broadcasting a key this relay's
   * accept-list does not know leaves every peer's matching panel stale, with no error either side:
   * {@code relay_frames_total{type="changed"}} keeps climbing while nothing records the key was
   * filtered. This counts exactly that, once per frame (not once per key, which would make one
   * crafted frame worth {@link #MAX_CHANGED_SECTIONS} increments), and logs one DEBUG line carrying
   * the count plus the first rejected key as a representative sample.
   *
   * <p>DEBUG is mandatory here and the level is a contract: on the client path the frame — and the
   * key inside it — is entirely client-supplied, so INFO or WARN would be an attacker-triggerable
   * log flood. The key is rendered through {@link LogSafe} (control characters stripped, truncated
   * at {@link #MAX_SECTION_KEY_LENGTH}) because it is free text on its way to a logger, and it is
   * never used as a metric tag value — that would be unbounded, client-controlled cardinality
   * (REQ-OBS-006).
   *
   * @param topic the topic the frame targeted (its class tags the drop counter)
   * @param filtered the filter outcome; a zero rejection count reports nothing at all
   * @param source which publish path produced the frame, for the log line only (a fixed literal —
   *     {@code client} / {@code server} / {@code fan-out})
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
   * Outcome of filtering one {@code changed} frame's section keys against a topic class's
   * whitelist: what survived, plus the evidence {@link #reportFilteredSections} needs to make the
   * silent drop visible.
   *
   * <p>Only keys the whitelist refused count as rejected. A duplicate is collapsed rather than
   * rejected (the client's vocabulary is fine, it just said the same thing twice) and a non-string
   * array entry is malformed input rather than vocabulary skew, so neither inflates the count.
   *
   * @param accepted the accepted, de-duplicated, {@link #MAX_CHANGED_SECTIONS}-capped keys
   * @param rejected how many keys the class whitelist refused
   * @param firstRejectedKey the first refused key, kept as the single representative for the
   *     one-line-per-frame DEBUG report, or {@code null} when nothing was refused
   */
  private record FilteredSections(List<String> accepted, int rejected, String firstRejectedKey) {}

  /**
   * Reaper tick — three jobs, all on a single daemon thread; any thrown exception is logged and
   * swallowed so a transient failure does not kill the reaper.
   *
   * <ol>
   *   <li>drop expired local presence entries and broadcast (plus gossip) the shrunken snapshot to
   *       rooms that lost at least one entry;
   *   <li>drop peer partitions whose replica has gone quiet past {@link
   *       LiveSyncPresenceService#REMOTE_PARTITION_TTL} and broadcast those rooms locally — a
   *       <em>local</em> consequence of remote state, so it is deliberately not re-gossiped;
   *   <li>re-gossip this instance's presence snapshot for every still-tracked topic (ADR-0126).
   * </ol>
   *
   * <p>The periodic re-gossip is what makes the cross-replica mirror self-healing rather than
   * delta-ordered: a dropped message, a Redis blip or a replica that started after the fact all
   * converge within one tick, with no delete frames, no acknowledgements and no assumption that
   * messages arrive in order. It costs one small message per <em>actively edited</em> topic per
   * tick — {@link LiveSyncPresenceService#trackedTopics()} is empty whenever nobody has a mission
   * panel focused, which is the overwhelmingly common case.
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
        // Gossips even when the topic just lost its last local editor and is therefore no longer
        // tracked: that empty snapshot is exactly what drops this instance's partition on the peers
        // immediately, instead of leaving decayed dots up for a full REMOTE_PARTITION_TTL.
        broadcastLocalPresenceChange(topic);
      }
    }
    return uniqueTopics;
  }

  /**
   * Drops peer partitions that have not been re-gossiped within {@link
   * LiveSyncPresenceService#REMOTE_PARTITION_TTL} — a replica that crashed, was scaled away or lost
   * Redis — and broadcasts the shrunken snapshot to this instance's rooms. Nothing is published:
   * the expiry is a purely local conclusion about a silent peer, and every other replica reaches it
   * independently on its own tick.
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
   * Sanitises an inbound {@code sections} array against a topic class's whitelist: non-string
   * entries and keys outside {@link LiveSyncTopicClass#allowedSections()} are dropped, duplicates
   * are collapsed, and the count is capped at {@link #MAX_CHANGED_SECTIONS}. This is what stops a
   * client injecting an arbitrary fetch target or amplifying one frame into an unbounded fan-out.
   *
   * <p>Rejected keys are not swallowed: the returned {@link FilteredSections} carries the count and
   * a sample so {@link #reportFilteredSections} can count and log the drop, which is what turns a
   * client/relay vocabulary skew from an invisible stale panel into an observable signal
   * (REQ-FE-010).
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

  /**
   * Applies a peer replica's gossiped editor-presence snapshot (ADR-0126) — the presence-channel
   * counterpart of {@link #deliverFromFanout(String, List)} — and re-broadcasts the merged dots to
   * this instance's room when the merged view actually changed. Nothing is re-published, which is
   * what keeps two replicas from echoing each other's state forever.
   *
   * <p>The payload is re-validated here, not trusted: the topic must parse to a
   * <em>presence-enabled</em> class (so a peer can never open a presence surface on a class that
   * has none), and section keys are held to the same shape bound as an inbound client frame. The
   * publishing replica already applied both, so this is defense-in-depth against a malformed or
   * older-version peer or a tampered Redis payload — the same posture {@link
   * #deliverFromFanout(String, List)} takes on the changed channel.
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
   * Broadcasts the merged presence snapshot for a topic to this instance's room <em>and</em>
   * gossips this instance's own half to peer replicas (ADR-0126). Called from every path that
   * mutates local presence — {@code focus}/{@code blur}, a socket close, a heartbeat-TTL reap — so
   * a dot appears and disappears on every replica at the same time rather than within the next
   * gossip tick.
   *
   * <p>The peer-driven path ({@link #deliverPresenceFromFanout(String, String, Map)}) deliberately
   * calls {@link #broadcastSnapshot(LiveSyncTopic)} instead: re-publishing on consume would make
   * two replicas echo each other indefinitely.
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
   * Per-session token-bucket rate limit on inbound {@code changed} frames. Consumes and returns
   * {@code true} when a token is available, or returns {@code false} (dropping the frame) once the
   * session exceeds {@link #CHANGED_BURST} frames refilled at {@link #CHANGED_REFILL_PER_SEC}/s.
   *
   * @param session the session that sent the frame
   * @return {@code true} to relay the frame, {@code false} to drop it as throttled
   */
  private boolean allowChangedFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_CHANGED_RATE, CHANGED_BURST, CHANGED_REFILL_PER_SEC);
  }

  /**
   * Per-session token-bucket rate limit on inbound presence control frames ({@code focus} / {@code
   * heartbeat} / {@code blur}), mirroring {@link #allowChangedFrame} (#1245). Bounds the per-topic
   * presence-map growth rate and the snapshot-broadcast amplification a crafted client could drive
   * by looping {@code focus} frames with unique section keys; {@link
   * LiveSyncPresenceService#MAX_SECTIONS_PER_TOPIC} bounds the absolute map size, this bounds the
   * rate.
   *
   * @param session the session that sent the frame
   * @return {@code true} to process the frame, {@code false} to drop it as throttled
   */
  private boolean allowPresenceFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_PRESENCE_RATE, PRESENCE_BURST, PRESENCE_REFILL_PER_SEC);
  }

  /**
   * Per-session token-bucket rate limit on inbound {@code subscribe} frames, the third user of
   * {@link #allowFrame} alongside {@link #allowChangedFrame} and {@link #allowPresenceFrame}.
   * Bounds the rate at which one socket can submit authorization probes to {@link #authExecutor} —
   * the per-session topic cap cannot, because {@link #completeSubscribe} releases the reserved slot
   * on a deny, so a subscribe → deny → subscribe cycle stays below the cap forever (see {@link
   * #SUBSCRIBE_BURST}).
   *
   * @param session the session that sent the frame
   * @return {@code true} to process the subscribe, {@code false} to drop it as throttled
   */
  private boolean allowSubscribeFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_SUBSCRIBE_RATE, SUBSCRIBE_BURST, SUBSCRIBE_REFILL_PER_SEC);
  }

  /**
   * Shared token-bucket primitive backing {@link #allowChangedFrame}, {@link #allowPresenceFrame}
   * and {@link #allowSubscribeFrame} — every inbound frame type goes through this one
   * implementation, so a new frame type gets the same bounded behaviour rather than a fourth
   * hand-rolled variant. Consumes and returns {@code true} when a token is available, or {@code
   * false} once the session exceeds {@code burst} frames refilled at {@code refillPerSec}/s. Frames
   * from one session are delivered serially by the container, so the unsynchronised bucket state
   * held in the session attributes under {@code attrKey} needs no locking.
   *
   * @param session the session that sent the frame
   * @param attrKey the session-attribute key holding this bucket's state
   * @param burst the bucket capacity (maximum tokens)
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
   * Relays a sanitised {@code changed} frame through the per-<em>topic</em> throttle (F2 / #1243):
   * consumes a token from the topic's bucket and, on success, relays to the local room (excluding
   * {@code origin}) and hands the frame to the cross-replica fan-out; on exhaustion counts a {@link
   * MetricNames#DROPPED_TOPIC_THROTTLED} drop and relays nothing. This is the second, room-scoped
   * gate after the per-session bucket: it bounds a room's aggregate relay + fan-out rate across all
   * publishers, so no set of sockets can amplify one room's fragment-refetch fan-out. Server-side
   * publishes ({@link #publishFromServer}) and peer-replica deliveries ({@link #deliverFromFanout})
   * deliberately bypass this gate — the former is trusted and request-rate-limited upstream, the
   * latter was already accepted (and throttled) on its originating replica.
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
   * Per-topic token-bucket rate limit on accepted {@code changed} frames (F2 / #1243). Unlike the
   * per-session bucket in {@link #allowChangedFrame} (touched only by that session's delivery
   * thread), one topic bucket is hit concurrently by every session publishing to the room, so its
   * token math runs under the bucket instance's monitor; {@code computeIfAbsent} atomically
   * gets-or-creates the shared instance. A bucket the reaper drops while idle simply gets recreated
   * full on the next publish — the correct idle state — so the reap never mis-throttles.
   *
   * @param topic the topic the frame targets
   * @return {@code true} to relay the frame, {@code false} to drop it as per-topic throttled
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
   * Drops per-topic {@code changed} buckets untouched for at least {@link
   * #TOPIC_BUCKET_IDLE_REAP_NANOS}, keeping {@link #changedRateByTopic} bounded to recently-active
   * rooms rather than accreting one entry per distinct topic ever published to. An idle bucket has
   * fully refilled, so removing it (and recreating it full on the next publish) is behaviourally
   * identical; an actively-used bucket's monitor serialises against the token math, so its
   * just-updated {@code lastRefillNanos} keeps it. Package-private and clock-parameterised so the
   * test can force reaping deterministically.
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
   * Atomically claims a per-user socket slot (F2 / #1243): increments the user's live-socket count
   * and, if that would exceed {@link #MAX_SOCKETS_PER_USER}, undoes the increment and refuses.
   *
   * @param userId the connecting user's stable id (Keycloak {@code sub})
   * @return {@code true} if a slot was claimed, {@code false} if the user is already at the cap
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
   * Releases a per-user socket slot (F2 / #1243), removing the entry when the user's last socket
   * closes so the map stays bounded to currently-connected users.
   *
   * @param userId the closing socket owner's stable id
   */
  private void releaseUserSocket(@NotNull String userId) {
    socketsByUser.compute(
        userId, (ignored, count) -> (count == null || count <= 1) ? null : count - 1);
  }

  /**
   * Counter {@code basetool_livesync_socket_rejected_total{reason}} for a {@code /ws/sync} socket
   * refused at connect. Carries no {@code topic_class} — no topic at socket-establish time.
   *
   * @param reason the refusal reason (a bounded literal, e.g. {@link
   *     MetricNames#SOCKET_REJECTED_USER_CAP})
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

  /**
   * Mutable per-topic token-bucket state for the per-topic {@code changed}-frame throttle (F2 /
   * #1243). Distinct from {@link ChangedRateState} because one instance is shared across every
   * session publishing to the room and is therefore mutated under its own monitor (see {@link
   * #allowTopicChanged} / {@link #reapIdleTopicBuckets}) — a separate type keeps guarded-access
   * discipline uniform and self-documenting.
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
