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
   * subscriptions per topic class in {@code LiveSyncWebSocketHandler} (REQ-FE-015, ADR-0092).
   */
  public static final String LIVESYNC_SUBSCRIPTIONS = "basetool.livesync.subscriptions";

  /**
   * Counter {@code basetool_livesync_redis_published_total} — tag {@code topic_class}; {@code
   * changed} signals this instance published to the cross-replica Redis channel (ADR-0092).
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
   * already happened, so the failure only degrades cross-replica delivery, ADR-0092).
   */
  public static final String LIVESYNC_REDIS_ERRORS = "basetool.livesync.redis.errors";

  /** Gauge {@code basetool_active_sessions} — active Spring Session sessions (frontend). */
  public static final String ACTIVE_SESSIONS = "basetool.active.sessions";

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
   * #DROPPED_THROTTLED} / {@link #DROPPED_SEND_FAILED}) and {@code topic_class} at the throttle and
   * send-failure branches of the relay (#1041 item 17, REQ-FE-015).
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

  /** Tag key: the bounded backend-call failure reason (also the presence-drop / login reason). */
  public static final String TAG_REASON = "reason";

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

  /** Login reason placeholder on a success (keeps the counter's label schema consistent). */
  public static final String LOGIN_REASON_NONE = "none";

  /** Presence relay frame type: a peer-forwarded {@code changed} live-sync signal. */
  public static final String FRAME_CHANGED = "changed";

  /** Presence relay frame type: a full presence {@code snapshot} broadcast. */
  public static final String FRAME_SNAPSHOT = "snapshot";

  /** Presence drop reason: a {@code changed} frame rejected by the per-session token bucket. */
  public static final String DROPPED_THROTTLED = "throttled";

  /** Presence drop reason: a frame that failed to write to a closed/broken session. */
  public static final String DROPPED_SEND_FAILED = "send_failed";

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

  private MetricNames() {
    // Constants holder — not instantiable.
  }
}
