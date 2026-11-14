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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryPatchCancellation;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels the running refinery orders a game patch ended (REQ-PATCH-006): each order is cancelled
 * as the web cancels it, keeps the patch change as its reason and keeps its finances.
 */
@Service
@RequiredArgsConstructor
public class RefineryPatchCancellationService implements RefineryPatchCancellation {

  /** The statuses of a running order, the only ones a patch ends. */
  private static final Set<RefineryOrderStatus> RUNNING =
      Set.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS);

  /** The section of the refinery-order list room. */
  private static final String QUEUE = "queue";

  /** The section of a refinery order's detail room that holds the order's fields. */
  private static final String ORDER = "order";

  private final RefineryOrderRepository repository;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final LiveSyncPublisher liveSyncPublisher;

  @Override
  @Transactional(readOnly = true)
  public long countCancellable(@NotNull Instant liveAt) {
    return repository.countStartedBefore(RUNNING, liveAt);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int cancel(
      @NotNull Instant liveAt, @NotNull UUID patchChangeId, @NotNull String patchLabel) {
    List<RefineryOrder> orders = repository.lockStartedBeforeOrderById(RUNNING, liveAt);
    Instant now = Instant.now();
    for (RefineryOrder order : orders) {
      order.cancelByPatchChange(patchChangeId);
      RefineryOrderSection.GAME_DATA.advance(order);
      order.getProvenance().recordWebChange(now);
      eventPublisher.publishEvent(RefineryNotices.readyCleared(order.getId()));
    }
    auditRecorder.record(
        AuditEventType.REFINERY_ORDERS_CANCELED_BY_PATCH,
        patchChangeId,
        patchLabel,
        null,
        AuditDetails.of("canceled", orders.size()).with("patchChange", patchChangeId));
    if (!orders.isEmpty()) {
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.REFINERY), List.of(QUEUE));
      orders.forEach(order ->
          liveSyncPublisher.publishAfterCommit(
              LiveSyncTopic.of(LiveSyncTopicClass.REFINERY_ORDER, order.getId()), List.of(ORDER)));
    }
    return orders.size();
  }
}
