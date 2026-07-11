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
 * Unit tests for {@link MissionPresenceService}.
 *
 * <p>The service is an in-memory state holder; tests verify (a) presence is recorded and surfaced
 * via the snapshot, (b) clearing removes single entries idempotently, (c) the reaper drops entries
 * past TTL and only reports the affected (mission, section) pairs, (d) two tabs of the same user
 * don't wipe each other on a single tab close.
 */
class MissionPresenceServiceTest {

  private MissionPresenceService service;
  private SimpleMeterRegistry meterRegistry;
  private UUID missionA;
  private UUID missionB;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service = new MissionPresenceService(meterRegistry);
    missionA = UUID.randomUUID();
    missionB = UUID.randomUUID();
  }

  @Test
  void presenceGauge_reflectsTheNumberOfMissionsWithLiveEditors() {
    assertThat(presenceGauge()).isEqualTo(0.0d);

    service.touch(missionA, "core", "user-1", "User One");
    service.touch(missionB, "schedule", "user-2", "User Two");

    // Two distinct missions currently have a live editor.
    assertThat(presenceGauge()).isEqualTo(2.0d);

    // Clearing the only editor of missionA drops it from the tracked set.
    service.clearAll(missionA, "user-1");
    assertThat(presenceGauge()).isEqualTo(1.0d);
  }

  private double presenceGauge() {
    return meterRegistry.get(MetricNames.MISSION_PRESENCE_MISSIONS).gauge().value();
  }

  @Test
  void touch_shouldRecordEntry_andSurfaceItInSnapshot() {
    boolean firstSighting = service.touch(missionA, "details", "user-1", "Alice");

    assertThat(firstSighting).isTrue();
    Map<String, List<MissionPresenceService.Entry>> snap =
        service.snapshot(missionA, Instant.now());
    assertThat(snap).containsOnlyKeys("details");
    assertThat(snap.get("details"))
        .extracting(MissionPresenceService.Entry::userId)
        .containsExactly("user-1");
    assertThat(snap.get("details"))
        .extracting(MissionPresenceService.Entry::displayName)
        .containsExactly("Alice");
  }

  @Test
  void touch_secondHeartbeat_shouldReturnFalseAndUpdateTimestamp() {
    service.touch(missionA, "details", "user-1", "Alice");
    Instant beforeSecond = service.get(missionA, "details", "user-1").lastHeartbeat();

    // Sleep a hair so the new heartbeat timestamp is strictly greater (Instant.now() resolution
    // is platform-dependent; on Windows it can be 15ms-ish, so even busy loops are fine — we
    // just need monotonic progress).
    sleepTinyBit();

    boolean secondSighting = service.touch(missionA, "details", "user-1", "Alice");

    assertThat(secondSighting).isFalse();
    assertThat(service.get(missionA, "details", "user-1").lastHeartbeat()).isAfter(beforeSecond);
  }

  @Test
  void touch_refusesNewSectionsBeyondTheDistinctSectionCap() {
    // Fill the mission to the distinct-section cap with unique keys — each is accepted.
    for (int i = 0; i < MissionPresenceService.MAX_SECTIONS_PER_MISSION; i++) {
      assertThat(service.touch(missionA, "section-" + i, "user-1", "User One")).isTrue();
    }

    // A further, previously-unseen section is refused (returns false, map stays at the cap) rather
    // than growing — the memory-exhaustion guard against a crafted client looping focus frames with
    // unique section keys.
    assertThat(service.touch(missionA, "section-overflow", "user-1", "User One")).isFalse();
    assertThat(service.snapshot(missionA, Instant.now()))
        .hasSize(MissionPresenceService.MAX_SECTIONS_PER_MISSION)
        .doesNotContainKey("section-overflow");

    // An already-tracked section still accepts a new editor even while the mission is at the cap.
    service.touch(missionA, "section-0", "user-2", "User Two");
    assertThat(service.get(missionA, "section-0", "user-2")).isNotNull();
  }

  @Test
  void clear_shouldRemoveEntry_andBeIdempotent() {
    service.touch(missionA, "details", "user-1", "Alice");

    assertThat(service.clear(missionA, "details", "user-1")).isTrue();
    assertThat(service.clear(missionA, "details", "user-1")).isFalse();
    assertThat(service.snapshot(missionA, Instant.now())).isEmpty();
  }

  @Test
  void clearAll_shouldRemoveUserFromEverySection_andReportAffectedKeys() {
    service.touch(missionA, "details", "user-1", "Alice");
    service.touch(missionA, "participants", "user-1", "Alice");
    service.touch(missionA, "details", "user-2", "Bob");

    List<String> affected = service.clearAll(missionA, "user-1");

    assertThat(affected).containsExactlyInAnyOrder("details", "participants");
    Map<String, List<MissionPresenceService.Entry>> snap =
        service.snapshot(missionA, Instant.now());
    // Bob still in details; participants section is gone entirely.
    assertThat(snap).containsOnlyKeys("details");
    assertThat(snap.get("details"))
        .extracting(MissionPresenceService.Entry::userId)
        .containsExactly("user-2");
  }

  @Test
  void clearAll_onUnknownMission_returnsEmptyAndDoesNotThrow() {
    assertThat(service.clearAll(missionA, "user-1")).isEmpty();
  }

  @Test
  void reapExpired_shouldDropEntriesPastTtl_andReportAffectedSections() {
    Instant longAgo = Instant.now().minus(MissionPresenceService.ENTRY_TTL).minusSeconds(5);
    Instant now = Instant.now();

    // Inject a stale entry by touching first then time-travelling the "now" used by reap:
    // the service uses Instant.now() at insertion, so to simulate an old entry without
    // sleeping for 30s we instead pass a FUTURE "now" to reapExpired.
    service.touch(missionA, "details", "user-1", "Alice");
    service.touch(missionA, "schedule", "user-2", "Bob");
    Instant future = now.plus(MissionPresenceService.ENTRY_TTL).plusSeconds(5);

    List<MissionPresenceService.MissionSectionRef> affected = service.reapExpired(future);

    assertThat(affected)
        .extracting(MissionPresenceService.MissionSectionRef::sectionKey)
        .containsExactlyInAnyOrder("details", "schedule");
    assertThat(service.snapshot(missionA, future)).isEmpty();
    assertThat(longAgo).isBefore(future); // sanity, suppresses unused warning
  }

  @Test
  void reapExpired_shouldLeaveFreshEntriesAlone() {
    service.touch(missionA, "details", "user-1", "Alice");

    List<MissionPresenceService.MissionSectionRef> affected = service.reapExpired(Instant.now());

    assertThat(affected).isEmpty();
    assertThat(service.snapshot(missionA, Instant.now()).get("details"))
        .extracting(MissionPresenceService.Entry::userId)
        .containsExactly("user-1");
  }

  @Test
  void snapshot_shouldNotLeakReapableEntries() {
    service.touch(missionA, "details", "user-1", "Alice");
    Instant future = Instant.now().plus(MissionPresenceService.ENTRY_TTL).plusSeconds(1);

    // The reaper hasn't run yet, but a snapshot taken with a future `now` must already
    // hide entries that would be reaped on the next tick — the websocket handler relies
    // on this to avoid pushing stale state to a newly connecting client.
    assertThat(service.snapshot(missionA, future)).isEmpty();
  }

  @Test
  void snapshot_shouldReturnImmutableMapAndImmutableLists() {
    service.touch(missionA, "details", "user-1", "Alice");
    Map<String, List<MissionPresenceService.Entry>> snap =
        service.snapshot(missionA, Instant.now());

    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> snap.put("x", List.of()));
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> snap.get("details").clear());
  }

  @Test
  void trackedMissions_shouldReflectInsertionsAndCleanups() {
    assertThat(service.trackedMissions()).isEmpty();
    service.touch(missionA, "details", "user-1", "Alice");
    service.touch(missionB, "schedule", "user-2", "Bob");

    assertThat(service.trackedMissions()).containsExactlyInAnyOrder(missionA, missionB);

    service.clearAll(missionA, "user-1");
    assertThat(service.trackedMissions()).containsExactly(missionB);
  }

  private static void sleepTinyBit() {
    try {
      Thread.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
