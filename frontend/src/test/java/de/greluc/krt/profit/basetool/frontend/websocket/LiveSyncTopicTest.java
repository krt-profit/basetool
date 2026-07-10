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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LiveSyncTopic#parse(String)} and the {@link LiveSyncTopicClass} registry: a
 * scoped class round-trips a UUID into a normalised canonical string, an unknown prefix or a
 * scope/id mismatch is rejected, and every registered class is internally consistent.
 */
class LiveSyncTopicTest {

  @Test
  void parse_acceptsScopedMissionTopic_andNormalisesTheCanonical() {
    UUID id = UUID.randomUUID();

    LiveSyncTopic topic = LiveSyncTopic.parse("mission:" + id);

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.MISSION);
    assertThat(topic.resourceId()).isEqualTo(id);
    assertThat(topic.canonical()).isEqualTo("mission:" + id);
  }

  @Test
  void parse_acceptsScopedOperationTopic() {
    UUID id = UUID.randomUUID();

    LiveSyncTopic topic = LiveSyncTopic.parse("operation:" + id);

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.OPERATION);
    assertThat(topic.resourceId()).isEqualTo(id);
    assertThat(topic.canonical()).isEqualTo("operation:" + id);
  }

  @Test
  void parse_acceptsGlobalOrdersQueueTopic() {
    LiveSyncTopic topic = LiveSyncTopic.parse("orders");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.ORDERS_QUEUE);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("orders");
  }

  @Test
  void parse_rejectsGlobalOrdersQueueTopicCarryingAnId() {
    // `orders` is a global room; a prefixed id violates its scope.
    assertThat(LiveSyncTopic.parse("orders:" + UUID.randomUUID())).isNull();
  }

  @Test
  void everyScopedClassExposesAnAuthProbePathWithAnIdPlaceholder() {
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      if (topicClass.scoped()) {
        assertThat(topicClass.authProbePath())
            .as("scoped class %s has a subscribe-auth probe path", topicClass)
            .isNotBlank();
        assertThat(topicClass.authProbePath()).contains("{id}");
      }
    }
  }

  @Test
  void parse_rejectsUnknownPrefix() {
    assertThat(LiveSyncTopic.parse("bogus:" + UUID.randomUUID())).isNull();
    assertThat(LiveSyncTopic.parse("bogus")).isNull();
  }

  @Test
  void parse_rejectsScopedClassWithoutAValidUuid() {
    assertThat(LiveSyncTopic.parse("mission")).isNull();
    assertThat(LiveSyncTopic.parse("mission:")).isNull();
    assertThat(LiveSyncTopic.parse("mission:not-a-uuid")).isNull();
  }

  @Test
  void parse_rejectsNullEmptyAndOverlongInput() {
    assertThat(LiveSyncTopic.parse(null)).isNull();
    assertThat(LiveSyncTopic.parse("")).isNull();
    assertThat(LiveSyncTopic.parse("mission:" + "x".repeat(200))).isNull();
  }

  @Test
  void everyRegisteredClassHasANonEmptyWhitelistAndDistinctMetricLabel() {
    LiveSyncTopicClass[] classes = LiveSyncTopicClass.values();
    for (LiveSyncTopicClass topicClass : classes) {
      assertThat(topicClass.allowedSections()).as("whitelist of %s", topicClass).isNotEmpty();
      assertThat(topicClass.prefix()).as("prefix of %s", topicClass).isNotBlank();
      assertThat(topicClass.metricLabel()).as("metric label of %s", topicClass).isNotBlank();
    }
    // Metric labels must be unique so the bounded topic_class dimension never collides
    // (REQ-OBS-011).
    long distinctLabels =
        java.util.Arrays.stream(classes).map(LiveSyncTopicClass::metricLabel).distinct().count();
    assertThat(distinctLabels).isEqualTo(classes.length);
  }
}
