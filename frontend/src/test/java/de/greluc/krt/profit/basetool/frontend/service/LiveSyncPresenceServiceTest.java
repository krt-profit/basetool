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
 * Unit tests for {@link LiveSyncPresenceService} (ported from {@code MissionPresenceServiceTest}
 * with topic strings replacing mission UUIDs; the presence semantics are unchanged).
 *
 * <p>The service is an in-memory state holder; tests verify (a) presence is recorded and surfaced
 * via the snapshot, (b) clearing removes single entries idempotently, (c) the reaper drops entries
 * past TTL and only reports the affected (topic, section) pairs, (d) two tabs of the same user
 * don't wipe each other on a single tab close.
 *
 * <p>The cross-replica mirror (ADR-0126, #1237) adds a second group: a peer's partition merges into
 * the snapshot without touching the local half, is replaced wholesale, reports a change only when
 * the merged view really changed (so the 10 s re-gossip does not broadcast every tick), and expires
 * when its replica goes silent.
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
    // Fill the topic up to the distinct-section cap with unique first-seen sections (#1245): each
    // is
    // a new sighting.
    for (int i = 0; i < LiveSyncPresenceService.MAX_SECTIONS_PER_TOPIC; i++) {
      assertThat(service.touch(topicA, "sec-" + i, "user-1", "Alice")).isTrue();
    }
    // A further first-seen section is refused (not tracked) rather than growing the map unbounded.
    assertThat(service.touch(topicA, "one-too-many", "user-1", "Alice")).isFalse();
    assertThat(service.get(topicA, "one-too-many", "user-1")).isNull();
    // An already-tracked section still accepts (heartbeat / a second editor), and a different topic
    // has its own independent budget.
    assertThat(service.get(topicA, "sec-0", "user-1")).isNotNull();
    assertThat(service.touch(topicB, "core", "user-2", "Bob")).isTrue();
  }

  @Test
  void presenceGauge_reflectsTheNumberOfTopicsWithLiveEditors() {
    assertThat(presenceGauge()).isEqualTo(0.0d);

    service.touch(topicA, "core", "user-1", "User One");
    service.touch(topicB, "schedule", "user-2", "User Two");

    // Two distinct topics currently have a live editor.
    assertThat(presenceGauge()).isEqualTo(2.0d);

    // Clearing the only editor of topicA drops it from the tracked set.
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

    // Sleep a hair so the new heartbeat timestamp is strictly greater (Instant.now() resolution
    // is platform-dependent; on Windows it can be 15ms-ish, so even busy loops are fine — we just
    // need monotonic progress).
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
    // Bob still in details; participants section is gone entirely.
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

    // Inject a stale entry by touching first then time-travelling the "now" used by reap: the
    // service uses Instant.now() at insertion, so to simulate an old entry without sleeping for 30s
    // we instead pass a FUTURE "now" to reapExpired.
    service.touch(topicA, "details", "user-1", "Alice");
    service.touch(topicA, "schedule", "user-2", "Bob");
    Instant future = now.plus(LiveSyncPresenceService.ENTRY_TTL).plusSeconds(5);

    List<LiveSyncPresenceService.TopicSectionRef> affected = service.reapExpired(future);

    assertThat(affected)
        .extracting(LiveSyncPresenceService.TopicSectionRef::sectionKey)
        .containsExactlyInAnyOrder("details", "schedule");
    assertThat(service.snapshot(topicA, future)).isEmpty();
    assertThat(longAgo).isBefore(future); // sanity, suppresses unused warning
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

    // The reaper hasn't run yet, but a snapshot taken with a future `now` must already hide entries
    // that would be reaped on the next tick — the websocket handler relies on this to avoid pushing
    // stale state to a newly connecting client.
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
    // Two tabs of one user load-balanced onto different replicas must render as ONE dot, so the
    // count stays a count of people rather than of sockets.
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

    // Gossiping a peer's entries back at it would let two instances keep each other's stale dots
    // alive indefinitely.
    assertThat(service.localSnapshot(topicA, Instant.now())).containsOnlyKeys("crew");
  }

  @Test
  void applyRemote_reportsAChangeOnlyWhenTheMergedViewActuallyChanges() {
    Instant now = Instant.now();

    assertThat(service.applyRemote(topicA, "instance-B", partition("crew", "user-2", "Bob"), now))
        .isTrue();
    // The 10 s re-gossip restates what we already hold: refresh the partition silently rather than
    // broadcasting a fresh snapshot to every socket in the room every tick.
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
    // Idempotent: a second empty snapshot reports no change, so no needless broadcast.
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
    // An established origin still updates — the cap refuses newcomers, it never evicts.
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
    // Same contract the local half already honours: a snapshot never shows state the next reap
    // would remove.
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
