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

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicAuthorizer;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Decides whether the current caller may join one live-sync room (ADR-0143), asking the same
 * question the equivalent read asks.
 *
 * <p>The member and self rooms are decided here; every other kind by the one {@link
 * LiveSyncTopicAuthorizer} its owning module registers. A check that throws is treated as a
 * refusal.
 */
@Service
@Slf4j
public class LiveSyncSubscriptionAuthorizer {

  private final AuthHelperService authHelperService;
  private final MeterRegistry meterRegistry;
  private final Map<LiveSyncAuthorization, LiveSyncTopicAuthorizer> topicAuthorizers;

  /**
   * Indexes the module authorizers by the kinds they decide.
   *
   * @param authHelperService the caller's identity and roles
   * @param meterRegistry where the verdicts are counted
   * @param topicAuthorizers the module authorizers
   * @throws IllegalStateException if a kind other than {@code MEMBER} and {@code SELF} has no
   *     authorizer or more than one, or an authorizer claims {@code MEMBER} or {@code SELF}
   */
  public LiveSyncSubscriptionAuthorizer(
      @NotNull AuthHelperService authHelperService,
      @NotNull MeterRegistry meterRegistry,
      @NotNull List<LiveSyncTopicAuthorizer> topicAuthorizers) {
    this.authHelperService = authHelperService;
    this.meterRegistry = meterRegistry;
    this.topicAuthorizers = index(topicAuthorizers);
  }

  /**
   * Answers whether the current caller may subscribe to a topic, and counts the verdict.
   *
   * @param topic the room, already parsed against this backend's registry
   * @return {@code true} if the room may be opened for this caller
   */
  public boolean maySubscribe(@NotNull LiveSyncTopic topic) {
    Verdict verdict = evaluate(topic);
    meterRegistry
        .counter(
            MetricNames.LIVESYNC_SUBSCRIBE,
            MetricNames.TAG_TOPIC_CLASS,
            topic.topicClass().metricLabel(),
            MetricNames.TAG_OUTCOME,
            verdict.allowed() ? MetricNames.OUTCOME_ALLOWED : MetricNames.OUTCOME_DENIED,
            MetricNames.TAG_REASON,
            verdict.reason())
        .increment();
    return verdict.allowed();
  }

  /**
   * Counts a subscribe request naming a topic this backend's registry does not know (unlabelled
   * metric).
   */
  public void recordInvalidTopic() {
    meterRegistry.counter(MetricNames.LIVESYNC_INVALID_TOPIC).increment();
  }

  /**
   * Runs the class's check.
   *
   * @param topic the room
   * @return the verdict and why, before it is counted
   */
  @NotNull
  private Verdict evaluate(@NotNull LiveSyncTopic topic) {
    if (!authHelperService.isMemberOrAbove()) {
      return Verdict.refuse(MetricNames.SUBSCRIBE_DENY_AUTHZ);
    }
    try {
      LiveSyncAuthorization kind = topic.topicClass().authorization();
      boolean allowed =
          switch (kind) {
            case MEMBER -> true;
            case SELF ->
                topic.requiredResourceId().equals(authHelperService.currentUserId().orElse(null));
            case MISSION, OPERATION, JOB_ORDER, JOB_ORDER_QUEUE, REFINERY_ORDER, BANK_ACCOUNT ->
                topicAuthorizers.get(kind).mayJoin(topic);
          };
      return allowed ? Verdict.permit() : Verdict.refuse(MetricNames.SUBSCRIBE_DENY_AUTHZ);
    } catch (RuntimeException e) {
      log.debug("Live-sync subscribe refused for {} after a failed check", topic.canonical(), e);
      return Verdict.refuse(MetricNames.SUBSCRIBE_DENY_CHECK_FAILED);
    }
  }

  /**
   * Maps every delegated kind to its one authorizer.
   *
   * @param authorizers the module authorizers
   * @return the index, covering every kind except {@code MEMBER} and {@code SELF}
   * @throws IllegalStateException if the authorizers do not cover the delegated kinds exactly once
   */
  @NotNull
  private static Map<LiveSyncAuthorization, LiveSyncTopicAuthorizer> index(
      @NotNull List<LiveSyncTopicAuthorizer> authorizers) {
    Map<LiveSyncAuthorization, LiveSyncTopicAuthorizer> index =
        new EnumMap<>(LiveSyncAuthorization.class);
    for (LiveSyncTopicAuthorizer authorizer : authorizers) {
      for (LiveSyncAuthorization kind : authorizer.authorizations()) {
        if (kind == LiveSyncAuthorization.MEMBER || kind == LiveSyncAuthorization.SELF) {
          throw new IllegalStateException(
              authorizer.getClass().getName() + " claims " + kind + ", which is decided centrally");
        }
        LiveSyncTopicAuthorizer previous = index.putIfAbsent(kind, authorizer);
        if (previous != null) {
          throw new IllegalStateException(
              "Two live-sync authorizers decide "
                  + kind
                  + ": "
                  + previous.getClass().getName()
                  + " and "
                  + authorizer.getClass().getName());
        }
      }
    }
    for (LiveSyncAuthorization kind : LiveSyncAuthorization.values()) {
      if (kind != LiveSyncAuthorization.MEMBER
          && kind != LiveSyncAuthorization.SELF
          && !index.containsKey(kind)) {
        throw new IllegalStateException("No live-sync authorizer decides " + kind);
      }
    }
    return index;
  }

  /**
   * One subscribe verdict and the bounded reason it carries into the metric.
   *
   * @param allowed whether the room may be opened
   * @param reason the {@code reason} tag value, {@link MetricNames#REASON_NONE} when allowed
   */
  private record Verdict(boolean allowed, @NotNull String reason) {

    /**
     * The allowing verdict. Named apart from the {@code allowed()} component accessor, which a
     * record generates and which a same-named factory would collide with.
     *
     * @return a verdict carrying the placeholder reason Micrometer requires
     */
    @NotNull
    static Verdict permit() {
      return new Verdict(true, MetricNames.REASON_NONE);
    }

    /**
     * A refusing verdict.
     *
     * @param reason why it was refused
     * @return the verdict
     */
    @NotNull
    static Verdict refuse(@NotNull String reason) {
      return new Verdict(false, reason);
    }
  }
}
