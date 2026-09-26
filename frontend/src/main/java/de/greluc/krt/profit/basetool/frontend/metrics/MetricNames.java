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
 * <p>Every tag value comes from a bounded set (REQ-OBS-006); the backend-client error {@code
 * reason} is derived locally, never from the backend's response.
 */
public final class MetricNames {

  /** Gauge {@code basetool_mission_presence_missions}: live-sync topics with a live editor. */
  public static final String MISSION_PRESENCE_MISSIONS = "basetool.mission.presence.missions";

  /**
   * Gauge {@code basetool_livesync_subscriptions} — tag {@code topic_class}; live live-sync topic
   * subscriptions per topic class in {@code LiveSyncWebSocketHandler} (REQ-FE-015, ADR-0094).
   */
  public static final String LIVESYNC_SUBSCRIPTIONS = "basetool.livesync.subscriptions";

  /**
   * Gauge {@code basetool_livesync_peer_rooms}, tag {@code topic_class}: the number of live rooms
   * holding two or more subscribers, i.e. where peer sync can relay anything.
   *
   * <p>Counts rooms, unlike {@link #LIVESYNC_SUBSCRIPTIONS}, which counts sockets.
   */
  public static final String LIVESYNC_PEER_ROOMS = "basetool.livesync.peer.rooms";

  /**
   * Counter {@code basetool_livesync_subscribe_total}, tags {@code topic_class}, {@code outcome}
   * ({@link #OUTCOME_ALLOWED} / {@link #OUTCOME_DENIED}) and {@code reason}: the verdict of a
   * {@code /ws/sync} subscribe authorization (REQ-FE-015, ADR-0094).
   *
   * <p>A denial's {@code reason} is {@link #SUBSCRIBE_DENY_AUTHZ} or {@link
   * #SUBSCRIBE_DENY_INDETERMINATE}; the {@code allowed} series carries {@link #REASON_NONE}.
   */
  public static final String LIVESYNC_SUBSCRIBE = "basetool.livesync.subscribe";

  /**
   * Counter {@code basetool_livesync_socket_rejected_total}, tag {@code reason}: a {@code /ws/sync}
   * socket refused at connect (ADR-0094). Carries no {@code topic_class}.
   */
  public static final String LIVESYNC_SOCKET_REJECTED = "basetool.livesync.socket.rejected";

  /**
   * Counter {@code basetool_livesync_invalid_topic_total} (unlabelled): a {@code /ws/sync}
   * subscribe to an unknown or unparseable topic (REQ-FE-015, ADR-0094). A sustained rate indicates
   * a client/server topic-vocabulary skew.
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
   * Counter {@code basetool_livesync_presence_published_total}, tag {@code topic_class}:
   * editor-presence snapshots this instance gossiped to the cross-replica presence channel
   * (ADR-0126). Kept separate from {@link #LIVESYNC_REDIS_PUBLISHED}.
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

  /**
   * Timer {@code basetool_livesync_socket_lifetime_seconds} (unlabelled): how long each {@code
   * /ws/sync} socket stayed open, recorded on close. Diagnostic only; no alert is wired on it.
   */
  public static final String LIVESYNC_SOCKET_LIFETIME = "basetool.livesync.socket.lifetime";

  /** Gauge {@code basetool_active_sessions} — active Spring Session sessions (frontend). */
  public static final String ACTIVE_SESSIONS = "basetool.active.sessions";

  /**
   * Counter {@code basetool_session_evicted_total} (unlabelled): a user's oldest session expired by
   * concurrent-session control because the {@code maximumSessions} cap is full (REQ-OBS-004).
   */
  public static final String SESSION_EVICTED = "basetool.session.evicted";

  /**
   * Counter {@code basetool_session_value_dropped_total}, tag {@code cause}: a Redis session value
   * that could not be deserialized and was dropped by {@code FaultTolerantSessionSerializer}.
   *
   * <p>{@code cause} is the root-cause simple class name from a fixed allow-list, otherwise {@code
   * other} (REQ-OBS-006, REQ-OBS-004).
   */
  public static final String SESSION_VALUE_DROPPED = "basetool.session.value.dropped";

  /**
   * Counter {@code basetool_session_type_refused_total}, tag {@code mode} ({@code report} / {@code
   * enforce}): a session value naming a class outside {@code SessionTypeAllowList} (REQ-SEC-067).
   *
   * <p>Under {@code report} it rises once per class and slot per frontend lifetime; under {@code
   * enforce} on every refused read. The class name is logged, never tagged.
   */
  public static final String SESSION_TYPE_REFUSED = "basetool.session.type.refused";

  /**
   * Counter {@code basetool_session_unmappable_total}, tag {@code missing_key}: a non-empty Redis
   * session hash lacking a field {@code RedisSessionMapper} requires, served as no session
   * (REQ-SEC-063).
   *
   * <p>A steady near-zero rate is expected; a climb means session hashes are being lost in volume.
   */
  public static final String SESSION_UNMAPPABLE = "basetool.session.unmappable";

  /**
   * Counter {@code basetool_client_error_total}, tag {@code kind}: browser-side failures reported
   * by the client error beacon.
   *
   * <p>{@code kind} is resolved server-side against the five {@code CLIENT_ERROR_*} literals and
   * discarded otherwise (REQ-OBS-006). Message, stack, script URL and user agent never reach a tag
   * or log line.
   */
  public static final String CLIENT_ERROR = "basetool.client.error";

  /** Counter {@code basetool_backend_client_errors_total} — tags {@code reason}, {@code method}. */
  public static final String BACKEND_CLIENT_ERRORS = "basetool.backend.client.errors";

  /**
   * Gauge {@code basetool_notification_relay_connections}: open browser-to-backend SSE relays held
   * by {@code NotificationPageController.stream()} on this instance.
   */
  public static final String NOTIFICATION_RELAY_CONNECTIONS =
      "basetool.notification.relay.connections";

  /**
   * Gauge {@code basetool_presence_ws_sessions}: live-sync WebSocket sessions summed across all
   * topic rooms in {@code LiveSyncWebSocketHandler} (REQ-FE-015).
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
   * Counter {@code basetool_presence_relay_dropped_total}, tags {@code reason} ({@link
   * #DROPPED_THROTTLED} / {@link #DROPPED_SEND_FAILED} / {@link #DROPPED_TOPIC_CAP} / {@link
   * #DROPPED_TOPIC_THROTTLED} / {@link #DROPPED_AUTHORIZE_SATURATED} / {@link
   * #DROPPED_SECTION_FILTERED}) and {@link #TAG_TOPIC_CLASS}: every drop branch of the live-sync
   * relay (REQ-FE-015).
   */
  public static final String PRESENCE_RELAY_DROPPED = "basetool.presence.relay.dropped";

  /**
   * Counter {@code basetool_login_total}, tags {@code outcome} ({@link #OUTCOME_SUCCESS} / {@link
   * #OUTCOME_FAILURE}) and {@code reason} (on failure {@link #LOGIN_REASON_INVALID_STATE} / {@link
   * #LOGIN_REASON_PROVIDER_ERROR} / {@link #LOGIN_REASON_OTHER}, else {@link #LOGIN_REASON_NONE}).
   * The reason is mapped from the exception type.
   */
  public static final String LOGIN = "basetool.login";

  /**
   * Counter {@code basetool_csrf_rejections_total} (unlabelled): a CSRF-token rejection counted by
   * the access-denied handler before it answers 403.
   */
  public static final String CSRF_REJECTIONS = "basetool.csrf.rejections";

  /**
   * Counter {@code basetool_bot_blocked_total}, tag {@code rule} ({@link #BOT_RULE_METHOD} / {@link
   * #BOT_RULE_PATH_PREFIX} / {@link #BOT_RULE_FILE_EXTENSION} / {@link #BOT_RULE_QUERY_STRING}):
   * requests rejected by {@code BotProtectionFilter}.
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
   * Tag key: the browser-error class on {@link #CLIENT_ERROR}, resolved server-side against the
   * five {@code CLIENT_ERROR_*} literals; a non-matching payload yields no series (REQ-OBS-006).
   */
  public static final String TAG_KIND = "kind";

  /** Tag key: the HTTP verb of the failed backend call ({@code GET}/{@code POST}/…). */
  public static final String TAG_METHOD = "method";

  /** Tag key: the presence-relay frame type on {@link #PRESENCE_RELAY_FRAMES}. */
  public static final String TAG_TYPE = "type";

  /**
   * Tag key: the bounded root-cause bucket of a dropped session value on {@link
   * #SESSION_VALUE_DROPPED}. Allow-listed exception simple names plus {@code other} — never a raw
   * class name, which would be an unbounded label.
   */
  public static final String TAG_CAUSE = "cause";

  /**
   * Tag key: the session type allow-list mode on {@link #SESSION_TYPE_REFUSED} — {@code report} or
   * {@code enforce}, a closed set of two literals ({@code off} never counts).
   */
  public static final String TAG_MODE = "mode";

  /**
   * Tag key: which required session-hash field was absent on {@link #SESSION_UNMAPPABLE}: {@code
   * creationTime}, {@code lastAccessedTime}, {@code maxInactiveInterval}, or {@code other}.
   */
  public static final String TAG_MISSING_KEY = "missing_key";

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
   * Redis fan-out operation: gossiping an editor-presence snapshot to peers (ADR-0126). Distinct
   * from {@link #OP_PUBLISH} so the {@code LiveSyncRedisFanoutBroken} alert covers only the changed
   * relay.
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

  /**
   * Bot-block rule: a syntactically invalid query string (answered with a bare 400). A chunk whose
   * parameter name is empty but which carries a value (e.g. {@code /?=phpinfo()}) makes Tomcat's
   * parameter parser throw on the first {@code getParameter*()} call, which happens on every
   * request; rejecting it at the edge is what keeps that failure out of the error dispatch.
   */
  public static final String BOT_RULE_QUERY_STRING = "query_string";

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

  /** Presence drop reason: a {@code changed} frame rejected by the per-topic token bucket. */
  public static final String DROPPED_TOPIC_THROTTLED = "topic_throttled";

  /**
   * Presence drop reason: a subscribe that failed open because the subscribe-authorization executor
   * was saturated (the probe could not be scheduled).
   */
  public static final String DROPPED_AUTHORIZE_SATURATED = "authorize_saturated";

  /**
   * Presence drop reason: a {@code changed} frame whose section keys were all rejected by the topic
   * class's allowed-section list, so nothing was relayed (REQ-FE-010). The rejected key never
   * becomes a tag value.
   */
  public static final String DROPPED_SECTION_FILTERED = "section_filtered";

  /** Live-sync subscribe outcome: the subscribe was authorized (or failed open on a transient). */
  public static final String OUTCOME_ALLOWED = "allowed";

  /**
   * Live-sync subscribe outcome: the subscribe was refused; the {@code reason} tag ({@link
   * #SUBSCRIBE_DENY_AUTHZ} / {@link #SUBSCRIBE_DENY_INDETERMINATE}) tells the cause.
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
   * Subscribe-deny reason: the authorization outcome was indeterminate (no token, a transient
   * 401/5xx/timeout, or a failing probe) and the presence-enabled topic class failed closed. Not a
   * permission verdict.
   */
  public static final String SUBSCRIBE_DENY_INDETERMINATE = "indeterminate";

  /**
   * Socket-rejected reason: the user already holds the maximum concurrent {@code /ws/sync} sockets.
   */
  public static final String SOCKET_REJECTED_USER_CAP = "user_cap";

  /** Socket-rejected reason: the user has not accepted the Terms of Use in force (REQ-SEC-028). */
  public static final String SOCKET_REJECTED_TERMS_GATE = "terms_gate";

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

  /**
   * Browser error kind: a Content-Security-Policy violation caught on the {@code
   * securitypolicyviolation} event. Only the violated directive and the blocked origin are sent.
   */
  public static final String CLIENT_ERROR_CSP_VIOLATION = "csp_violation";

  /**
   * Browser error kind: a script asked {@code window.krtI18nText} for a localized string its page
   * did not provide. The message carries only the key name.
   */
  public static final String CLIENT_ERROR_I18N_MISSING = "i18n_missing";

  /**
   * Gauge {@code basetool_tracing_enabled}: {@code 1} while this module is configured to emit
   * spans, {@code 0} while it is not.
   *
   * <p>Always registered, so {@code 0} means off on purpose while absence means the module is not
   * scraped. Untagged (REQ-OBS-011).
   */
  public static final String TRACING_ENABLED = "basetool.tracing.enabled";

  private MetricNames() {}
}
