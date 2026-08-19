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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * The admin decision recorded on a {@link UserApprovalEvent} audit row (epic #720, Track 1).
 *
 * <p>Distinct from {@link ApprovalStatus} (the current account state): a decision is the immutable
 * audit of a single admin action. {@link #APPROVED} / {@link #REJECTED} move the decided account
 * into {@link ApprovalStatus#ACTIVE} / {@link ApprovalStatus#REJECTED}; {@link #LINKED} is recorded
 * on the surviving <em>existing</em> account and does not change its status (it was already {@link
 * ApprovalStatus#ACTIVE}) — it captures that a Discord registration was linked into it
 * (REQ-SEC-026); {@link #REOPENED} undoes a rejection by returning the account to {@link
 * ApprovalStatus#PENDING} (REQ-SEC-034).
 */
public enum ApprovalDecision {

  /** An admin approved the registration (account moved to {@link ApprovalStatus#ACTIVE}). */
  APPROVED,

  /** An admin rejected the registration (account moved to {@link ApprovalStatus#REJECTED}). */
  REJECTED,

  /**
   * An admin linked a pending Discord registration onto an existing account: the Discord identity
   * was moved to this (surviving) account and the throwaway Discord-registered account removed. The
   * row is written against the surviving account's id; its status is unchanged (REQ-SEC-026).
   */
  LINKED,

  /**
   * An admin reopened a rejected registration: the account moved back from {@link
   * ApprovalStatus#REJECTED} to {@link ApprovalStatus#PENDING} so it re-enters the approval queue
   * and can be decided again through the normal approve/reject path (REQ-SEC-034).
   *
   * <p>Deliberately its own value rather than a reuse of {@link #APPROVED}: a reopen grants no
   * access — the account is pending, not active — so recording it as an approval would make the
   * audit trail claim an access grant that never happened. The reversal and the subsequent
   * re-decision are two separate rows.
   */
  REOPENED
}
