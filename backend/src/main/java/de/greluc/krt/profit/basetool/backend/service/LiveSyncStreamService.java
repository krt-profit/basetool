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
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registry of the app's open live-sync streams, and the only place a {@code changed} frame is
 * written to one (ADR-0143).
 *
 * <p>A stream is opened by {@link #subscribe(UUID, List)} with the topic set already authorized;
 * this class does no access control of its own. It keeps two indices over the same subscriptions —
 * by topic, so a frame reaches one room without walking every stream, and by subscriber, so the
 * per-member cap can evict the oldest. Both are updated through {@link #retire} alone, guarded by a
 * one-shot flag, because a stream can be retired concurrently by a failed send, its own timeout,
 * the servlet container's completion callback and an eviction, and a half-removed subscription
 * would keep a dead emitter in a room forever.
 *
 * <p>Modelled on {@link NotificationStreamService} down to the timeout, the heartbeat and the
 * failure tagging — the same transport with the same hazards, in front of nginx and a mobile NAT.
 */
@Service
@Slf4j
public class LiveSyncStreamService {

  /**
   * Emitter lifetime. Long, because the cost of a re-open is a fresh authorization of every topic;
   * short enough that a stream stranded by a NAT that dropped the connection without an RST is
   * eventually collected rather than held until redeploy.
   */
  private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

  /**
   * Open streams one member may hold at once.
   *
   * <p>Far above real use — the app shows one screen and opens one stream — and low enough that a
   * crafted client cannot pin thousands of emitters. Reaching it evicts the oldest rather than
   * refusing the newest: the newest is the screen the member is actually looking at.
   */
  static final int MAX_STREAMS_PER_SUB = 4;

  private final Map<String, Set<Subscription>> byTopic = new ConcurrentHashMap<>();
  private final Map<UUID, Queue<Subscription>> bySub = new ConcurrentHashMap<>();
  private final MeterRegistry meterRegistry;

  /**
   * Builds the registry and binds the open-stream gauge.
   *
   * @param meterRegistry registry the stream gauge and the failure counters bind to
   */
  public LiveSyncStreamService(@NotNull MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    Gauge.builder(
            MetricNames.LIVESYNC_STREAMS,
            bySub,
            map -> map.values().stream().mapToInt(Queue::size).sum())
        .description("Open app live-sync SSE streams, summed across all members.")
        .register(meterRegistry);
  }

  /**
   * Opens a stream for one member over an already-authorized topic set.
   *
   * <p>The {@code subscribed} event goes out first and names the accepted topics, so the client can
   * tell "live" from "the server dropped this room" without waiting for a frame that will never
   * come. A caller that reaches the per-member cap evicts its own oldest stream.
   *
   * @param sub the caller's Keycloak {@code sub}
   * @param topics the topics the caller was allowed to join; must not be empty
   * @return the emitter, to be returned from the controller
   * @throws IllegalArgumentException if {@code topics} is empty — an unauthorized stream is a 403
   *     at the controller, never an emitter with no rooms
   */
  @NotNull
  public SseEmitter subscribe(@NotNull UUID sub, @NotNull List<LiveSyncTopic> topics) {
    if (topics.isEmpty()) {
      throw new IllegalArgumentException("A live-sync stream needs at least one accepted topic");
    }
    Subscription subscription = new Subscription(sub, canonicalTopics(topics), newEmitter());
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
    send(subscription, MetricNames.LIVESYNC_EVENT_SUBSCRIBED, subscribedPayload(subscription));
    return emitter;
  }

  /**
   * Writes one {@code changed} frame to every stream in a room.
   *
   * <p>Sections are the caller's business: they arrive already clipped to the topic class's
   * whitelist. A frame whose sections clipped to nothing never reaches here — an empty section list
   * would tell a client "something changed" without saying what, which every client would then have
   * to interpret as "reload everything".
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
   * Sends one event, retiring the stream if the write fails.
   *
   * @param subscription the target stream
   * @param event the SSE event name
   * @param data the event payload
   */
  private void send(
      @NotNull Subscription subscription, @NotNull String event, @NotNull String data) {
    try {
      subscription.emitter().send(SseEmitter.event().name(event).data(data));
    } catch (IOException | RuntimeException e) {
      meterRegistry
          .counter(
              MetricNames.LIVESYNC_SEND_FAILURES,
              MetricNames.TAG_EVENT,
              event,
              MetricNames.TAG_CAUSE,
              causeTag(e))
          .increment();
      log.debug(
          "Dropping live-sync stream of {} after a failed '{}' push", subscription.sub(), event, e);
      retire(subscription, false);
    }
  }

  /**
   * Removes a stream from both indices, at most once, and optionally completes it.
   *
   * @param subscription the stream to retire
   * @param complete whether to complete the emitter — {@code false} when the container is already
   *     tearing it down, since completing an emitter from inside its own completion callback throws
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
   * Renders a {@code changed} payload.
   *
   * <p>Hand-built rather than mapped, and safe to build that way precisely because neither part is
   * free text: the topic is a canonical string this backend produced from its own registry, and
   * every section has already been matched against a whitelist of literals. Nothing that reaches
   * here can carry a quote or a backslash.
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
  private static Set<String> canonicalTopics(@NotNull List<LiveSyncTopic> topics) {
    Map<String, Boolean> ordered = new LinkedHashMap<>();
    for (LiveSyncTopic topic : topics) {
      ordered.put(topic.canonical(), Boolean.TRUE);
    }
    return Set.copyOf(ordered.keySet());
  }

  /**
   * Maps a send failure onto its bounded metric tag.
   *
   * @param cause the throwable the send raised
   * @return the {@code cause} tag value
   */
  @NotNull
  private static String causeTag(@NotNull Throwable cause) {
    if (cause instanceof IOException) {
      return MetricNames.CAUSE_IO;
    }
    if (cause instanceof IllegalStateException) {
      return MetricNames.CAUSE_ILLEGAL_STATE;
    }
    return MetricNames.CAUSE_OTHER;
  }

  /**
   * One open stream.
   *
   * <p>Identity-compared on purpose — two streams of the same member over the same topics are
   * different rooms' members and must be removable independently — which is what the default record
   * equality would break, so it is overridden back to identity below.
   *
   * @param sub the member holding the stream
   * @param topics the canonical room keys it belongs to
   * @param emitter the emitter frames are written to
   * @param retired one-shot guard so the stream leaves both indices exactly once
   */
  private record Subscription(
      @NotNull UUID sub,
      @NotNull Set<String> topics,
      @NotNull SseEmitter emitter,
      @NotNull AtomicBoolean retired) {

    /**
     * Builds a fresh, not-yet-retired subscription.
     *
     * @param sub the member holding the stream
     * @param topics the canonical room keys
     * @param emitter the emitter
     */
    Subscription(@NotNull UUID sub, @NotNull Set<String> topics, @NotNull SseEmitter emitter) {
      this(sub, topics, emitter, new AtomicBoolean(false));
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
}
