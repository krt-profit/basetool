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

package de.greluc.krt.profit.basetool.backend.notification.api;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the notification
 * module: delivery, mail, the SSE stream and its Redis fan-out (REQ-OBS-011). The names and values
 * are part of the dashboards and alert rules under {@code monitoring/}.
 */
public final class NotificationMetricNames {

  /**
   * Gauge {@code basetool_redis_fanout_subscribed} — tag {@code fanout} ({@code notifications}); 1
   * while the cross-instance pub/sub container is listening, 0 while it is not. Bound to {@code
   * isListening()}, not {@code isRunning()}.
   */
  public static final String REDIS_FANOUT_SUBSCRIBED = "basetool.redis.fanout.subscribed";

  /**
   * Counter {@code basetool_notification_retention_deleted_total} — tag {@code kind} ({@code read}
   * / {@code unread}), the two halves of the inbox retention sweep (REQ-NOTIF-009).
   */
  public static final String NOTIFICATION_RETENTION_DELETED =
      "basetool.notification.retention.deleted";

  /**
   * Counter {@code basetool_notification_muted_total} — tag {@code notification_type}, the
   * recipients dropped because they muted the type (REQ-NOTIF-027).
   */
  public static final String NOTIFICATION_MUTED = "basetool.notification.muted";

  /**
   * Counter {@code basetool_notification_timed_produced_total} — tag {@code kind}, the producer
   * that raised the notices (REQ-NOTIF-026). Bounded by the number of producers.
   */
  public static final String NOTIFICATION_TIMED_PRODUCED = "basetool.notification.timed.produced";

  /**
   * Counter {@code basetool_notification_created_total} — tag {@code notification_type}, the inbox
   * rows written for a fired event (REQ-NOTIF-029).
   */
  public static final String NOTIFICATION_CREATED = "basetool.notification.created";

  /**
   * Counter {@code basetool_mail_total} — tag {@code outcome} ({@link #MAIL_SENT} / {@link
   * #MAIL_FAILED} / {@link #MAIL_DROPPED_DISABLED} / {@link #MAIL_DROPPED_NO_HOST} / {@link
   * #MAIL_DROPPED_NO_SENDER}). Counts the delivery failures and config-gated drops {@code
   * SmtpMailService} swallows; never the recipient or subject.
   */
  public static final String MAIL = "basetool.mail";

  /**
   * Gauge {@code basetool_sse_connections} — the live Server-Sent-Event subscriber count summed
   * across all recipients in {@code NotificationStreamService}. Drives {@code SsePushChannelDead}
   * (zero here while the frontend still reports active sessions = a dead push channel).
   */
  public static final String SSE_CONNECTIONS = "basetool.sse.connections";

  /**
   * Counter {@code basetool_sse_send_failures_total} — tags {@code event} ({@link
   * #SSE_EVENT_CONNECTED} / {@link #SSE_EVENT_NOTIFICATION} / {@link #SSE_EVENT_HEARTBEAT}) and
   * {@code cause} ({@link MetricNames#CAUSE_IO} / {@link MetricNames#CAUSE_ILLEGAL_STATE} / {@link
   * MetricNames#CAUSE_OTHER}), bumped at each drop-on-send-failure branch.
   *
   * <p>{@code cause} is derived from the caught exception's type, never its message (REQ-OBS-006).
   */
  public static final String SSE_SEND_FAILURES = "basetool.sse.send.failures";

  /**
   * Counter {@code basetool_sse_emitters_evicted_total} (untagged) — bumped each time {@code
   * NotificationStreamService.subscribe} retires a recipient's oldest stream because the
   * per-recipient emitter cap ({@code MAX_EMITTERS_PER_SUB}) is full.
   *
   * <p>A sustained rate means the cap is set too low. The recipient {@code sub} is never a label
   * (REQ-OBS-006).
   */
  public static final String SSE_EMITTERS_EVICTED = "basetool.sse.emitters.evicted";

  /**
   * Counter {@code basetool_sse_redis_published_total} — real-time notification signals this
   * instance published to the cross-replica Redis channel (ADR-0094).
   */
  public static final String SSE_REDIS_PUBLISHED = "basetool.sse.redis.published";

  /**
   * Counter {@code basetool_sse_redis_consumed_total} — real-time notification signals this
   * instance consumed from a peer replica (own-origin messages excluded).
   */
  public static final String SSE_REDIS_CONSUMED = "basetool.sse.redis.consumed";

  /**
   * Counter {@code basetool_sse_redis_errors_total} — tag {@code op} ({@link
   * MetricNames#OP_PUBLISH} / {@link MetricNames#OP_CONSUME}); a Redis fan-out publish or consume
   * that failed (swallowed — local delivery already happened, so the failure only degrades
   * cross-replica push; polling remains the fallback, ADR-0094).
   */
  public static final String SSE_REDIS_ERRORS = "basetool.sse.redis.errors";

  /**
   * Tag key: the {@code NotificationType} name on {@link #NOTIFICATION_MUTED}. Bounded by the
   * notification types.
   */
  public static final String TAG_NOTIFICATION_TYPE = "notification_type";

  /**
   * Tag key: which cross-instance pub/sub container a {@link #REDIS_FANOUT_SUBSCRIBED} sample
   * belongs to — on this backend only {@link #FANOUT_NOTIFICATIONS}, since the live-sync fan-out
   * publishes without subscribing (ADR-0274). One series, fixed at compile time.
   */
  public static final String TAG_FANOUT = "fanout";

  /** Fan-out identity: the notification push's Redis pub/sub container (ADR-0094). */
  public static final String FANOUT_NOTIFICATIONS = "notifications";

  /** Mail outcome: the SMTP relay accepted the message. */
  public static final String MAIL_SENT = "sent";

  /** Mail outcome: the relay threw a {@code MailException} (swallowed, best-effort). */
  public static final String MAIL_FAILED = "failed";

  /** Mail outcome: dropped because the {@code app.mail.enabled} kill-switch is off. */
  public static final String MAIL_DROPPED_DISABLED = "dropped_disabled";

  /** Mail outcome: dropped because {@code spring.mail.host} is blank (SMTP not configured). */
  public static final String MAIL_DROPPED_NO_HOST = "dropped_no_host";

  /** Mail outcome: host set but no {@code JavaMailSender} bean was available. */
  public static final String MAIL_DROPPED_NO_SENDER = "dropped_no_sender";

  /** SSE event value: the initial {@code connected} handshake event. */
  public static final String SSE_EVENT_CONNECTED = "connected";

  /** SSE event value: an unread-state-changed {@code notification} push. */
  public static final String SSE_EVENT_NOTIFICATION = "notification";

  /** SSE event value: the periodic keep-alive {@code heartbeat}. */
  public static final String SSE_EVENT_HEARTBEAT = "heartbeat";

  private NotificationMetricNames() {}
}
