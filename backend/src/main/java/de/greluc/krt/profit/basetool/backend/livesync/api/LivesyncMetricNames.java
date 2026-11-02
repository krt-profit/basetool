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

package de.greluc.krt.profit.basetool.backend.livesync.api;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the live-sync module
 * (REQ-OBS-011). The names and values are part of the dashboards and alert rules under {@code
 * monitoring/}.
 */
public final class LivesyncMetricNames {

  /**
   * Counter {@code basetool_livesync_publish_accepted_total} — tag {@code topic_class}; frames the
   * backend raised after a committed write of its own (ADR-0274).
   */
  public static final String LIVESYNC_PUBLISH_ACCEPTED = "basetool.livesync.publish.accepted";

  /**
   * Counter {@code basetool_livesync_redis_published_total} — tag {@code topic_class}; frames this
   * instance put on the shared channel for the frontend's sessions.
   */
  public static final String LIVESYNC_REDIS_PUBLISHED = "basetool.livesync.redis.published";

  /**
   * Counter {@code basetool_livesync_redis_errors_total} — tag {@code op} ({@link
   * MetricNames#OP_PUBLISH}); a swallowed publish failure, which costs an open page its refresh and
   * never the write.
   */
  public static final String LIVESYNC_REDIS_ERRORS = "basetool.livesync.redis.errors";

  /**
   * Tag key: the live-sync room class a measurement belongs to (ADR-0274). Bounded by the {@code
   * LiveSyncTopicClass} enum, one value per constant, distinct even where two classes share a wire
   * prefix so two surfaces never fold into one series (REQ-OBS-011).
   */
  public static final String TAG_TOPIC_CLASS = "topic_class";

  private LivesyncMetricNames() {}
}
