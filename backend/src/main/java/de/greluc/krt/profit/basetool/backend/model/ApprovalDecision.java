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
 * The immutable admin decision recorded on a {@link UserApprovalEvent} audit row.
 *
 * <p>{@link #APPROVED} / {@link #REJECTED} move the account to {@link ApprovalStatus#ACTIVE} /
 * {@link ApprovalStatus#REJECTED}; {@link #LINKED} leaves the surviving account's status unchanged;
 * {@link #REOPENED} returns a rejected account to {@link ApprovalStatus#PENDING}.
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
   * An admin reopened a rejected registration, moving it from {@link ApprovalStatus#REJECTED} back
   * to {@link ApprovalStatus#PENDING} so it can be decided again (REQ-SEC-034). It grants no
   * access.
   */
  REOPENED
}
