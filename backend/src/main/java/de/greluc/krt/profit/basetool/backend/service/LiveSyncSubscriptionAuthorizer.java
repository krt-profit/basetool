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
 * Decides whether the current caller may join one live-sync room (ADR-0143).
 *
 * <p>Every question here is the question the equivalent read already asks, and it is asked of the
 * same collaborator the {@code @PreAuthorize} expressions use. That is the whole design: a room
 * admits exactly the callers who could already fetch what changed, so the one thing a {@code
 * changed} frame carries — that a resource moved — never reaches someone who may not see the
 * resource.
 *
 * <p>Unlike the frontend's authorizer (ADR-0094), there is no fail-open branch and no bounded
 * executor. The frontend has to work through a captured token against a remote API and cannot tell
 * "refused" from "unreachable"; this backend is the authority and answers from its own data, so a
 * verdict is either yes or no. A check that throws is treated as **no**: a stream open is
 * user-initiated and retried on the next screen, so refusing a room during a transient fault costs
 * one refresh, while admitting one would be an access decision made by an exception handler.
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
   * Records that a caller named a topic this backend's registry does not know.
   *
   * <p>Lives here rather than in the controller so the whole subscribe-side measurement sits in one
   * place, and unlabelled because the topic belongs to no class — an {@code unknown} sentinel in
   * the bounded {@code topic_class} set would cost every other query its clean vocabulary. A
   * sustained rate is the signature of an app build asking for a room this server no longer serves,
   * which is the one skew the parity gate cannot catch: the gate compares two server registries,
   * not a shipped client against either.
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
      // Every room in this registry is member-facing; a role-less account has no business in any of
      // them, and checking it once here keeps each branch below to its own resource question.
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
      // Counted apart from an ordinary refusal: a rising rate here is an infrastructure signal,
      // not members hitting permission boundaries, and the two are indistinguishable once merged.
      return Verdict.refuse(MetricNames.SUBSCRIBE_DENY_CHECK_FAILED);
    }
  }

  /**
   * Answers whether the caller may read one org-unit bank account.
   *
   * <p>There is no {@code canSee…} predicate for this one, so the read itself is the check: the
   * member-facing detail read throws when the caller may not see the account, which is exactly the
   * gate the app's Bank screen passes through. Deliberately the org-unit read and not the
   * bank-staff one — the app carries the member-facing surface only (REQ-APP-BANK-007), and a room
   * wider than the screen it feeds would admit a bank employee to a stream they have no client for.
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
   * @throws IllegalStateException if a per-resource class reached here without one — impossible via
   *     {@link LiveSyncTopic#parse}, which refuses that combination, so it can only mean the
   *     registry and the parser disagree
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
