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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderCreatedEvent;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderNotices;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderNotices.AutomaticEndingReason;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationPromptCleanup;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationCause;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitWriterDirectory;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Collects one order revision's original notice policies after final material reconciliation. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JobOrderChangeEffects {
  private static final int COLLECTED_NOTICE_ORDER = 200;
  private final OrgUnitWriterDirectory writers;
  private final AuthHelperService auth;
  private final ApplicationEventPublisher events;
  private final LiveSyncPublisher liveSync;
  private final NotificationPromptCleanup notificationCleanup;

  /**
   * Captures existing action prompts while the caller holds the order lock.
   *
   * @param orderId the closing order
   * @return the exact prompts the closing revision supersedes
   */
  public @NotNull Set<UUID> captureActionPromptIds(@NotNull UUID orderId) {
    return notificationCleanup.capture(
        JobOrderNotices.ACTION_PROMPTS, JobOrderCreatedEvent.ENTITY_TYPE, orderId);
  }

  /** Registers the collector before final material reconciliation can produce further changes. */
  public void prepare() {
    batch();
  }

  /**
   * Adds one changed contribution without sending a notice per material row.
   *
   * @param claim the contribution after its recorded change
   * @param reason the recorded reason whose original preference remains authoritative
   * @param previous the preceding open amount
   * @param creditDelta the historical contribution change
   */
  public void materialChanged(
      @NotNull MaterialClaim claim,
      @NotNull MaterialContributionChange.Reason reason,
      double previous,
      double creditDelta) {
    if (!meaningful(claim, reason, previous, creditDelta)) {
      return;
    }
    JobOrder order = claim.getJobOrder();
    NoticeKind groupKey =
        switch (reason) {
          case UNIT_INELIGIBLE -> NoticeKind.INELIGIBLE;
          case REQUIREMENT_ENDED, ORDER_ENDED, PROMISE_ENDED -> NoticeKind.ENDED;
          case PROMISE_CHANGED,
              PROVISION_CREDITED,
              CAPACITY_REDUCED,
              FALSE_FACT_CORRECTED,
              PROMISE_WITHDRAWN ->
              NoticeKind.CHANGED;
        };
    group(order, groupKey, claim.getClaimingOrgUnit().getId())
        .materials
        .add(claim.getMaterial().getName());
  }

  /**
   * Groups a real receipt declaration correction even when promise and credit totals stay equal.
   *
   * @param claim a currently authorized contribution whose receipt facts changed
   */
  public void receiptFactChanged(@NotNull MaterialClaim claim) {
    group(claim.getJobOrder(), NoticeKind.CHANGED, claim.getClaimingOrgUnit().getId())
        .materials
        .add(claim.getMaterial().getName());
  }

  /**
   * Groups an actual ineligibility ending even when the participation has no open material line.
   *
   * @param order the affected order
   * @param unitId the Staffel whose active participation ended
   */
  public void participationEnded(@NotNull JobOrder order, @NotNull UUID unitId) {
    group(order, NoticeKind.INELIGIBLE, unitId);
  }

  /** The command's actual change to one Staffel's support for the order. */
  public enum ParticipationChange {
    /** The Staffel began active support. */
    ENROLLED,
    /** The Staffel ended active support. */
    WITHDRAWN,
    /** The Staffel's contribution relationship continues under responsibility. */
    BECAME_RESPONSIBLE
  }

  /**
   * Groups explicit support changes and refreshes a supporter becoming responsible.
   *
   * @param order the affected order
   * @param unitId the Staffel whose support changed
   * @param change the recorded participation transition
   */
  public void participationChanged(
      @NotNull JobOrder order, @NotNull UUID unitId, @NotNull ParticipationChange change) {
    NoticeKind kind =
        switch (change) {
          case ENROLLED -> NoticeKind.SUPPORT_ENROLLED;
          case WITHDRAWN -> NoticeKind.SUPPORT_WITHDRAWN;
          case BECAME_RESPONSIBLE -> null;
        };
    if (kind == null) {
      publishMaterialSections(order);
    } else {
      group(order, kind, unitId);
    }
  }

  void moneyCommitmentChanged(JobOrder order, UUID unitId) {
    group(order, NoticeKind.MONEY_COMMITMENT_CHANGED, unitId);
  }

  private UnitGroup group(JobOrder order, NoticeKind kind, UUID unitId) {
    Revision revision = batch().revisions.computeIfAbsent(order.getId(), _ -> new Revision());
    ActorRef actor = new ActorRef(auth.currentUserId().orElse(null), ActorRef.UNKNOWN_NAME);
    UnitGroup group =
        revision.groups.computeIfAbsent(
            new GroupKey(kind, actor), _ -> new UnitGroup(order, actor));
    group.affectedUnits.add(unitId);
    if (order.getResponsibleOrgUnit() != null) {
      group.coordinationUnits.add(order.getResponsibleOrgUnit().getId());
    }
    if (kind == NoticeKind.MONEY_COMMITMENT_CHANGED) {
      liveSync.publishAfterCommit(
          LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order.getId()),
          List.of("participations", "money"));
      liveSync.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue"));
    } else {
      publishMaterialSections(order);
    }
    return group;
  }

  private void publishMaterialSections(JobOrder order) {
    liveSync.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order.getId()),
        List.of("materials", "aggregated", "kpi", "actions", "participations"));
    liveSync.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue", "demand"));
  }

  /**
   * Retains original order notices in the same revision while forwarding other domain events.
   *
   * @param event the edit's data-only event
   */
  public void publish(@NotNull Object event) {
    if (event instanceof NotificationEvent notice
        && notice.entityId() != null
        && (JobOrderCreatedEvent.ENTITY_TYPE.equals(notice.entityType())
            || JobOrderNotices.ENTITY_TYPE_DELETED.equals(notice.entityType()))) {
      Revision revision = batch().revisions.computeIfAbsent(notice.entityId(), _ -> new Revision());
      revision.originals.add(notice);
    } else {
      events.publishEvent(event);
    }
  }

  private static boolean meaningful(
      MaterialClaim claim,
      MaterialContributionChange.Reason reason,
      double previous,
      double creditDelta) {
    return switch (reason) {
      case UNIT_INELIGIBLE, REQUIREMENT_ENDED, ORDER_ENDED, PROMISE_ENDED, PROMISE_WITHDRAWN ->
          true;
      case PROMISE_CHANGED, PROVISION_CREDITED, CAPACITY_REDUCED, FALSE_FACT_CORRECTED ->
          Double.compare(previous, claim.getAmount()) != 0 || Double.compare(creditDelta, 0.0) != 0;
    };
  }

  private Batch batch() {
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof Batch current) {
        return current;
      }
    }
    Batch current = new Batch();
    TransactionSynchronizationManager.registerSynchronization(current);
    return current;
  }

  private static @NotNull NoticePriority priority(@NotNull NotificationEvent event) {
    return switch (event.eventType()) {
      case JOB_ORDER_FINISHED, JOB_ORDER_REOPENED -> NoticePriority.FINISHED;
      case JOB_ORDER_REASSIGNED -> NoticePriority.RESPONSIBILITY;
      case JOB_ORDER_CLARIFICATION_REQUIRED -> NoticePriority.CLARIFICATION;
      case JOB_ORDER_UPDATED_BY_REQUESTER -> NoticePriority.REQUESTER_UPDATE;
      case JOB_ORDER_UPDATED_BY_RESPONSIBLE -> NoticePriority.RESPONSIBLE_UPDATE;
      case JOB_ORDER_PARTICIPATION_CHANGED, JOB_ORDER_MONEY_COMMITMENT_CHANGED ->
          NoticePriority.PARTICIPATION;
      default -> NoticePriority.OTHER;
    };
  }

  /** Delivery precedence for the original notice policies. */
  private enum NoticePriority {
    FINISHED,
    RESPONSIBILITY,
    CLARIFICATION,
    PARTICIPATION,
    AFFECTED_MATERIAL,
    REQUESTER_UPDATE,
    OTHER,
    RESPONSIBLE_UPDATE
  }

  /** The unit audience and rendering facts for one notification preference. */
  private static final class UnitGroup {
    private final UUID orderId;
    private final Integer displayId;
    private final String handle;
    private final ActorRef actor;
    private final Set<UUID> affectedUnits = new LinkedHashSet<>();
    private final Set<UUID> coordinationUnits = new LinkedHashSet<>();
    private final Set<String> materials = new TreeSet<>();

    private UnitGroup(JobOrder order, ActorRef actor) {
      orderId = order.getId();
      displayId = order.getDisplayId();
      handle = order.getHandle();
      this.actor = actor;
    }
  }

  /** Distinguishes ordinary changes from automatic endings under their original preferences. */
  private enum NoticeKind {
    CHANGED,
    ENDED,
    INELIGIBLE,
    SUPPORT_ENROLLED,
    SUPPORT_WITHDRAWN,
    MONEY_COMMITMENT_CHANGED;

    boolean usesSharedUnitAudience() {
      return this == SUPPORT_ENROLLED
          || this == SUPPORT_WITHDRAWN
          || this == MONEY_COMMITMENT_CHANGED;
    }
  }

  /** Preserves the actor and original preference of each unit group. */
  private record GroupKey(NoticeKind kind, ActorRef actor) {}

  /** A stable cause identity and original event groups for one order revision. */
  private static final class Revision {
    private final UUID causeId = UUID.randomUUID();
    private final List<NotificationEvent> originals = new ArrayList<>();
    private final Map<GroupKey, UnitGroup> groups = new LinkedHashMap<>();
  }

  private static NotificationEvent notice(NoticeKind kind, UnitGroup group, Set<UUID> recipients) {
    String materials = String.join(", ", group.materials);
    return switch (kind) {
      case CHANGED ->
          JobOrderNotices.materialContributionsChanged(
              group.orderId, group.displayId, group.handle, recipients, materials, group.actor);
      case ENDED, INELIGIBLE ->
          JobOrderNotices.promisesEnded(
              group.orderId,
              group.displayId,
              group.handle,
              recipients,
              materials,
              kind == NoticeKind.INELIGIBLE
                  ? AutomaticEndingReason.INELIGIBLE_UNIT
                  : AutomaticEndingReason.ORDER_CHANGED,
              group.actor);
      case MONEY_COMMITMENT_CHANGED ->
          JobOrderNotices.moneyCommitmentChanged(
              group.orderId, group.displayId, recipients, group.actor);
      case SUPPORT_ENROLLED, SUPPORT_WITHDRAWN ->
          JobOrderNotices.participationChanged(
              group.orderId,
              group.displayId,
              group.handle,
              recipients,
              kind == NoticeKind.SUPPORT_ENROLLED,
              group.actor);
    };
  }

  private static void appendUnitNotices(
      Map<NoticePriority, List<NotificationEvent>> originals,
      NoticeKind kind,
      UnitGroup group,
      Map<UUID, Set<UUID>> audiences) {
    Set<UUID> affected = recipients(group.affectedUnits, audiences);
    Set<UUID> coordination = recipients(group.coordinationUnits, audiences);
    if (kind.usesSharedUnitAudience()) {
      affected.addAll(coordination);
      appendGroup(originals, NoticePriority.PARTICIPATION, kind, group, affected);
    } else {
      coordination.removeAll(affected);
      appendGroup(originals, NoticePriority.AFFECTED_MATERIAL, kind, group, affected);
      appendGroup(originals, NoticePriority.OTHER, kind, group, coordination);
    }
  }

  private static Set<UUID> recipients(Set<UUID> units, Map<UUID, Set<UUID>> audiences) {
    return units.stream()
        .flatMap(unit -> audiences.getOrDefault(unit, Set.of()).stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static void appendGroup(
      Map<NoticePriority, List<NotificationEvent>> originals,
      NoticePriority priority,
      NoticeKind kind,
      UnitGroup group,
      Set<UUID> recipients) {
    if (!recipients.isEmpty()) {
      originals
          .computeIfAbsent(priority, _ -> new ArrayList<>())
          .add(notice(kind, group, recipients));
    }
  }

  /** Publishes scalar notice causes after the material coordinator's final reconciliation. */
  private final class Batch implements TransactionSynchronization {
    private final Map<UUID, Revision> revisions = new LinkedHashMap<>();

    /** Runs after the material coordinator has reconciled its final state. */
    @Override
    public int getOrder() {
      return COLLECTED_NOTICE_ORDER;
    }

    /** Publishes only immutable original-event causes from a writable business transaction. */
    @Override
    public void beforeCommit(boolean readOnly) {
      if (readOnly) {
        return;
      }
      Set<UUID> affectedUnits =
          revisions.values().stream()
              .flatMap(revision -> revision.groups.values().stream())
              .flatMap(group ->
                  Stream.concat(group.affectedUnits.stream(), group.coordinationUnits.stream()))
              .collect(Collectors.toSet());
      Map<UUID, Set<UUID>> audiences = writers.writerUserIdsByUnit(affectedUnits);
      for (Revision revision : revisions.values()) {
        Map<NoticePriority, List<NotificationEvent>> originals =
            new EnumMap<>(NoticePriority.class);
        revision.originals.forEach(
            event -> originals.computeIfAbsent(priority(event), _ -> new ArrayList<>()).add(event));
        revision.groups.forEach(
            (key, group) -> appendUnitNotices(originals, key.kind(), group, audiences));
        if (!originals.isEmpty()) {
          events.publishEvent(
              new NotificationCause(
                  revision.causeId, originals.values().stream().flatMap(List::stream).toList()));
        }
      }
    }
  }
}
