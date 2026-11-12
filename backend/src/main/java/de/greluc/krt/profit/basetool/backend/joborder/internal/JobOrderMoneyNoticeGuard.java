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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderMoneyMovedEvent;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderMoneyProvisionedEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationCauseGuard;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Rechecks the specific affected holder link before a money notice reaches its account. */
@Service
@RequiredArgsConstructor
class JobOrderMoneyNoticeGuard implements NotificationCauseGuard {

  private final JobOrderRepository orders;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyIdentityLock identityLock;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isCurrent(@NotNull NotificationEvent event) {
    if (event instanceof JobOrderMoneyMovedEvent movement) {
      return isCurrentMovement(movement);
    }
    if (!(event instanceof JobOrderMoneyProvisionedEvent provision)) {
      return true;
    }
    if (!provision.groupedWithOrderRevision()) {
      identityLock.lockAccounts(provision.contextRecipientUserId(), provision.actorSub());
    }
    return orders.lockForScopedEdit(provision.entityId()).isPresent()
        && holders
            .findByOrderIdAndId(provision.entityId(), provision.holderId())
            .filter(
                holder -> Objects.equals(holder.getUserId(), provision.contextRecipientUserId()))
            .isPresent()
        && orders.hasMoneyHolderReadGrant(provision.entityId(), provision.contextRecipientUserId());
  }

  private boolean isCurrentMovement(JobOrderMoneyMovedEvent event) {
    if (!event.groupedWithOrderRevision()) {
      identityLock.lockAccounts(
          event.actorSub(), event.sourceAccount(), event.destinationAccount());
    }
    UUID orderId = event.entityId();
    if (orders.lockForScopedEdit(orderId).isEmpty()) {
      return false;
    }
    UUID holderId = event.holderId();
    UUID recipientId = event.recipientId();
    UUID account =
        holderId != null
            ? holders.accountFor(orderId, holderId).orElse(null)
            : recipientId == null ? null : recipients.accountFor(orderId, recipientId);
    return event.contextRecipientUserId().equals(account)
        && orders.hasMoneyPersonalReadGrant(orderId, event.contextRecipientUserId());
  }
}
