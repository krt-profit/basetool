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
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Announces a committed backend write to the frontend's live-sync rooms (ADR-0274, REQ-FE-010).
 *
 * <p>Unbounded by design: the write that raises a frame is itself bounded. Never throws, so a
 * caller needs no guard of its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveSyncPublisher {

  private final LiveSyncFanout fanout;
  private final MeterRegistry meterRegistry;

  /**
   * Publishes one frame to the room's listeners, dropping it when no named section survives the
   * class whitelist. A failed publish costs an open page its refresh and is never raised.
   *
   * @param topic the room
   * @param sections the sections that changed, as the caller names them
   */
  public void publish(@NotNull LiveSyncTopic topic, @NotNull List<String> sections) {
    List<String> clipped = topic.topicClass().clipSections(sections);
    if (clipped.isEmpty()) {
      return;
    }
    try {
      fanout.publish(topic, clipped);
    } catch (RuntimeException e) {
      log.warn("Live-sync frame for {} was not published", topic.topicClass().metricLabel(), e);
      return;
    }
    meterRegistry
        .counter(
            MetricNames.LIVESYNC_PUBLISH_ACCEPTED,
            MetricNames.TAG_TOPIC_CLASS,
            topic.topicClass().metricLabel())
        .increment();
  }
}
