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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
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
    return auth.currentUserId().orElseThrow(JobOrderMoneyAccess::noActiveMember);
  }

  UUID requireActiveActor() {
    return requireActive(actorId());
  }

  /**
   * Names the web's caller of a money command: the contextual authorities, the contextual read and
   * the request's member.
   *
   * @return the web caller
   */
  JobOrderCaller webCaller() {
    return JobOrderCaller.web(auth.currentUserId().orElse(null));
  }

  /**
   * Returns the member a caller's actor acts as.
   *
   * @param actor the actor
   * @return the actor's member
   * @throws AccessDeniedException when the actor resolves to no member
   */
  UUID memberOf(JobOrderActor actor) {
    UUID member = actor.member();
    if (member == null) {
      throw noActiveMember();
    }
    return member;
  }

  /**
   * Requires the member to hold an active account.
   *
   * @param member the member
   * @return the member's id
   * @throws AccessDeniedException when the account is not active
   */
  UUID requireActive(UUID member) {
    return holders
        .activeUser(member)
        .map(User::getId)
        .orElseThrow(JobOrderMoneyAccess::noActiveMember);
  }

  private static AccessDeniedException noActiveMember() {
    return new AccessDeniedException("An active member account is required.");
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
    requireParty(unitWriter(order));
  }

  /**
   * Requires the writer units of a caller's rule to cover either side of the order.
   *
   * @param order the loaded order
   * @param writers the units the caller writes for under its rule
   * @throws AccessDeniedException when the caller writes neither side
   */
  void requireUnitWriter(JobOrder order, JobOrderWriters.Scope writers) {
    requireParty(writers.writesEitherSide(order));
  }

  private static void requireParty(boolean party) {
    if (!party) {
      throw new AccessDeniedException("Caller may not represent either order party.");
    }
  }
}
