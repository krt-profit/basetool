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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Bounding and ordering of a client-published change signal (ADR-0143). */
@ExtendWith(MockitoExtension.class)
class LiveSyncRelayServiceTest {

  private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final LiveSyncTopic INVENTORY = LiveSyncTopic.parse("inventory");
  private static final LiveSyncTopic BOARD = LiveSyncTopic.parse("materialboard");

  @Mock private LiveSyncStreamService streamService;
  @Mock private LiveSyncFanout fanout;

  private MeterRegistry meterRegistry;
  private LiveSyncRelayService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service = new LiveSyncRelayService(streamService, fanout, meterRegistry);
  }

  @Test
  @DisplayName("an accepted frame is delivered locally before it is handed to the fan-out")
  void localDeliveryPrecedesTheFanout() {
    assertThat(service.publishFromClient(ALICE, INVENTORY, List.of("stock")))
        .isEqualTo(LiveSyncRelayService.Outcome.ACCEPTED);

    // The order is what makes a Redis outage cost peer delivery and nothing else.
    InOrder order = inOrder(streamService, fanout);
    order.verify(streamService).deliver(INVENTORY, List.of("stock"));
    order.verify(fanout).publish(INVENTORY, List.of("stock"));
  }

  @Test
  @DisplayName("sections outside the whitelist are dropped, and the rest still relayed")
  void unknownSectionsAreClipped() {
    service.publishFromClient(ALICE, INVENTORY, List.of("nonsense", "stock"));

    verify(streamService).deliver(INVENTORY, List.of("stock"));
  }

  @Test
  @DisplayName("a frame that clips to nothing is refused rather than relayed empty")
  void aFrameWithNoKnownSectionIsRefused() {
    assertThat(service.publishFromClient(ALICE, INVENTORY, List.of("crew", "queue")))
        .isEqualTo(LiveSyncRelayService.Outcome.NO_KNOWN_SECTIONS);

    verify(streamService, never())
        .deliver(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(fanout, never())
        .publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    assertThat(rejected(MetricNames.REASON_NO_SECTIONS)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("one member cannot outrun their own bucket")
  void theSubjectBucketBoundsOneMember() {
    long accepted = 0;
    for (int i = 0; i < LiveSyncRelayService.SUBJECT_BURST + 5; i++) {
      if (service.publishFromClient(ALICE, INVENTORY, List.of("stock"))
          == LiveSyncRelayService.Outcome.ACCEPTED) {
        accepted++;
      }
    }

    assertThat(accepted).isLessThanOrEqualTo(LiveSyncRelayService.SUBJECT_BURST + 1);
    assertThat(rejected(MetricNames.REASON_SUBJECT_BUCKET)).isPositive();
  }

  @Test
  @DisplayName("an exhausted member's bucket does not touch anybody else's")
  void bucketsAreHeldPerMember() {
    for (int i = 0; i < LiveSyncRelayService.SUBJECT_BURST + 5; i++) {
      service.publishFromClient(ALICE, INVENTORY, List.of("stock"));
    }

    // Bob has emitted nothing; Alice flooding must not cost him his own signal.
    assertThat(service.publishFromClient(BOB, BOARD, List.of("board")))
        .isEqualTo(LiveSyncRelayService.Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("a room's aggregate bucket bounds it even when every publisher stays under theirs")
  void theTopicBucketBoundsARoomAcrossPublishers() {
    // The bound that matters: many clients each well under their own limit can still flood one
    // global room's re-fetch fan-out, which is exactly what ADR-0094 sized the second bucket for.
    long accepted = 0;
    long attempts = LiveSyncRelayService.TOPIC_BURST + 40;
    for (int i = 0; i < attempts; i++) {
      UUID publisher = new UUID(0L, i);
      if (service.publishFromClient(publisher, INVENTORY, List.of("stock"))
          == LiveSyncRelayService.Outcome.ACCEPTED) {
        accepted++;
      }
    }

    assertThat(accepted).isLessThan(attempts);
    assertThat(rejected(MetricNames.REASON_TOPIC_BUCKET)).isPositive();
  }

  @Test
  @DisplayName("an accepted frame is counted under its topic class")
  void acceptedFramesAreCountedByTopicClass() {
    service.publishFromClient(ALICE, INVENTORY, List.of("stock"));

    assertThat(
            meterRegistry
                .counter(
                    MetricNames.LIVESYNC_PUBLISH_ACCEPTED,
                    MetricNames.TAG_TOPIC_CLASS,
                    "inventory_all")
                .count())
        .isEqualTo(1.0);
  }

  private double rejected(String reason) {
    return meterRegistry.get(MetricNames.LIVESYNC_PUBLISH_REJECTED).counters().stream()
        .filter(counter -> reason.equals(counter.getId().getTag(MetricNames.TAG_REASON)))
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }
}
