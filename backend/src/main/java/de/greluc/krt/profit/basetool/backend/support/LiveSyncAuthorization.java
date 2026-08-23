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

package de.greluc.krt.profit.basetool.backend.support;

/**
 * The check a caller has to pass before a live-sync room is opened for them (ADR-0143).
 *
 * <p>Each constant names <em>which</em> question is asked, not the answer; {@code
 * LiveSyncSubscriptionAuthorizer} in the service layer runs it, because the answers come from
 * {@code OwnerScopeService} and this package is a dependency leaf. Keeping the kinds here lets
 * {@link LiveSyncTopicClass} declare its gate beside its section whitelist, so a new room cannot be
 * added without stating who may enter it.
 *
 * <p>The questions are the same ones the equivalent read already asks, deliberately: a room that
 * admitted someone the underlying {@code GET} would refuse would leak the fact that a resource
 * changed, which is the only thing a {@code changed} frame carries.
 */
public enum LiveSyncAuthorization {

  /**
   * Any real member. Used for the global rooms whose page gate is the member role itself — the
   * Einsatz list, the shared Lager, the Materialbörse, the Raffinerie queue and the org-unit bank
   * overview. Every one of those re-fetches through a scoped read on the receiving side, so a peer
   * outside the scope refreshes into the same view they had.
   */
  MEMBER,

  /** {@code ownerScopeService.canSeeMission(id)} — the gate of the Einsatz detail read. */
  MISSION,

  /** {@code ownerScopeService.canSeeOperation(id)} — the gate of the Operation detail read. */
  OPERATION,

  /** {@code ownerScopeService.canSeeJobOrder(id)} — the gate of the Auftrag detail read. */
  JOB_ORDER,

  /**
   * {@code ownerScopeService.canViewJobOrders()} — the queue capability. A requester who only ever
   * sees their own Aufträge does not hold it and is refused the global room, exactly as they are
   * refused the queue page.
   */
  JOB_ORDER_QUEUE,

  /** {@code ownerScopeService.canSeeRefineryOrder(id)} — the gate of the Raffinerie-Order read. */
  REFINERY_ORDER,

  /**
   * The org-unit bank account read the app's Bank screen performs. Deliberately not the bank-staff
   * read: the app carries the member-facing surface only (REQ-APP-BANK-007), so the room follows
   * the same path rather than the wider one.
   */
  BANK_ACCOUNT
}
