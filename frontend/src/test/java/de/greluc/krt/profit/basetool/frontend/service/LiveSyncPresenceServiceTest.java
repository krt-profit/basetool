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

  private static void sleepTinyBit() {
    try {
      Thread.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
