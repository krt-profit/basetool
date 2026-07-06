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

  /** Gauge {@code basetool_mission_presence_missions} — missions with a live editor (frontend). */
  public static final String MISSION_PRESENCE_MISSIONS = "basetool.mission.presence.missions";

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
   * Gauge {@code basetool_presence_ws_sessions} — live mission-presence WebSocket sessions summed
   * across all missions in {@code MissionPresenceWebSocketHandler} (#1041 item 17).
   */
  public static final String PRESENCE_WS_SESSIONS = "basetool.presence.ws.sessions";

  /**
   * Counter {@code basetool_presence_relay_frames_total} — tag {@code type} ({@link #FRAME_CHANGED}
   * / {@link #FRAME_SNAPSHOT}). A {@code changed}-frame flatline while {@code snapshot} frames keep
   * flowing is the early indicator for the REQ-FE-010 live-multi-user-sync defect class.
   */
  public static final String PRESENCE_RELAY_FRAMES = "basetool.presence.relay.frames";

  /**
   * Counter {@code basetool_presence_relay_dropped_total} — tag {@code reason} ({@link
   * #DROPPED_THROTTLED} / {@link #DROPPED_SEND_FAILED}) at the currently-silent throttle and
   * send-failure branches of the presence relay (#1041 item 17).
   */
  public static final String PRESENCE_RELAY_DROPPED = "basetool.presence.relay.dropped";

  /** Tag key: the bounded backend-call failure reason (also the presence-drop reason). */
  public static final String TAG_REASON = "reason";

  /** Tag key: the HTTP verb of the failed backend call ({@code GET}/{@code POST}/…). */
  public static final String TAG_METHOD = "method";

  /** Tag key: the presence-relay frame type on {@link #PRESENCE_RELAY_FRAMES}. */
  public static final String TAG_TYPE = "type";

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
