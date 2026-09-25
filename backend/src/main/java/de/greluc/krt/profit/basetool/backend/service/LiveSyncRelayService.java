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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Accepts a {@code changed} frame from an app client, rate-limits it and relays it, locally first,
 * then to peers (ADR-0143).
 *
 * <p>A per-subject and a per-topic token bucket bound the re-fetch load a frame triggers. A dropped
 * frame only delays a peer's refresh; no data is lost.
 */
@Service
@Slf4j
public class LiveSyncRelayService {

  /** Frames one member may emit in a burst. */
  static final long SUBJECT_BURST = 40L;

  /** Steady-state frames per second for one member. */
  static final long SUBJECT_REFILL_PER_SECOND = 20L;

  /** Frames one room may accept in a burst, across all publishers. */
  static final long TOPIC_BURST = 200L;

  /** Steady-state accepted frames per second for one room, across all publishers. */
  static final long TOPIC_REFILL_PER_SECOND = 100L;

  /** Subjects tracked before the least-recently-used are dropped, matching the request limiter. */
  private static final long MAX_TRACKED_SUBJECTS = 50_000L;

  /**
   * Maximum number of rooms tracked before the least-recently-used bucket is evicted; an evicted
   * bucket is recreated full.
   */
  private static final long MAX_TRACKED_TOPICS = 10_000L;

  private static final Duration SUBJECT_BUCKET_TTL = Duration.ofHours(1);
  private static final Duration TOPIC_BUCKET_TTL = Duration.ofMinutes(5);

  private final LiveSyncStreamService streamService;
  private final LiveSyncFanout fanout;
  private final MeterRegistry meterRegistry;
  private final Cache<UUID, Bucket> subjectBuckets;
  private final Cache<String, Bucket> topicBuckets;

  /**
   * Builds the relay.
   *
   * @param streamService the local emitter registry frames are delivered to first
   * @param fanout the seam that carries an accepted frame to peers
   * @param meterRegistry registry the accept/reject counters bind to
   */
  public LiveSyncRelayService(
      @NotNull LiveSyncStreamService streamService,
      @NotNull LiveSyncFanout fanout,
      @NotNull MeterRegistry meterRegistry) {
    this.streamService = streamService;
    this.fanout = fanout;
    this.meterRegistry = meterRegistry;
    this.subjectBuckets =
        Caffeine.newBuilder()
            .expireAfterAccess(SUBJECT_BUCKET_TTL)
            .maximumSize(MAX_TRACKED_SUBJECTS)
            .build();
    this.topicBuckets =
        Caffeine.newBuilder()
            .expireAfterAccess(TOPIC_BUCKET_TTL)
            .maximumSize(MAX_TRACKED_TOPICS)
            .build();
  }

  /**
   * Takes one frame from a client: clips sections, applies the per-subject and per-topic buckets,
   * delivers locally, then fans out.
   *
   * @param sub the emitting member's Keycloak {@code sub}
   * @param topic the room, already parsed
   * @param rawSections the sections as the client named them, untrusted
   * @return what happened, for the controller to map onto a status
   */
  @NotNull
  public Outcome publishFromClient(
      @NotNull UUID sub, @NotNull LiveSyncTopic topic, @NotNull List<String> rawSections) {
    String topicClass = topic.topicClass().metricLabel();
    List<String> sections = topic.topicClass().clipSections(rawSections);
    if (sections.isEmpty()) {
      count(MetricNames.LIVESYNC_PUBLISH_REJECTED, MetricNames.REASON_NO_SECTIONS, topicClass);
      return Outcome.NO_KNOWN_SECTIONS;
    }
    if (!subjectBuckets
        .get(sub, key -> newBucket(SUBJECT_BURST, SUBJECT_REFILL_PER_SECOND))
        .tryConsume(1)) {
      count(MetricNames.LIVESYNC_PUBLISH_REJECTED, MetricNames.REASON_SUBJECT_BUCKET, topicClass);
      log.debug("Live-sync publish refused: per-subject bucket exhausted for {}", sub);
      return Outcome.SUBJECT_RATE_LIMITED;
    }
    if (!topicBuckets
        .get(topic.canonical(), key -> newBucket(TOPIC_BURST, TOPIC_REFILL_PER_SECOND))
        .tryConsume(1)) {
      count(MetricNames.LIVESYNC_PUBLISH_REJECTED, MetricNames.REASON_TOPIC_BUCKET, topicClass);
      log.debug("Live-sync publish refused: per-topic bucket exhausted for {}", topic.canonical());
      return Outcome.TOPIC_RATE_LIMITED;
    }
    streamService.deliver(topic, sections);
    fanout.publish(topic, sections);
    meterRegistry
        .counter(MetricNames.LIVESYNC_PUBLISH_ACCEPTED, MetricNames.TAG_TOPIC_CLASS, topicClass)
        .increment();
    return Outcome.ACCEPTED;
  }

  /**
   * Builds a greedy-refill bucket.
   *
   * @param burst the capacity
   * @param refillPerSecond tokens added per second
   * @return the bucket
   */
  @NotNull
  private static Bucket newBucket(long burst, long refillPerSecond) {
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(burst)
                .refillGreedy(refillPerSecond, Duration.ofSeconds(1))
                .build())
        .build();
  }

  /**
   * Bumps a two-tag counter.
   *
   * @param metric the counter name
   * @param reason the bounded reason tag
   * @param topicClass the bounded topic-class tag
   */
  private void count(@NotNull String metric, @NotNull String reason, @NotNull String topicClass) {
    meterRegistry
        .counter(metric, MetricNames.TAG_REASON, reason, MetricNames.TAG_TOPIC_CLASS, topicClass)
        .increment();
  }

  /** What a client publish attempt resulted in. */
  public enum Outcome {

    /** Relayed locally and handed to the fan-out. */
    ACCEPTED,

    /** Every section named was outside the topic class's whitelist; the frame is not relayed. */
    NO_KNOWN_SECTIONS,

    /** The emitting member's own bucket is empty. */
    SUBJECT_RATE_LIMITED,

    /** The room's aggregate bucket is empty, across all publishers. */
    TOPIC_RATE_LIMITED
  }
}
