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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Room bookkeeping and delivery of the app's live-sync SSE registry (ADR-0143).
 *
 * <p>Most cases run the service over a direct executor, so a drain happens inside the call that
 * queued the frame and the recorded order is observable without waiting. The BE-PERF-13 cases at
 * the end run it over real virtual threads, because what they prove — a blocked subscriber does not
 * hold the publisher, order survives concurrency, a full queue drops — only exists off-thread.
 */
class LiveSyncStreamServiceTest {

  private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final LiveSyncTopic INVENTORY = LiveSyncTopic.parse("inventory");
  private static final LiveSyncTopic BOARD = LiveSyncTopic.parse("materialboard");

  private MeterRegistry meterRegistry;
  private RecordingStreamService service;
  private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service = new RecordingStreamService(meterRegistry, Runnable::run);
  }

  @AfterEach
  void tearDown() {
    virtualThreads.shutdownNow();
  }

  @Test
  @DisplayName("a new stream is told which topics were accepted, before anything else")
  void subscribeAnnouncesTheAcceptedTopics() {
    service.subscribe(ALICE, List.of(INVENTORY, BOARD));

    RecordingEmitter emitter = service.emitters().getFirst();
    assertThat(emitter.events()).hasSize(1);
    assertThat(emitter.events().getFirst().name()).isEqualTo("subscribed");
    assertThat(emitter.events().getFirst().data()).contains("inventory").contains("materialboard");
  }

  @Test
  @DisplayName("a frame reaches only the streams in that room")
  void deliveryIsScopedToTheRoom() {
    service.subscribe(ALICE, List.of(INVENTORY));
    service.subscribe(BOB, List.of(BOARD));

    service.deliver(INVENTORY, List.of("stock"));

    assertThat(service.emitters().get(0).eventNames()).containsExactly("subscribed", "changed");
    assertThat(service.emitters().get(1).eventNames()).containsExactly("subscribed");
  }

  @Test
  @DisplayName("the changed frame names the room and only its whitelisted sections")
  void changedFrameCarriesTopicAndSections() {
    service.subscribe(ALICE, List.of(INVENTORY));

    service.deliver(INVENTORY, List.of("stock"));

    assertThat(service.emitters().getFirst().events().get(1).data())
        .isEqualTo("{\"topic\":\"inventory\",\"sections\":[\"stock\"]}");
  }

  @Test
  @DisplayName("an empty section list is never relayed")
  void emptySectionsAreNotRelayed() {
    service.subscribe(ALICE, List.of(INVENTORY));

    service.deliver(INVENTORY, List.of());

    assertThat(service.emitters().getFirst().eventNames()).containsExactly("subscribed");
  }

  @Test
  @DisplayName("one stream may join several rooms and hears from each")
  void oneStreamJoinsSeveralRooms() {
    service.subscribe(ALICE, List.of(INVENTORY, BOARD));

    service.deliver(INVENTORY, List.of("stock"));
    service.deliver(BOARD, List.of("board"));

    assertThat(service.emitters().getFirst().eventNames())
        .containsExactly("subscribed", "changed", "changed");
  }

  @Test
  @DisplayName("reaching the per-member cap evicts the oldest stream, not the newest")
  void theCapEvictsTheOldest() {
    List<SseEmitter> opened = new ArrayList<>();
    for (int i = 0; i < LiveSyncStreamService.MAX_STREAMS_PER_SUB + 1; i++) {
      opened.add(service.subscribe(ALICE, List.of(INVENTORY)));
    }

    assertThat(meterRegistry.counter(MetricNames.LIVESYNC_STREAMS_EVICTED).count()).isEqualTo(1.0);
    service.deliver(INVENTORY, List.of("stock"));
    assertThat(service.emitters().getFirst().eventNames()).containsExactly("subscribed");
    assertThat(service.emitters().getLast().eventNames()).containsExactly("subscribed", "changed");
    assertThat(opened).hasSize(LiveSyncStreamService.MAX_STREAMS_PER_SUB + 1);
  }

  @Test
  @DisplayName("a failed write retires the stream, so a dead emitter never stays in a room")
  void aFailedSendRetiresTheStream() {
    service.subscribe(ALICE, List.of(INVENTORY));
    service.emitters().getFirst().failFromNowOn();

    service.deliver(INVENTORY, List.of("stock"));
    service.deliver(INVENTORY, List.of("stock"));

    assertThat(service.emitters().getFirst().failedSends()).isEqualTo(1);
    assertThat(meterRegistry.get(MetricNames.LIVESYNC_SEND_FAILURES).counters()).isNotEmpty();
  }

  @Test
  @DisplayName("the open-stream gauge follows subscribe and retire")
  void gaugeTracksOpenStreams() {
    assertThat(gauge()).isZero();
    service.subscribe(ALICE, List.of(INVENTORY));
    service.subscribe(BOB, List.of(INVENTORY));
    assertThat(gauge()).isEqualTo(2.0);

    service.emitters().getFirst().failFromNowOn();
    service.deliver(INVENTORY, List.of("stock"));

    assertThat(gauge()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("the heartbeat reaches every open stream")
  void heartbeatReachesEveryStream() {
    service.subscribe(ALICE, List.of(INVENTORY));
    service.subscribe(BOB, List.of(BOARD));

    service.heartbeat();

    assertThat(service.emitters().getFirst().eventNames())
        .containsExactly("subscribed", "heartbeat");
    assertThat(service.emitters().getLast().eventNames())
        .containsExactly("subscribed", "heartbeat");
  }

  @Test
  @DisplayName("a stream with no accepted topic is a programming error, not an empty emitter")
  void emptyTopicSetIsRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> service.subscribe(ALICE, List.of()));
  }

  @Test
  @DisplayName(
      "BE-PERF-13: a subscriber whose write blocks does not hold the publisher or its peers")
  void aBlockedSubscriberDoesNotHoldThePublisher() throws InterruptedException {
    meterRegistry = new SimpleMeterRegistry();
    RecordingStreamService async = new RecordingStreamService(meterRegistry, virtualThreads);
    async.subscribe(ALICE, List.of(INVENTORY));
    async.subscribe(BOB, List.of(INVENTORY));
    RecordingEmitter stuck = async.emitters().getFirst();
    RecordingEmitter healthy = async.emitters().getLast();
    awaitEvents(stuck, 1);
    stuck.blockFromNowOn();

    assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> async.deliver(INVENTORY, List.of("stock")));

    awaitEvents(healthy, 2);
    assertThat(healthy.eventNames()).containsExactly("subscribed", "changed");
    assertThat(stuck.eventNames()).containsExactly("subscribed");

    stuck.unblock();
    awaitEvents(stuck, 2);
    assertThat(stuck.eventNames()).containsExactly("subscribed", "changed");
  }

  @Test
  @DisplayName("BE-PERF-13: frames reach one stream in the order they were published")
  void orderPerStreamSurvivesConcurrentDelivery() throws InterruptedException {
    meterRegistry = new SimpleMeterRegistry();
    RecordingStreamService async = new RecordingStreamService(meterRegistry, virtualThreads);
    async.subscribe(ALICE, List.of(INVENTORY));
    RecordingEmitter emitter = async.emitters().getFirst();

    for (int i = 0; i < 40; i++) {
      async.deliver(INVENTORY, List.of("s" + i));
    }

    awaitEvents(emitter, 41);
    assertThat(emitter.eventNames().getFirst()).isEqualTo("subscribed");
    for (int i = 0; i < 40; i++) {
      assertThat(emitter.events().get(i + 1).data()).contains("\"s" + i + "\"");
    }
  }

  @Test
  @DisplayName("BE-PERF-13: a full delivery queue drops and counts instead of growing")
  void aFullQueueDropsAndCounts() throws InterruptedException {
    meterRegistry = new SimpleMeterRegistry();
    RecordingStreamService async = new RecordingStreamService(meterRegistry, virtualThreads);
    async.subscribe(ALICE, List.of(INVENTORY));
    RecordingEmitter stuck = async.emitters().getFirst();
    awaitEvents(stuck, 1);
    stuck.blockFromNowOn();
    async.deliver(INVENTORY, List.of("first"));
    stuck.awaitBlocked();

    int overflow = 6;
    for (int i = 0; i < LiveSyncStreamService.MAX_QUEUED_FRAMES + overflow; i++) {
      async.deliver(INVENTORY, List.of("stock"));
    }

    assertThat(
            meterRegistry
                .get(MetricNames.LIVESYNC_FRAMES_DROPPED)
                .tag(MetricNames.TAG_EVENT, MetricNames.LIVESYNC_EVENT_CHANGED)
                .counter()
                .count())
        .isEqualTo(overflow);
    assertThat(meterRegistry.get(MetricNames.LIVESYNC_FRAMES_QUEUED).gauge().value())
        .isEqualTo(LiveSyncStreamService.MAX_QUEUED_FRAMES);

    stuck.unblock();
    awaitEvents(stuck, 2 + LiveSyncStreamService.MAX_QUEUED_FRAMES);
    assertThat(meterRegistry.get(MetricNames.LIVESYNC_FRAMES_QUEUED).gauge().value()).isZero();
  }

  /**
   * Waits until {@code emitter} has recorded at least {@code count} events.
   *
   * @param emitter the recording double
   * @param count the number of events to wait for
   * @throws InterruptedException if interrupted while waiting
   */
  private static void awaitEvents(RecordingEmitter emitter, int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (emitter.events().size() < count && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(emitter.events()).as("events after waiting").hasSizeGreaterThanOrEqualTo(count);
  }

  private double gauge() {
    return meterRegistry.get(MetricNames.LIVESYNC_STREAMS).gauge().value();
  }

  /** A service whose emitters are recording doubles rather than real async contexts. */
  private static final class RecordingStreamService extends LiveSyncStreamService {

    private final List<RecordingEmitter> emitters = new CopyOnWriteArrayList<>();

    RecordingStreamService(MeterRegistry meterRegistry, Executor deliveryExecutor) {
      super(meterRegistry, deliveryExecutor);
    }

    @Override
    protected SseEmitter newEmitter() {
      RecordingEmitter emitter = new RecordingEmitter();
      emitters.add(emitter);
      return emitter;
    }

    List<RecordingEmitter> emitters() {
      return emitters;
    }
  }

  /** An emitter that records what was written to it and can be made to fail on demand. */
  private static final class RecordingEmitter extends SseEmitter {

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private volatile boolean failing;
    private volatile int failedSends;
    private volatile CountDownLatch gate;
    private final CountDownLatch blocked = new CountDownLatch(1);

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      CountDownLatch current = gate;
      if (current != null) {
        blocked.countDown();
        try {
          current.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while blocked", e);
        }
      }
      if (failing) {
        failedSends++;
        throw new IOException("client gone");
      }
      StringBuilder wire = new StringBuilder();
      for (DataWithMediaType part : builder.build()) {
        wire.append(part.getData());
      }
      events.add(new Event(field(wire.toString(), "event:"), field(wire.toString(), "data:")));
    }

    /**
     * Reads one SSE field out of the reassembled wire form.
     *
     * @param wire the assembled event
     * @param marker the field marker, including its colon
     * @return the field's value, or the empty string if the event carries no such field
     */
    private static String field(String wire, String marker) {
      int start = wire.indexOf(marker);
      if (start < 0) {
        return "";
      }
      int from = start + marker.length();
      int end = wire.indexOf('\n', from);
      return (end < 0 ? wire.substring(from) : wire.substring(from, end)).trim();
    }

    @Override
    public void complete() {}

    void failFromNowOn() {
      failing = true;
    }

    /** Makes every further write park until {@link #unblock()} — a client that stopped reading. */
    void blockFromNowOn() {
      gate = new CountDownLatch(1);
    }

    /** Releases the writes parked by {@link #blockFromNowOn()}. */
    void unblock() {
      CountDownLatch current = gate;
      gate = null;
      if (current != null) {
        current.countDown();
      }
    }

    /** Waits until a write has actually parked in {@link #send}. */
    void awaitBlocked() throws InterruptedException {
      assertThat(blocked.await(5, TimeUnit.SECONDS)).as("a write parked").isTrue();
    }

    int failedSends() {
      return failedSends;
    }

    List<Event> events() {
      return events;
    }

    List<String> eventNames() {
      return events.stream().map(Event::name).toList();
    }
  }

  /** One captured SSE event. */
  private record Event(String name, String data) {}
}
