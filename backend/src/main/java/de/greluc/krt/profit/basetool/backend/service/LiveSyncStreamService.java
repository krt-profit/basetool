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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registry of the app's open live-sync streams and the only writer of {@code changed} frames to
 * them (ADR-0143). Performs no access control; topics arrive already authorized.
 *
 * <p>Subscriptions are indexed by topic and by subscriber, and removed from both only through
 * {@link #retire}, at most once. Frames go into a per-stream bounded queue ({@value
 * #MAX_QUEUED_FRAMES} frames) drained off the caller's thread:
 *
 * <ul>
 *   <li>at most one drain per stream, so per-stream order is preserved and the {@code subscribed}
 *       event is always first;
 *   <li>a frame offered to a full queue is dropped and counted in {@code
 *       basetool_livesync_frames_dropped_total{event}};
 *   <li>each drain runs on its own virtual thread, so a blocked write delays no other stream.
 * </ul>
 */
@Service
@Slf4j
public class LiveSyncStreamService {

  /**
   * Emitter lifetime; long enough to avoid frequent re-authorization, short enough to collect
   * streams stranded by a silently dropped connection.
   */
  private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

  /** Open streams one member may hold at once; reaching it evicts the member's oldest stream. */
  static final int MAX_STREAMS_PER_SUB = 4;

  /** Frames one stream may have queued before further frames are dropped. */
  static final int MAX_QUEUED_FRAMES = 64;

  private final Map<String, Set<Subscription>> byTopic = new ConcurrentHashMap<>();
  private final Map<UUID, Queue<Subscription>> bySub = new ConcurrentHashMap<>();
  private final MeterRegistry meterRegistry;

  /** Where the drain tasks run; a direct executor in unit tests, virtual threads otherwise. */
  private final Executor deliveryExecutor;

  /** The executor this instance created and must shut down, or {@code null} when injected. */
  @Nullable private final ExecutorService ownedExecutor;

  /**
   * Builds the registry with its own virtual-thread delivery executor and binds the gauges.
   *
   * @param meterRegistry registry the gauges and the failure / drop counters bind to
   */
  @Autowired
  public LiveSyncStreamService(@NotNull MeterRegistry meterRegistry) {
    this(
        meterRegistry,
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("livesync-deliver-", 0).factory()),
        true);
  }

  /**
   * Builds the registry over a given delivery executor.
   *
   * @param meterRegistry registry the gauges and the failure / drop counters bind to
   * @param deliveryExecutor where the per-stream drain tasks run; not shut down by this instance
   */
  protected LiveSyncStreamService(
      @NotNull MeterRegistry meterRegistry, @NotNull Executor deliveryExecutor) {
    this(meterRegistry, deliveryExecutor, false);
  }

  /**
   * Shared constructor body.
   *
   * @param meterRegistry registry the gauges and counters bind to
   * @param deliveryExecutor where the drain tasks run
   * @param owned whether this instance created the executor and so must shut it down
   */
  private LiveSyncStreamService(
      @NotNull MeterRegistry meterRegistry, @NotNull Executor deliveryExecutor, boolean owned) {
    this.meterRegistry = meterRegistry;
    this.deliveryExecutor = deliveryExecutor;
    this.ownedExecutor = owned ? (ExecutorService) deliveryExecutor : null;
    Gauge.builder(
            MetricNames.LIVESYNC_STREAMS,
            bySub,
            map -> map.values().stream().mapToInt(Queue::size).sum())
        .description("Open app live-sync SSE streams, summed across all members.")
        .register(meterRegistry);
    Gauge.builder(
            MetricNames.LIVESYNC_FRAMES_QUEUED,
            bySub,
            map ->
                map.values().stream()
                    .flatMap(Queue::stream)
                    .mapToInt(subscription -> subscription.pending().size())
                    .sum())
        .description("Frames waiting in the app live-sync streams' delivery queues, summed.")
        .register(meterRegistry);
  }

  /**
   * Stops the delivery executor this instance created, letting drains already running finish.
   * Frames still queued are abandoned with their streams — the container is closing every emitter
   * at shutdown anyway.
   */
  @PreDestroy
  public void shutdownDelivery() {
    if (ownedExecutor != null) {
      ownedExecutor.shutdown();
    }
  }

  /**
   * Opens a stream for one member over an already-authorized topic set. The first event is {@code
   * subscribed}, naming the accepted topics; reaching the per-member cap evicts the oldest stream.
   *
   * @param sub the caller's Keycloak {@code sub}
   * @param topics the topics the caller was allowed to join; must not be empty
   * @return the emitter, to be returned from the controller
   * @throws IllegalArgumentException if {@code topics} is empty
   */
  @NotNull
  public SseEmitter subscribe(@NotNull UUID sub, @NotNull List<LiveSyncTopic> topics) {
    if (topics.isEmpty()) {
      throw new IllegalArgumentException("A live-sync stream needs at least one accepted topic");
    }
    Subscription subscription = new Subscription(sub, canonicalTopics(topics), newEmitter());
    send(subscription, MetricNames.LIVESYNC_EVENT_SUBSCRIBED, subscribedPayload(subscription));
    List<Subscription> evicted = new ArrayList<>();
    bySub.compute(
        sub,
        (key, queue) -> {
          Queue<Subscription> streams = (queue != null) ? queue : new ConcurrentLinkedQueue<>();
          while (streams.size() >= MAX_STREAMS_PER_SUB) {
            Subscription oldest = streams.poll();
            if (oldest == null) {
              break;
            }
            evicted.add(oldest);
          }
          streams.add(subscription);
          return streams;
        });
    for (String topic : subscription.topics()) {
      byTopic.computeIfAbsent(topic, key -> ConcurrentHashMap.newKeySet()).add(subscription);
    }
    for (Subscription old : evicted) {
      meterRegistry.counter(MetricNames.LIVESYNC_STREAMS_EVICTED).increment();
      log.debug("Evicting oldest live-sync stream of {}: cap {} reached", sub, MAX_STREAMS_PER_SUB);
      retire(old, true);
    }

    SseEmitter emitter = subscription.emitter();
    emitter.onCompletion(() -> retire(subscription, false));
    emitter.onTimeout(() -> retire(subscription, true));
    emitter.onError(error -> retire(subscription, false));
    return emitter;
  }

  /**
   * Queues one {@code changed} frame for every stream in a room.
   *
   * @param topic the room
   * @param sections the section keys, non-empty and already whitelisted
   */
  public void deliver(@NotNull LiveSyncTopic topic, @NotNull List<String> sections) {
    Set<Subscription> room = byTopic.get(topic.canonical());
    if (room == null || room.isEmpty() || sections.isEmpty()) {
      return;
    }
    String payload = changedPayload(topic, sections);
    for (Subscription subscription : room) {
      send(subscription, MetricNames.LIVESYNC_EVENT_CHANGED, payload);
    }
    meterRegistry
        .counter(
            MetricNames.LIVESYNC_DELIVERED,
            MetricNames.TAG_TOPIC_CLASS,
            topic.topicClass().metricLabel())
        .increment();
  }

  /**
   * Keeps idle streams alive through proxies and NATs that drop a silent connection.
   *
   * <p>Same cadence and same property name shape as the notification stream's heartbeat: an app in
   * the background can sit for minutes without a single frame, and the first sign that the
   * connection died must not be a missed change.
   */
  @Scheduled(fixedRateString = "${app.live-sync.sse.heartbeat-interval:PT20S}")
  public void heartbeat() {
    bySub.forEach(
        (sub, streams) ->
            streams.forEach(
                subscription -> send(subscription, MetricNames.LIVESYNC_EVENT_HEARTBEAT, "ok")));
  }

  /**
   * Creates the emitter.
   *
   * @return a fresh emitter with the standard timeout; overridable so a test can inject a double
   *     without opening a real async context
   */
  @NotNull
  protected SseEmitter newEmitter() {
    return new SseEmitter(EMITTER_TIMEOUT_MS);
  }

  /**
   * Queues one event for a stream and ensures a drain will write it off this thread. A retired
   * stream takes nothing; a full queue drops and counts the frame.
   *
   * @param subscription the target stream
   * @param event the SSE event name
   * @param data the event payload
   */
  private void send(
      @NotNull Subscription subscription, @NotNull String event, @NotNull String data) {
    if (subscription.retired().get()) {
      return;
    }
    if (!subscription.pending().offer(new Frame(event, data))) {
      meterRegistry
          .counter(MetricNames.LIVESYNC_FRAMES_DROPPED, MetricNames.TAG_EVENT, event)
          .increment();
      log.debug(
          "Dropping a '{}' frame for the live-sync stream of {}: {} frames already queued",
          event,
          subscription.sub(),
          MAX_QUEUED_FRAMES);
      return;
    }
    scheduleDrain(subscription);
  }

  /**
   * Starts a drain for {@code subscription} unless one is already running — the one-drain-per-
   * stream rule that keeps the stream's frames in order.
   *
   * @param subscription the stream with frames waiting
   */
  private void scheduleDrain(@NotNull Subscription subscription) {
    if (!subscription.draining().compareAndSet(false, true)) {
      return;
    }
    try {
      deliveryExecutor.execute(() -> drain(subscription));
    } catch (RejectedExecutionException e) {
      subscription.draining().set(false);
      log.debug("Live-sync delivery executor refused a drain; retiring the stream", e);
      retire(subscription, false);
    }
  }

  /**
   * Writes a stream's queued frames in order until the queue is empty, retiring the stream on the
   * first failed write.
   *
   * @param subscription the stream to drain
   */
  private void drain(@NotNull Subscription subscription) {
    try {
      Frame frame;
      while ((frame = subscription.pending().poll()) != null) {
        if (subscription.retired().get() || !write(subscription, frame)) {
          subscription.pending().clear();
          return;
        }
      }
    } finally {
      subscription.draining().set(false);
    }
    if (!subscription.pending().isEmpty() && !subscription.retired().get()) {
      scheduleDrain(subscription);
    }
  }

  /**
   * Writes one frame to the stream's emitter, retiring the stream if the write fails.
   *
   * @param subscription the target stream
   * @param frame the frame to write
   * @return {@code true} when the frame was written, {@code false} when the stream was retired
   */
  private boolean write(@NotNull Subscription subscription, @NotNull Frame frame) {
    try {
      subscription.emitter().send(SseEmitter.event().name(frame.event()).data(frame.data()));
      return true;
    } catch (IOException | RuntimeException e) {
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_SEND_FAILURES,
              MetricNames.TAG_EVENT,
              frame.event(),
              MetricNames.TAG_CAUSE,
              SseSendFailureCause.tagOf(e))
          .increment();
      log.debug(
          "Dropping live-sync stream of {} after a failed '{}' push",
          subscription.sub(),
          frame.event(),
          e);
      retire(subscription, false);
      return false;
    }
  }

  /**
   * Removes a stream from both indices, at most once, and optionally completes it.
   *
   * @param subscription the stream to retire
   * @param complete whether to complete the emitter; {@code false} from inside its own completion
   *     callback
   */
  private void retire(@NotNull Subscription subscription, boolean complete) {
    if (!subscription.retired().compareAndSet(false, true)) {
      return;
    }
    for (String topic : subscription.topics()) {
      byTopic.computeIfPresent(
          topic,
          (key, room) -> {
            room.remove(subscription);
            return room.isEmpty() ? null : room;
          });
    }
    bySub.computeIfPresent(
        subscription.sub(),
        (key, streams) -> {
          streams.remove(subscription);
          return streams.isEmpty() ? null : streams;
        });
    if (complete) {
      try {
        subscription.emitter().complete();
      } catch (RuntimeException e) {
        log.debug("Live-sync emitter completion raced its own teardown", e);
      }
    }
  }

  /**
   * Renders the {@code subscribed} payload.
   *
   * @param subscription the stream being opened
   * @return a JSON object naming the accepted topics
   */
  @NotNull
  private static String subscribedPayload(@NotNull Subscription subscription) {
    StringBuilder json = new StringBuilder("{\"topics\":[");
    boolean first = true;
    for (String topic : subscription.topics()) {
      if (!first) {
        json.append(',');
      }
      json.append('"').append(topic).append('"');
      first = false;
    }
    return json.append("]}").toString();
  }

  /**
   * Renders a {@code changed} JSON payload by hand; safe because topic and sections are canonical,
   * whitelisted strings.
   *
   * @param topic the room
   * @param sections the whitelisted section keys
   * @return the JSON frame
   */
  @NotNull
  private static String changedPayload(
      @NotNull LiveSyncTopic topic, @NotNull List<String> sections) {
    StringBuilder json =
        new StringBuilder("{\"topic\":\"").append(topic.canonical()).append("\",\"sections\":[");
    for (int i = 0; i < sections.size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(sections.get(i)).append('"');
    }
    return json.append("]}").toString();
  }

  /**
   * Reduces the authorized topics to their canonical strings, de-duplicated, order preserved.
   *
   * @param topics the accepted topics
   * @return the room keys this stream belongs to
   */
  @NotNull
  @UnmodifiableView
  private static Set<String> canonicalTopics(@NotNull List<LiveSyncTopic> topics) {
    Set<String> ordered = new LinkedHashSet<>();
    for (LiveSyncTopic topic : topics) {
      ordered.add(topic.canonical());
    }
    return Collections.unmodifiableSet(ordered);
  }

  /**
   * One open stream, compared by identity so two equal-looking streams stay independently
   * removable.
   *
   * @param sub the member holding the stream
   * @param topics the canonical room keys it belongs to
   * @param emitter the emitter frames are written to
   * @param retired one-shot guard so the stream leaves both indices exactly once
   * @param pending the frames waiting for the drain, bounded at {@link #MAX_QUEUED_FRAMES}
   * @param draining set while a drain task owns this stream, so at most one writes to it
   */
  private record Subscription(
      @NotNull UUID sub,
      @NotNull Set<String> topics,
      @NotNull SseEmitter emitter,
      @NotNull AtomicBoolean retired,
      @NotNull Queue<Frame> pending,
      @NotNull AtomicBoolean draining) {

    /**
     * Builds a fresh, not-yet-retired subscription with an empty delivery queue.
     *
     * @param sub the member holding the stream
     * @param topics the canonical room keys
     * @param emitter the emitter
     */
    Subscription(@NotNull UUID sub, @NotNull Set<String> topics, @NotNull SseEmitter emitter) {
      this(
          sub,
          topics,
          emitter,
          new AtomicBoolean(false),
          new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES),
          new AtomicBoolean(false));
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object other) {
      return this == other;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }

  /**
   * One SSE event waiting in a stream's delivery queue.
   *
   * @param event the SSE event name
   * @param data the event payload
   */
  private record Frame(@NotNull String event, @NotNull String data) {}
}
