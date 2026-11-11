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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderMoneyMovedEvent;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderMoneyProvisionedEvent;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationCause;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Registers committed money invalidations and targeted participant notices. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyEffects {

  static final List<String> LIFECYCLE_SECTIONS =
      List.of("money", "header", "actions", "dialogs", "kpi", "participations");

  private final LiveSyncPublisher live;
  private final ApplicationEventPublisher events;
  private final JobOrderChangeEffects orderEffects;

  void provisionRecorded(
      JobOrder order,
      JobOrderMoneyHolder holder,
      JobOrderMoneyOperation attempt,
      UUID actor,
      boolean started,
      boolean newLinkedHolder) {
    live.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order.getId()),
        started ? LIFECYCLE_SECTIONS : List.of("money"));
    if (started || newLinkedHolder) {
      live.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue"));
    }
    UUID recipient = holder.getUserId();
    if (recipient == null || recipient.equals(actor)) {
      return;
    }
    events.publishEvent(
        new NotificationCause(
            Objects.requireNonNull(attempt.getId()),
            List.of(
                new JobOrderMoneyProvisionedEvent(
                    order.getId(),
                    attempt.getHolderId(),
                    recipient,
                    actor,
                    order.getDisplayId()))));
  }

  void designationChanged(JobOrder order, boolean linked) {
    publishMoney(order, false, linked);
  }

  void movementRecorded(
      JobOrder order,
      JobOrderMoneyOperation fact,
      @Nullable UUID source,
      @Nullable UUID recipient,
      UUID actor,
      boolean lifecycleChanged,
      boolean newLinkedHolder) {
    publishMoney(order, lifecycleChanged, newLinkedHolder);
    boolean completes = order.getStatus() == JobOrderStatus.COMPLETED;
    List<NotificationEvent> notices = new ArrayList<>();
    if (source != null && !source.equals(actor)) {
      notices.add(
          new JobOrderMoneyMovedEvent(
              order.getId(),
              fact.getHolderId(),
              null,
              source,
              actor,
              source,
              recipient,
              completes,
              order.getDisplayId()));
    }
    if (recipient != null && !recipient.equals(actor) && !recipient.equals(source)) {
      notices.add(
          new JobOrderMoneyMovedEvent(
              order.getId(),
              fact.getDestinationHolderId(),
              fact.getRecipientId(),
              recipient,
              actor,
              source,
              recipient,
              completes,
              order.getDisplayId()));
    }
    if (completes) {
      notices.forEach(orderEffects::publish);
    } else if (!notices.isEmpty()) {
      events.publishEvent(new NotificationCause(Objects.requireNonNull(fact.getId()), notices));
    }
  }

  void targetChanged(JobOrder order) {
    publishMoney(order, false, true);
  }

  void informationChanged(JobOrder order) {
    live.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order.getId()),
        List.of("money", "header", "details", "dialogs"));
    live.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue"));
  }

  private void publishMoney(JobOrder order, boolean lifecycleChanged, boolean membershipChanged) {
    live.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order.getId()),
        lifecycleChanged ? LIFECYCLE_SECTIONS : List.of("money"));
    if (lifecycleChanged || membershipChanged) {
      live.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue"));
    }
  }
}
