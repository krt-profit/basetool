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

package de.greluc.krt.profit.basetool.frontend.metrics;

/**
 * Single source of truth for the frontend {@code basetool_*} business-metric names, tag keys and
 * bounded tag values (REQ-OBS-011).
 *
 * <p>Meter names use Micrometer's dotted convention; the Prometheus scrape renders each dot as an
 * underscore and appends the type suffix. Every tag value comes from a bounded, enumerable set
 * (REQ-OBS-006) — in particular the backend-client error {@code reason} is derived <em>locally</em>
 * from the failure branch, never from the backend's response body code, so an unexpected backend
 * code can never inflate the metric's cardinality.
 */
public final class MetricNames {

  /**
   * Gauge {@code basetool_mission_presence_missions} — live-sync topics with a live editor
   * (frontend). Name legacy-pinned: presence is mission-only at ship time, so the value is
   * unchanged and the existing dashboard panel keeps meaning (REQ-OBS-011 forbids a rename that
   * breaks a dashboard).
   */
  public static final String MISSION_PRESENCE_MISSIONS = "basetool.mission.presence.missions";

  /**
   * Gauge {@code basetool_livesync_subscriptions} — tag {@code topic_class}; live live-sync topic
   * subscriptions per topic class in {@code LiveSyncWebSocketHandler} (REQ-FE-015, ADR-0094).
   */
  public static final String LIVESYNC_SUBSCRIPTIONS = "basetool.livesync.subscriptions";

  /**
   * Gauge {@code basetool_livesync_peer_rooms} — tag {@code topic_class}; the number of live rooms
   * of this class currently holding <b>two or more</b> subscribers, i.e. the rooms in which
   * peer-sync can actually do anything (#1238).
   *
   * <p>This is the honest denominator {@link #PRESENCE_RELAY_FRAMES} lacks. {@code
   * LiveSyncWebSocketHandler#relayLocal} skips the originating session, so a room with a single
   * viewer relays <b>zero</b> {@code changed} frames no matter how hard that viewer edits — a
   * {@code changed} flatline is therefore the normal state of an unoccupied surface, not a defect
   * signal, and reading one without this gauge is how a "peer sync is broken" alert would fire
   * every time the squadron simply was not co-editing. Measured on prod over the 21 days to
   * 2026-08-03, peak <em>concurrent</em> subscriptions per class ran from 15 ({@code mission}) down
   * to 1 ({@code operation} — co-presence never occurred there at all), which is why the {@code
   * changed}-flatline alert of #1238 stays deferred: only {@code mission} carries enough traffic to
   * support one.
   *
   * <p>Counts rooms, not sockets — {@link #LIVESYNC_SUBSCRIPTIONS} sums sockets across all rooms of
   * a class and therefore cannot distinguish two peers in one room (peer-sync live) from two
   * separate single-viewer rooms (peer-sync inert).
   */
  public static final String LIVESYNC_PEER_ROOMS = "basetool.livesync.peer.rooms";

  /**
   * Counter {@code basetool_livesync_subscribe_total} — tags {@code topic_class}, {@code outcome}
   * ({@link #OUTCOME_ALLOWED} / {@link #OUTCOME_DENIED}) and {@code reason}; the verdict of a
   * multiplexed {@code /ws/sync} subscribe-authorization check (REQ-FE-015, ADR-0094). A
   * saturated-executor fail-open is instead counted as a {@link #DROPPED_AUTHORIZE_SATURATED} relay
   * drop.
   *
   * <p>On a denial the {@code reason} tag separates the two very different paths that both land on
   * {@code outcome=denied} today: an explicit backend authorization refusal ({@link
   * #SUBSCRIBE_DENY_AUTHZ}) and a presence-enabled class failing <em>closed</em> on an
   * indeterminate probe ({@link #SUBSCRIBE_DENY_INDETERMINATE}). Without the split, a backend or
   * token outage reads exactly like users hitting permission boundaries. Micrometer requires a
   * uniform tag-key set per meter name, so the {@code allowed} series carries the {@link
   * #REASON_NONE} placeholder.
   */
  public static final String LIVESYNC_SUBSCRIBE = "basetool.livesync.subscribe";

  /**
   * Counter {@code basetool_livesync_socket_rejected_total} — tag {@code reason} ({@link
   * #SOCKET_REJECTED_USER_CAP}); a multiplexed {@code /ws/sync} socket refused at connect. Carries
   * no {@code topic_class} — a rejected socket has bound no topic yet (F2 / #1243, ADR-0094).
   */
  public static final String LIVESYNC_SOCKET_REJECTED = "basetool.livesync.socket.rejected";

  /**
   * Counter {@code basetool_livesync_invalid_topic_total} (unlabelled) — a multiplexed {@code
   * /ws/sync} subscribe to an unknown / unparseable topic ({@code LiveSyncTopic.parse(...) == null}
   * in {@code LiveSyncWebSocketHandler.handleSubscribe}). Carries <b>no</b> {@code topic_class} —
   * the topic did not parse, so it belongs to no known class — for the same reason {@link
   * #LIVESYNC_SOCKET_REJECTED} carries none: rather than inflate the bounded {@code topic_class}
   * set with an {@code unknown} sentinel, a dedicated unlabelled meter keeps that set to the real
   * classes (REQ-OBS-011 design call, #1239). A sustained stream is the signature of a
   * client/server topic-vocabulary skew — a client subscribing to a topic this server no longer
   * knows (REQ-FE-015, ADR-0094).
   */
  public static final String LIVESYNC_INVALID_TOPIC = "basetool.livesync.invalid.topic";

  /**
   * Counter {@code basetool_livesync_redis_published_total} — tag {@code topic_class}; {@code
   * changed} signals this instance published to the cross-replica Redis channel (ADR-0094).
   */
  public static final String LIVESYNC_REDIS_PUBLISHED = "basetool.livesync.redis.published";

  /**
   * Counter {@code basetool_livesync_redis_consumed_total} — tag {@code topic_class}; {@code
   * changed} signals this instance consumed from a peer replica (own-origin messages excluded).
   */
  public static final String LIVESYNC_REDIS_CONSUMED = "basetool.livesync.redis.consumed";

  /**
   * Counter {@code basetool_livesync_redis_errors_total} — tag {@code op} ({@link #OP_PUBLISH} /
   * {@link #OP_CONSUME}); a Redis fan-out publish or consume that failed (swallowed — local relay
   * already happened, so the failure only degrades cross-replica delivery, ADR-0094).
   */
  public static final String LIVESYNC_REDIS_ERRORS = "basetool.livesync.redis.errors";

  /**
   * Counter {@code basetool_livesync_presence_published_total} — tag {@code topic_class};
   * editor-presence snapshots this instance gossiped to the cross-replica presence channel
   * (ADR-0126, #1237).
   *
   * <p>A separate meter name from {@link #LIVESYNC_REDIS_PUBLISHED} on purpose. Presence gossip is
   * <em>periodic</em> (one message per tracked topic per reaper tick) while the changed relay is
   * <em>event-driven</em>, so folding them into one series — even under a distinguishing tag —
   * would let the steady gossip floor swamp the changed-relay rate that the fan-out dashboard panel
   * exists to show.
   */
  public static final String LIVESYNC_PRESENCE_PUBLISHED = "basetool.livesync.presence.published";

  /**
   * Counter {@code basetool_livesync_presence_consumed_total} — tag {@code topic_class};
   * editor-presence snapshots this instance consumed from a peer replica (own-origin messages
   * excluded, ADR-0126).
   */
  public static final String LIVESYNC_PRESENCE_CONSUMED = "basetool.livesync.presence.consumed";

  /**
   * Gauge {@code basetool_livesync_presence_remote_partitions} (unlabelled) — live {@code (topic,
   * peer instance)} editor-presence partitions mirrored from other replicas (ADR-0126). Reads a
   * flat zero on a single-replica deployment; on a multi-replica one, a zero while {@link
   * #MISSION_PRESENCE_MISSIONS} is non-zero on several replicas means the presence gossip is not
   * landing. Unlabelled: topic id and instance id are both unbounded (REQ-OBS-006).
   */
  public static final String LIVESYNC_PRESENCE_REMOTE_PARTITIONS =
      "basetool.livesync.presence.remote.partitions";

  /** Gauge {@code basetool_active_sessions} — active Spring Session sessions (frontend). */
  public static final String ACTIVE_SESSIONS = "basetool.active.sessions";

  /**
   * Counter {@code basetool_session_evicted_total} (unlabelled) — bumped when Spring Security's
   * concurrent-session control expires a user's oldest session because the {@code maximumSessions}
   * cap configured in {@code SecurityConfig} is already full. The eviction reaches the victim only
   * as an unexplained logout on their next request and leaves no other trace, so a user genuinely
   * cycling devices and a session registry that has stopped reaping stale Redis entries (the cap
   * then filling with dead sessions and evicting live ones) are indistinguishable without this
   * counter. Unlabelled — neither the principal name nor the session id may become a tag value
   * (REQ-OBS-004).
   */
  public static final String SESSION_EVICTED = "basetool.session.evicted";

  /**
   * Counter {@code basetool_client_error_total} — tag {@code kind} ({@link
   * #CLIENT_ERROR_SCRIPT_ERROR} / {@link #CLIENT_ERROR_UNHANDLED_REJECTION} / {@link
   * #CLIENT_ERROR_RESOURCE_ERROR}); browser-side failures reported by the client error beacon. A JS
   * exception that kills a {@code krtFetch} handler produces no server-side signal whatsoever — the
   * request that never happens cannot be counted — so a deploy that breaks the client half of a
   * live-update surface is otherwise invisible until a user reports it.
   *
   * <p>The {@code kind} tag is resolved <em>server-side</em> against the three literals below and
   * the beacon's value is discarded when it matches none of them: the endpoint is reachable without
   * a session, so echoing a client-supplied kind would be an unbounded, attacker-controlled label
   * (REQ-OBS-006). The message, stack, script URL and user agent never reach a tag or a log line.
   */
  public static final String CLIENT_ERROR = "basetool.client.error";

  /** Counter {@code basetool_backend_client_errors_total} — tags {@code reason}, {@code method}. */
  public static final String BACKEND_CLIENT_ERRORS = "basetool.backend.client.errors";

  /**
   * Gauge {@code basetool_notification_relay_connections} — open browser→backend SSE relays held by
   * {@code NotificationPageController.stream()} on this instance (#1041 item 17).
   */
  public static final String NOTIFICATION_RELAY_CONNECTIONS =
      "basetool.notification.relay.connections";

  /**
   * Gauge {@code basetool_presence_ws_sessions} — live live-sync WebSocket sessions summed across
   * all topic rooms in {@code LiveSyncWebSocketHandler} (#1041 item 17, REQ-FE-015).
   */
  public static final String PRESENCE_WS_SESSIONS = "basetool.presence.ws.sessions";

  /**
   * Counter {@code basetool_presence_relay_frames_total} — tags {@code type} ({@link
   * #FRAME_CHANGED} / {@link #FRAME_SNAPSHOT}) and {@code topic_class} (REQ-FE-015). A {@code
   * changed}-frame flatline while {@code snapshot} frames keep flowing is the early indicator for
   * the REQ-FE-010 live-multi-user-sync defect class.
   */
  public static final String PRESENCE_RELAY_FRAMES = "basetool.presence.relay.frames";

  /**
   * Counter {@code basetool_presence_relay_dropped_total} — tags {@code reason} ({@link
   * #DROPPED_THROTTLED} / {@link #DROPPED_SEND_FAILED} / {@link #DROPPED_TOPIC_CAP} / {@link
   * #DROPPED_TOPIC_THROTTLED} / {@link #DROPPED_AUTHORIZE_SATURATED} / {@link
   * #DROPPED_SECTION_FILTERED}) and {@link #TAG_TOPIC_CLASS} at the drop branches of the relay
   * (#1041 item 17, REQ-FE-015). Every drop branch counts here, so the reason set grows with the
   * relay; the {@code topic_class} tag is the same bounded set the other relay counters use.
   */
  public static final String PRESENCE_RELAY_DROPPED = "basetool.presence.relay.dropped";

  /**
   * Counter {@code basetool_login_total} — tags {@code outcome} ({@link #OUTCOME_SUCCESS} / {@link
   * #OUTCOME_FAILURE}) and {@code reason} (on failure {@link #LOGIN_REASON_INVALID_STATE} / {@link
   * #LOGIN_REASON_PROVIDER_ERROR} / {@link #LOGIN_REASON_OTHER}, else {@link #LOGIN_REASON_NONE}).
   * The reason is mapped from the exception type, never the raw error string (#1041 item 18).
   */
  public static final String LOGIN = "basetool.login";

  /**
   * Counter {@code basetool_csrf_rejections_total} (unlabelled) — bumped by the access-denied
   * handler on a CSRF-token rejection before it delegates the 403. {@code krtFetch}'s silent
   * single-retry self-heal otherwise masks a systematic CSRF-wiring regression as intermittent
   * failed writes (#1041 item 18).
   */
  public static final String CSRF_REJECTIONS = "basetool.csrf.rejections";

  /**
   * Counter {@code basetool_bot_blocked_total} — tag {@code rule} ({@link #BOT_RULE_METHOD} /
   * {@link #BOT_RULE_PATH_PREFIX} / {@link #BOT_RULE_FILE_EXTENSION}). {@code
   * BotProtectionFilter}'s three reject branches are otherwise {@code log.debug}-only
   * (prod-invisible); the counter also surfaces a self-inflicted false positive when a new legit
   * route matches a blocked prefix (#1041 item 19).
   */
  public static final String BOT_BLOCKED = "basetool.bot.blocked";

  /**
   * Tag key: the bounded backend-call failure reason — also the presence-drop reason, the login
   * reason, the socket-rejected reason and the live-sync subscribe-deny reason ({@link
   * #SUBSCRIBE_DENY_AUTHZ} / {@link #SUBSCRIBE_DENY_INDETERMINATE}). Every meter using it draws
   * from its own fixed value set; none of those values is ever derived from client input.
   */
  public static final String TAG_REASON = "reason";

  /**
   * Tag key: the browser-error class on {@link #CLIENT_ERROR} ({@link #CLIENT_ERROR_SCRIPT_ERROR} /
   * {@link #CLIENT_ERROR_UNHANDLED_REJECTION} / {@link #CLIENT_ERROR_RESOURCE_ERROR}). Resolved
   * server-side against exactly those three literals — a beacon payload that matches none of them
   * contributes no series at all (REQ-OBS-006).
   */
  public static final String TAG_KIND = "kind";

  /** Tag key: the HTTP verb of the failed backend call ({@code GET}/{@code POST}/…). */
  public static final String TAG_METHOD = "method";

  /** Tag key: the presence-relay frame type on {@link #PRESENCE_RELAY_FRAMES}. */
  public static final String TAG_TYPE = "type";

  /**
   * Tag key: the bounded live-sync {@code topic_class} on the relay counters and {@link
   * #LIVESYNC_SUBSCRIPTIONS} — one of the {@code LiveSyncTopicClass} metric labels (REQ-OBS-011).
   */
  public static final String TAG_TOPIC_CLASS = "topic_class";

  /** Tag key: the Redis fan-out operation on {@link #LIVESYNC_REDIS_ERRORS}. */
  public static final String TAG_OP = "op";

  /** Redis fan-out operation: publishing a {@code changed} signal to peers. */
  public static final String OP_PUBLISH = "publish";

  /** Redis fan-out operation: consuming a peer's {@code changed} signal. */
  public static final String OP_CONSUME = "consume";

  /**
   * Redis fan-out operation: gossiping an editor-presence snapshot to peers (ADR-0126). A distinct
   * value from {@link #OP_PUBLISH} so the {@code LiveSyncRedisFanoutBroken} alert keeps firing on
   * the changed relay alone: a failed changed publish silently degrades <em>correctness</em> (a
   * peer's view stays stale), while a failed presence gossip only costs a cosmetic dot — visible on
   * the fan-out dashboard panel, not worth a page.
   */
  public static final String OP_PRESENCE_PUBLISH = "presence_publish";

  /** Redis fan-out operation: consuming a peer's editor-presence snapshot (ADR-0126). */
  public static final String OP_PRESENCE_CONSUME = "presence_consume";

  /** Tag key: the login outcome on {@link #LOGIN}. */
  public static final String TAG_OUTCOME = "outcome";

  /** Tag key: the bot-protection reject rule on {@link #BOT_BLOCKED}. */
  public static final String TAG_RULE = "rule";

  /** Bot-block rule: a disallowed HTTP method (answered 405). */
  public static final String BOT_RULE_METHOD = "method";

  /** Bot-block rule: a known bot/scanner path prefix (answered 404). */
  public static final String BOT_RULE_PATH_PREFIX = "path_prefix";

  /** Bot-block rule: a never-served file extension (answered 404). */
  public static final String BOT_RULE_FILE_EXTENSION = "file_extension";

  /** Login outcome: authentication succeeded. */
  public static final String OUTCOME_SUCCESS = "success";

  /** Login outcome: authentication failed. */
  public static final String OUTCOME_FAILURE = "failure";

  /** Login failure reason: OAuth2 state / authorization-request mismatch (CSRF-of-the-flow). */
  public static final String LOGIN_REASON_INVALID_STATE = "invalid_state";

  /** Login failure reason: the IdP or the code-to-token exchange returned an error. */
  public static final String LOGIN_REASON_PROVIDER_ERROR = "provider_error";

  /** Login failure reason: any other authentication exception. */
  public static final String LOGIN_REASON_OTHER = "other";

  /**
   * Reason placeholder for a series whose outcome has no reason — a successful {@link #LOGIN}, and
   * an {@link #OUTCOME_ALLOWED} subscribe on {@link #LIVESYNC_SUBSCRIBE}. Micrometer rejects the
   * same meter name registered with differing tag-key sets, so the non-failure series must still
   * carry a {@code reason}; this is the value it carries.
   */
  public static final String REASON_NONE = "none";

  /**
   * Login-specific spelling of {@link #REASON_NONE}, kept because the login call sites and the
   * {@link #LOGIN} contract are written in terms of it. Identical wire value {@code none}: the two
   * are interchangeable and must stay so — do not give either a different literal.
   */
  public static final String LOGIN_REASON_NONE = REASON_NONE;

  /** Presence relay frame type: a peer-forwarded {@code changed} live-sync signal. */
  public static final String FRAME_CHANGED = "changed";

  /** Presence relay frame type: a full presence {@code snapshot} broadcast. */
  public static final String FRAME_SNAPSHOT = "snapshot";

  /** Presence drop reason: a {@code changed} frame rejected by the per-session token bucket. */
  public static final String DROPPED_THROTTLED = "throttled";

  /** Presence drop reason: a frame that failed to write to a closed/broken session. */
  public static final String DROPPED_SEND_FAILED = "send_failed";

  /** Presence drop reason: a subscribe refused because the socket hit its per-session topic cap. */
  public static final String DROPPED_TOPIC_CAP = "topic_cap";

  /**
   * Presence drop reason: a {@code changed} frame rejected by the per-<em>topic</em> token bucket —
   * the room's aggregate publish rate exceeded its bound regardless of the per-session limit (F2 /
   * #1243).
   */
  public static final String DROPPED_TOPIC_THROTTLED = "topic_throttled";

  /**
   * Presence drop reason: a subscribe that failed open because the subscribe-authorization executor
   * was saturated (the probe could not be scheduled).
   */
  public static final String DROPPED_AUTHORIZE_SATURATED = "authorize_saturated";

  /**
   * Presence drop reason: a {@code changed} frame whose section keys were all rejected by the topic
   * class's allowed-section whitelist, so nothing was relayed to the room. This is the exact
   * signature of the REQ-FE-010 defect class — an acting client broadcasting a section key the
   * relay's accept-list does not know leaves every peer stale, with no error on either side — and
   * the drop is otherwise completely silent, which is why it is counted rather than logged. The
   * rejected key is client-supplied and never becomes a tag value (REQ-OBS-006).
   */
  public static final String DROPPED_SECTION_FILTERED = "section_filtered";

  /** Live-sync subscribe outcome: the subscribe was authorized (or failed open on a transient). */
  public static final String OUTCOME_ALLOWED = "allowed";

  /**
   * Live-sync subscribe outcome: the subscribe was refused. Covers both an explicit backend 403/404
   * and a presence-enabled class failing closed on an indeterminate probe — the accompanying {@code
   * reason} tag ({@link #SUBSCRIBE_DENY_AUTHZ} / {@link #SUBSCRIBE_DENY_INDETERMINATE}) is what
   * tells the two apart.
   */
  public static final String OUTCOME_DENIED = "denied";

  /**
   * Subscribe-deny reason on {@link #LIVESYNC_SUBSCRIBE} with {@link #OUTCOME_DENIED}: the
   * authorization probe got an explicit backend 403/404, or a locally role-gated global room
   * withheld its capability. A real permission verdict — the caller may not read the topic — so a
   * steady trickle here is normal and only a step change is interesting.
   */
  public static final String SUBSCRIBE_DENY_AUTHZ = "authz";

  /**
   * Subscribe-deny reason on {@link #LIVESYNC_SUBSCRIBE} with {@link #OUTCOME_DENIED}: the
   * authorization outcome was indeterminate (no captured token, a transient 401/5xx/timeout, or a
   * probe that threw) and the topic class is presence-enabled, so it failed <b>closed</b> rather
   * than risk disclosing who is editing a resource the caller may not read. Not a permission
   * verdict: a rising rate here is a backend/token availability problem, and lumping it into {@link
   * #SUBSCRIBE_DENY_AUTHZ} would make an outage look like users hitting permission boundaries.
   */
  public static final String SUBSCRIBE_DENY_INDETERMINATE = "indeterminate";

  /**
   * Socket-rejected reason ({@link #LIVESYNC_SOCKET_REJECTED}): a {@code /ws/sync} socket refused
   * because the user already holds the maximum concurrent sockets (F2 / #1243).
   */
  public static final String SOCKET_REJECTED_USER_CAP = "user_cap";

  /** Reason: the backend returned a 4xx problem response. */
  public static final String REASON_BACKEND_4XX = "backend_4xx";

  /** Reason: the backend returned a 5xx problem response. */
  public static final String REASON_BACKEND_5XX = "backend_5xx";

  /** Reason: the Resilience4j circuit breaker was open (call short-circuited). */
  public static final String REASON_CIRCUIT_OPEN = "circuit_open";

  /** Reason: the Resilience4j bulkhead was saturated. */
  public static final String REASON_BULKHEAD_FULL = "bulkhead_full";

  /** Reason: a timeout or transport-level connection failure. */
  public static final String REASON_TIMEOUT = "timeout";

  /** Reason: any other unexpected backend failure. */
  public static final String REASON_UNKNOWN = "unknown";

  /** Browser error kind: an uncaught script exception reported via {@code window.onerror}. */
  public static final String CLIENT_ERROR_SCRIPT_ERROR = "script_error";

  /**
   * Browser error kind: a promise rejection no handler claimed, reported via the {@code
   * unhandledrejection} event — the shape a failed {@code krtFetch} chain takes.
   */
  public static final String CLIENT_ERROR_UNHANDLED_REJECTION = "unhandled_rejection";

  /**
   * Browser error kind: a subresource (script, stylesheet, image) that failed to load, caught on
   * the capturing {@code error} event. Distinguishes a broken/stale asset deploy from application
   * code that threw.
   */
  public static final String CLIENT_ERROR_RESOURCE_ERROR = "resource_error";

  private MetricNames() {
    // Constants holder — not instantiable.
  }
}
