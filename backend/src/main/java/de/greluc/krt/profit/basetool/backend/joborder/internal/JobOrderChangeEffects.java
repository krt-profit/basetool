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
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationCause;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitWriterDirectory;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
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
   * Groups an actual ineligibility ending even when the participation has no open material line.
   *
   * @param order the affected order
   * @param unitId the Staffel whose active participation ended
   */
  public void participationEnded(@NotNull JobOrder order, @NotNull UUID unitId) {
    group(order, NoticeKind.INELIGIBLE, unitId);
  }

  private MaterialGroup group(JobOrder order, NoticeKind kind, UUID unitId) {
    Revision revision = batch().revisions.computeIfAbsent(order.getId(), _ -> new Revision());
    ActorRef actor = new ActorRef(auth.currentUserId().orElse(null), ActorRef.UNKNOWN_NAME);
    MaterialGroup group =
        revision.materials.computeIfAbsent(
            new GroupKey(kind, actor), _ -> new MaterialGroup(order, actor));
    group.unitIds.add(unitId);
    if (order.getResponsibleOrgUnit() != null) {
      group.unitIds.add(order.getResponsibleOrgUnit().getId());
    }
    liveSync.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order.getId()),
        List.of("materials", "aggregated", "kpi", "actions"));
    liveSync.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue", "demand"));
    return group;
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
      case JOB_ORDER_FINISHED -> NoticePriority.FINISHED;
      case JOB_ORDER_UPDATED_BY_REQUESTER -> NoticePriority.REQUESTER_UPDATE;
      default -> NoticePriority.OTHER;
    };
  }

  /** Delivery precedence for the original notice policies. */
  private enum NoticePriority {
    FINISHED,
    REQUESTER_UPDATE,
    OTHER
  }

  /** The data-only material audience and rendering facts collected for one original preference. */
  private static final class MaterialGroup {
    private final UUID orderId;
    private final Integer displayId;
    private final String handle;
    private final ActorRef actor;
    private final Set<UUID> unitIds = new LinkedHashSet<>();
    private final Set<String> materials = new TreeSet<>();

    private MaterialGroup(JobOrder order, ActorRef actor) {
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
    INELIGIBLE
  }

  /** Preserves the actor and original preference of each bounded material group. */
  private record GroupKey(NoticeKind kind, ActorRef actor) {}

  /** A stable cause identity and original event groups for one order revision. */
  private static final class Revision {
    private final UUID causeId = UUID.randomUUID();
    private final List<NotificationEvent> originals = new ArrayList<>();
    private final Map<GroupKey, MaterialGroup> materials = new LinkedHashMap<>();
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
              .flatMap(revision -> revision.materials.values().stream())
              .flatMap(group -> group.unitIds.stream())
              .collect(Collectors.toSet());
      Map<UUID, Set<UUID>> audiences = writers.writerUserIdsByUnit(affectedUnits);
      for (Revision revision : revisions.values()) {
        List<NotificationEvent> originals = new ArrayList<>(revision.originals);
        for (var entry : revision.materials.entrySet()) {
          MaterialGroup group = entry.getValue();
          Set<UUID> recipients =
              group.unitIds.stream()
                  .flatMap(unit -> audiences.getOrDefault(unit, Set.of()).stream())
                  .collect(Collectors.toUnmodifiableSet());
          if (!recipients.isEmpty()) {
            originals.add(
                entry.getKey().kind() == NoticeKind.CHANGED
                    ? JobOrderNotices.materialContributionsChanged(
                        group.orderId,
                        group.displayId,
                        group.handle,
                        recipients,
                        String.join(", ", group.materials),
                        group.actor)
                    : JobOrderNotices.promisesEnded(
                        group.orderId,
                        group.displayId,
                        group.handle,
                        recipients,
                        String.join(", ", group.materials),
                        entry.getKey().kind() == NoticeKind.INELIGIBLE
                            ? AutomaticEndingReason.INELIGIBLE_UNIT
                            : AutomaticEndingReason.ORDER_CHANGED,
                        group.actor));
          }
        }
        if (!originals.isEmpty()) {
          originals.sort(Comparator.comparing(JobOrderChangeEffects::priority));
          events.publishEvent(new NotificationCause(revision.causeId, originals));
        }
      }
    }
  }
}
