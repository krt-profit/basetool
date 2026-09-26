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
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Decides whether the current caller may join one live-sync room (ADR-0143), asking the same
 * question the equivalent read asks.
 *
 * <p>A check that throws is treated as a refusal.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiveSyncSubscriptionAuthorizer {

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final OrgUnitBankAccessService orgUnitBankAccessService;
  private final MeterRegistry meterRegistry;

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
      boolean allowed =
          switch (topic.topicClass().authorization()) {
            case MEMBER -> true;
            case MISSION -> ownerScopeService.canSeeMission(required(topic));
            case OPERATION -> ownerScopeService.canSeeOperation(required(topic));
            case JOB_ORDER -> ownerScopeService.canSeeJobOrder(required(topic));
            case JOB_ORDER_QUEUE -> ownerScopeService.canViewJobOrders();
            case REFINERY_ORDER -> ownerScopeService.canSeeRefineryOrder(required(topic));
            case BANK_ACCOUNT -> canSeeOrgUnitBankAccount(required(topic));
          };
      return allowed ? Verdict.permit() : Verdict.refuse(MetricNames.SUBSCRIBE_DENY_AUTHZ);
    } catch (RuntimeException e) {
      log.debug("Live-sync subscribe refused for {} after a failed check", topic.canonical(), e);
      return Verdict.refuse(MetricNames.SUBSCRIBE_DENY_CHECK_FAILED);
    }
  }

  /**
   * Answers whether the caller may read one org-unit bank account via the member-facing detail read
   * (REQ-APP-BANK-007).
   *
   * @param accountId the account named by the topic
   * @return {@code true} if the detail read succeeds
   */
  private boolean canSeeOrgUnitBankAccount(@NotNull UUID accountId) {
    orgUnitBankAccessService.getViewableAccountDetail(accountId);
    return true;
  }

  /**
   * Returns the topic's resource id, which a per-resource class always has.
   *
   * @param topic the room
   * @return the id
   * @throws IllegalStateException if a per-resource topic carries no id (registry and parser
   *     disagree)
   */
  @NotNull
  private static UUID required(@NotNull LiveSyncTopic topic) {
    @Nullable UUID id = topic.resourceId();
    if (id == null) {
      throw new IllegalStateException(
          "Per-resource topic class " + topic.topicClass() + " parsed without a resource id");
    }
    return id;
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
