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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What the publish seam guarantees its callers (ADR-0274): a frame carrying no known section is
 * dropped, a fan-out failure is swallowed and counted as not accepted, and nothing ever reaches the
 * caller as an exception.
 */
@ExtendWith(MockitoExtension.class)
class LiveSyncPublisherTest {

  @Mock private LiveSyncFanout fanout;

  private SimpleMeterRegistry meterRegistry;
  private LiveSyncPublisher publisher;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    publisher = new LiveSyncPublisher(fanout, meterRegistry);
  }

  /**
   * The accepted-frame count for one room class.
   *
   * @param topicClass the class whose counter is read
   * @return the count, zero when the counter was never created
   */
  private double accepted(LiveSyncTopicClass topicClass) {
    return meterRegistry
        .counter(
            MetricNames.LIVESYNC_PUBLISH_ACCEPTED,
            MetricNames.TAG_TOPIC_CLASS,
            topicClass.metricLabel())
        .count();
  }

  @Test
  @DisplayName("a frame with a known section is handed to the fan-out and counted")
  void aKnownSectionIsPublished() {
    publisher.publish(LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), List.of("stock"));

    verify(fanout)
        .publish(LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), List.of("stock"));
    assertThat(accepted(LiveSyncTopicClass.INVENTORY_ALL)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a frame whose sections all clip away is never published")
  void anUnknownSectionIsDropped() {
    publisher.publish(
        LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), Arrays.asList("grid", null));

    verify(fanout, never()).publish(any(), anyList());
    assertThat(accepted(LiveSyncTopicClass.INVENTORY_ALL)).isZero();
  }

  @Test
  @DisplayName("a fan-out failure never reaches the caller and is not counted as accepted")
  void aFanoutFailureIsSwallowed() {
    doThrow(new IllegalStateException("redis down")).when(fanout).publish(any(), anyList());

    assertThatCode(
            () ->
                publisher.publish(
                    LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board")))
        .as("a write must never fail because an open page missed its refresh")
        .doesNotThrowAnyException();
    assertThat(accepted(LiveSyncTopicClass.MATERIALBOARD)).isZero();
  }
}
