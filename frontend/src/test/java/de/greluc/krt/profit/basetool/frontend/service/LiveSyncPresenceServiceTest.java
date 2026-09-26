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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LiveSyncPresenceService}: presence recording and snapshot, idempotent
 * clearing, TTL reaping that reports only affected (topic, section) pairs, and independent tabs of
 * one user.
 *
 * <p>For the cross-replica mirror (ADR-0126): a peer partition merges without touching the local
 * half, is replaced wholesale, reports a change only when the merged view changes, and expires when
 * its replica goes silent.
 */
class LiveSyncPresenceServiceTest {

  private LiveSyncPresenceService service;
  private SimpleMeterRegistry meterRegistry;
  private String topicA;
  private String topicB;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service = new LiveSyncPresenceService(meterRegistry);
    topicA = "mission:" + UUID.randomUUID();
    topicB = "mission:" + UUID.randomUUID();
  }

  @Test
  void touch_refusesAFirstSeenSection_onceTheTopicIsAtTheDistinctSectionCap() {
    for (int i = 0; i < LiveSyncPresenceService.MAX_SECTIONS_PER_TOPIC; i++) {
      assertThat(service.touch(topicA, "sec-" + i, "user-1", "Alice")).isTrue();
    }
    assertThat(service.touch(topicA, "one-too-many", "user-1", "Alice")).isFalse();
    assertThat(service.get(topicA, "one-too-many", "user-1")).isNull();
    assertThat(service.get(topicA, "sec-0", "user-1")).isNotNull();
    assertThat(service.touch(topicB, "core", "user-2", "Bob")).isTrue();
  }

  @Test
  void presenceGauge_reflectsTheNumberOfTopicsWithLiveEditors() {
    assertThat(presenceGauge()).isEqualTo(0.0d);

    service.touch(topicA, "core", "user-1", "User One");
    service.touch(topicB, "schedule", "user-2", "User Two");

    assertThat(presenceGauge()).isEqualTo(2.0d);

    service.clearAll(topicA, "user-1");
    assertThat(presenceGauge()).isEqualTo(1.0d);
  }

  private double presenceGauge() {
    return meterRegistry.get(MetricNames.MISSION_PRESENCE_MISSIONS).gauge().value();
  }

  @Test
  void touch_shouldRecordEntry_andSurfaceItInSnapshot() {
    boolean firstSighting = service.touch(topicA, "details", "user-1", "Alice");

    assertThat(firstSighting).isTrue();
    Map<String, List<LiveSyncPresenceService.Entry>> snap = service.snapshot(topicA, Instant.now());
    assertThat(snap).containsOnlyKeys("details");
    assertThat(snap.get("details"))
        .extracting(LiveSyncPresenceService.Entry::userId)
        .containsExactly("user-1");
    assertThat(snap.get("details"))
        .extracting(LiveSyncPresenceService.Entry::displayName)
        .containsExactly("Alice");
  }

  @Test
  void touch_secondHeartbeat_shouldReturnFalseAndUpdateTimestamp() {
    service.touch(topicA, "details", "user-1", "Alice");
    Instant beforeSecond = service.get(topicA, "details", "user-1").lastHeartbeat();

    sleepTinyBit();

    boolean secondSighting = service.touch(topicA, "details", "user-1", "Alice");

    assertThat(secondSighting).isFalse();
    assertThat(service.get(topicA, "details", "user-1").lastHeartbeat()).isAfter(beforeSecond);
  }

  @Test
  void clear_shouldRemoveEntry_andBeIdempotent() {
    service.touch(topicA, "details", "user-1", "Alice");

    assertThat(service.clear(topicA, "details", "user-1")).isTrue();
    assertThat(service.clear(topicA, "details", "user-1")).isFalse();
    assertThat(service.snapshot(topicA, Instant.now())).isEmpty();
  }

  @Test
  void clearAll_shouldRemoveUserFromEverySection_andReportAffectedKeys() {
    service.touch(topicA, "details", "user-1", "Alice");
    service.touch(topicA, "participants", "user-1", "Alice");
    service.touch(topicA, "details", "user-2", "Bob");

    List<String> affected = service.clearAll(topicA, "user-1");

    assertThat(affected).containsExactlyInAnyOrder("details", "participants");
    Map<String, List<LiveSyncPresenceService.Entry>> snap = service.snapshot(topicA, Instant.now());
    assertThat(snap).containsOnlyKeys("details");
    assertThat(snap.get("details"))
        .extracting(LiveSyncPresenceService.Entry::userId)
        .containsExactly("user-2");
  }

  @Test
  void clearAll_onUnknownTopic_returnsEmptyAndDoesNotThrow() {
    assertThat(service.clearAll(topicA, "user-1")).isEmpty();
  }

  @Test
  void reapExpired_shouldDropEntriesPastTtl_andReportAffectedSections() {
    Instant longAgo = Instant.now().minus(LiveSyncPresenceService.ENTRY_TTL).minusSeconds(5);
    Instant now = Instant.now();

    service.touch(topicA, "details", "user-1", "Alice");
    service.touch(topicA, "schedule", "user-2", "Bob");
    Instant future = now.plus(LiveSyncPresenceService.ENTRY_TTL).plusSeconds(5);

    List<LiveSyncPresenceService.TopicSectionRef> affected = service.reapExpired(future);

    assertThat(affected)
        .extracting(LiveSyncPresenceService.TopicSectionRef::sectionKey)
        .containsExactlyInAnyOrder("details", "schedule");
    assertThat(service.snapshot(topicA, future)).isEmpty();
    assertThat(longAgo).isBefore(future);
  }

  @Test
  void reapExpired_shouldLeaveFreshEntriesAlone() {
    service.touch(topicA, "details", "user-1", "Alice");

    List<LiveSyncPresenceService.TopicSectionRef> affected = service.reapExpired(Instant.now());

    assertThat(affected).isEmpty();
    assertThat(service.snapshot(topicA, Instant.now()).get("details"))
        .extracting(LiveSyncPresenceService.Entry::userId)
        .containsExactly("user-1");
  }

  @Test
  void snapshot_shouldNotLeakReapableEntries() {
    service.touch(topicA, "details", "user-1", "Alice");
    Instant future = Instant.now().plus(LiveSyncPresenceService.ENTRY_TTL).plusSeconds(1);

    assertThat(service.snapshot(topicA, future)).isEmpty();
  }

  @Test
  void snapshot_shouldReturnImmutableMapAndImmutableLists() {
    service.touch(topicA, "details", "user-1", "Alice");
    Map<String, List<LiveSyncPresenceService.Entry>> snap = service.snapshot(topicA, Instant.now());

    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> snap.put("x", List.of()));
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> snap.get("details").clear());
  }

  @Test
  void trackedTopics_shouldReflectInsertionsAndCleanups() {
    assertThat(service.trackedTopics()).isEmpty();
    service.touch(topicA, "details", "user-1", "Alice");
    service.touch(topicB, "schedule", "user-2", "Bob");

    assertThat(service.trackedTopics()).containsExactlyInAnyOrder(topicA, topicB);

    service.clearAll(topicA, "user-1");
    assertThat(service.trackedTopics()).containsExactly(topicB);
  }

  @Test
  void snapshot_mergesLocalEditorsWithAPeerReplicasPartition() {
    service.touch(topicA, "crew", "user-1", "Alice");
    service.applyRemote(topicA, "instance-B", partition("steps", "user-2", "Bob"), Instant.now());

    Map<String, List<LiveSyncPresenceService.Entry>> snap = service.snapshot(topicA, Instant.now());

    assertThat(snap).containsOnlyKeys("crew", "steps");
    assertThat(snap.get("crew"))
        .extracting(LiveSyncPresenceService.Entry::userId)
        .containsExactly("user-1");
    assertThat(snap.get("steps"))
        .extracting(LiveSyncPresenceService.Entry::displayName)
        .containsExactly("Bob");
  }

  @Test
  void snapshot_collapsesAUserPresentOnBothThisInstanceAndAPeer() {
    service.touch(topicA, "crew", "user-1", "Alice");
    service.applyRemote(topicA, "instance-B", partition("crew", "user-1", "Alice"), Instant.now());

    assertThat(service.snapshot(topicA, Instant.now()).get("crew"))
        .extracting(LiveSyncPresenceService.Entry::userId)
        .containsExactly("user-1");
  }

  @Test
  void localSnapshot_carriesOnlyThisInstancesEditors() {
    service.touch(topicA, "crew", "user-1", "Alice");
    service.applyRemote(topicA, "instance-B", partition("steps", "user-2", "Bob"), Instant.now());

    assertThat(service.localSnapshot(topicA, Instant.now())).containsOnlyKeys("crew");
  }

  @Test
  void applyRemote_reportsAChangeOnlyWhenTheMergedViewActuallyChanges() {
    Instant now = Instant.now();

    assertThat(service.applyRemote(topicA, "instance-B", partition("crew", "user-2", "Bob"), now))
        .isTrue();
    assertThat(
            service.applyRemote(
                topicA, "instance-B", partition("crew", "user-2", "Bob"), now.plusSeconds(10)))
        .isFalse();
    assertThat(
            service.applyRemote(
                topicA, "instance-B", partition("crew", "user-3", "Carol"), now.plusSeconds(20)))
        .isTrue();
  }

  @Test
  void applyRemote_withAnEmptySnapshot_dropsThePeersPartitionImmediately() {
    service.applyRemote(topicA, "instance-B", partition("crew", "user-2", "Bob"), Instant.now());
    assertThat(service.remotePartitionCount()).isEqualTo(1);

    assertThat(service.applyRemote(topicA, "instance-B", Map.of(), Instant.now())).isTrue();

    assertThat(service.remotePartitionCount()).isZero();
    assertThat(service.snapshot(topicA, Instant.now())).isEmpty();
    assertThat(service.applyRemote(topicA, "instance-B", Map.of(), Instant.now())).isFalse();
  }

  @Test
  void applyRemote_refusesAFirstSeenOrigin_onceTheTopicIsAtTheOriginCap() {
    Instant now = Instant.now();
    for (int i = 0; i < LiveSyncPresenceService.MAX_REMOTE_ORIGINS_PER_TOPIC; i++) {
      assertThat(service.applyRemote(topicA, "instance-" + i, partition("crew", "u" + i, "U"), now))
          .isTrue();
    }

    assertThat(service.applyRemote(topicA, "instance-spoof", partition("crew", "x", "X"), now))
        .isFalse();
    assertThat(service.remotePartitionCount())
        .isEqualTo(LiveSyncPresenceService.MAX_REMOTE_ORIGINS_PER_TOPIC);
    assertThat(service.applyRemote(topicA, "instance-0", partition("steps", "u0", "U"), now))
        .isTrue();
  }

  @Test
  void applyRemote_truncatesAnOverLongEditorListPerSection() {
    List<LiveSyncPresenceService.PresenceEditor> editors = new ArrayList<>();
    for (int i = 0; i < LiveSyncPresenceService.MAX_EDITORS_PER_REMOTE_SECTION + 10; i++) {
      editors.add(new LiveSyncPresenceService.PresenceEditor("user-" + i, "User " + i));
    }

    service.applyRemote(topicA, "instance-B", Map.of("crew", editors), Instant.now());

    assertThat(service.snapshot(topicA, Instant.now()).get("crew"))
        .hasSize(LiveSyncPresenceService.MAX_EDITORS_PER_REMOTE_SECTION);
  }

  @Test
  void reapExpiredRemote_dropsASilentReplicasPartition_andReportsTheTopic() {
    Instant now = Instant.now();
    service.applyRemote(topicA, "instance-B", partition("crew", "user-2", "Bob"), now);
    service.applyRemote(topicB, "instance-C", partition("crew", "user-3", "Carol"), now);

    assertThat(service.reapExpiredRemote(now.plusSeconds(5))).isEmpty();

    Instant afterTtl = now.plus(LiveSyncPresenceService.REMOTE_PARTITION_TTL).plusSeconds(1);
    assertThat(service.reapExpiredRemote(afterTtl)).containsExactlyInAnyOrder(topicA, topicB);
    assertThat(service.remotePartitionCount()).isZero();
  }

  @Test
  void snapshot_hidesAPeerPartitionThatWouldExpireOnTheNextReap() {
    Instant now = Instant.now();
    service.applyRemote(topicA, "instance-B", partition("crew", "user-2", "Bob"), now);

    Instant afterTtl = now.plus(LiveSyncPresenceService.REMOTE_PARTITION_TTL).plusSeconds(1);
    assertThat(service.snapshot(topicA, afterTtl)).isEmpty();
  }

  @Test
  void remotePartitionsGauge_reflectsTheMirroredPartitionCount() {
    assertThat(remotePartitionsGauge()).isEqualTo(0.0d);

    service.applyRemote(topicA, "instance-B", partition("crew", "user-2", "Bob"), Instant.now());
    service.applyRemote(topicA, "instance-C", partition("crew", "user-3", "Carol"), Instant.now());

    assertThat(remotePartitionsGauge()).isEqualTo(2.0d);
  }

  /**
   * Builds a single-section, single-editor peer partition for the mirror tests.
   *
   * @param sectionKey the section the editor is on
   * @param userId the editor's stable id
   * @param displayName the editor's label
   * @return the partition as it would arrive from a peer replica
   */
  private static Map<String, List<LiveSyncPresenceService.PresenceEditor>> partition(
      String sectionKey, String userId, String displayName) {
    return Map.of(
        sectionKey, List.of(new LiveSyncPresenceService.PresenceEditor(userId, displayName)));
  }

  private double remotePartitionsGauge() {
    var gauge = meterRegistry.find(MetricNames.LIVESYNC_PRESENCE_REMOTE_PARTITIONS).gauge();
    return gauge == null ? 0.0d : gauge.value();
  }

  private static void sleepTinyBit() {
    try {
      Thread.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
