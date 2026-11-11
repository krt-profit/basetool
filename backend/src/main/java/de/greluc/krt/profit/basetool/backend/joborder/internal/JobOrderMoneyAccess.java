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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Applies the existing order parties and active account policy to money actions. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyAccess {

  private final JobOrderAccessPolicy access;
  private final JobOrderMoneyHolders holders;
  private final AuthHelperService auth;

  UUID actorId() {
    return auth.currentUserId()
        .orElseThrow(() -> new AccessDeniedException("An active member account is required."));
  }

  UUID requireActiveActor() {
    return holders
        .activeUser(actorId())
        .map(User::getId)
        .orElseThrow(() -> new AccessDeniedException("An active member account is required."));
  }

  boolean responsible(JobOrder order) {
    return access.canTransferResponsibility(order);
  }

  boolean requester(JobOrder order) {
    return access.canEdit(order, JobOrderGate.REQUESTER);
  }

  boolean unitWriter(JobOrder order) {
    return responsible(order) || requester(order);
  }

  boolean mayRepair(
      JobOrder order,
      JobOrderLinkedMoneyAttempt attempt,
      UUID actor,
      JobOrderMoneyLedger.Ledger booked) {
    if (access.responsibilityInvalid(order)) {
      return false;
    }
    if (responsible(order)) {
      return true;
    }
    if (!actor.equals(attempt.getActorId())) {
      return false;
    }
    if ((attempt.getKind().delivers() || attempt.getKind() == JobOrderMoneyKind.REQUESTER_RETURN)
        && requester(order)) {
      return true;
    }
    UUID holder = attempt.getHolderId();
    return holder == null
        ? actor.equals(attempt.getCounterpartyUserId())
        : actor.equals(booked.holder(holder).getUserId());
  }

  static boolean repairable(JobOrder order, JobOrderLinkedMoneyAttempt attempt) {
    return attempt.replaceable()
        && order.getStatus() != JobOrderStatus.REJECTED
        && (order.getStatus().isActive() || attempt.permitsTerminalPosting());
  }

  void requireUnitWriter(JobOrder order) {
    if (!unitWriter(order)) {
      throw new AccessDeniedException("Caller may not represent either order party.");
    }
  }
}
