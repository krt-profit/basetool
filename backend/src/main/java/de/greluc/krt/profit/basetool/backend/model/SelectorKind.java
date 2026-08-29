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
 * How a {@link NotificationRuleSelector} resolves its recipients.
 *
 * <p>The set is intentionally open so future selector kinds (e.g. a {@code GROUP} pointing at a
 * user-group entity) slot in without reworking the engine — each kind reads a different subset of
 * the selector's columns.
 */
public enum SelectorKind {

  /** Resolves to a single explicit user, named by {@code userId}. */
  SPECIFIC_USER,

  /** Resolves to every holder of a global role, named by {@code roleCode}. */
  ROLE,

  /**
   * Resolves to holders of a role <em>relative to an org unit carried by the event</em> — the role
   * given by {@code orgRelativeRole} within the org unit selected by {@code contextRole}.
   */
  ORG_RELATIVE_ROLE,

  /**
   * Resolves to every employee holding a {@code bank_account_grant} on the <em>bank account carried
   * by the event</em> ({@link
   * de.greluc.krt.profit.basetool.backend.event.NotificationEvent#contextAccountId()}). Reads no
   * selector columns — the account comes from the event, mirroring {@link #ORG_RELATIVE_ROLE}.
   */
  ACCOUNT_GRANT,

  /**
   * Resolves to the single user the event is directed at ({@link
   * de.greluc.krt.profit.basetool.backend.event.NotificationEvent#contextRecipientUserId()}) — e.g.
   * the officer/lead who raised a booking request, notified when it is confirmed or rejected. Reads
   * no selector columns — the recipient comes from the event.
   */
  EVENT_RECIPIENT,

  /**
   * Resolves to the <em>responsible holder(s)</em> (Kontoverantwortliche, REQ-BANK-034) of the bank
   * account carried by the event ({@link
   * de.greluc.krt.profit.basetool.backend.event.NotificationEvent#contextAccountId()}) — the
   * Staffelleiter / SK-Leiter / Bereichsleiter / OL members / Profit-Bereichsleiter derived from
   * the account's owning org unit. Reads no selector columns — the account comes from the event,
   * mirroring {@link #ACCOUNT_GRANT}. The org-unit-aware resolution stays inside the {@code
   * OrgUnitBankAccessService} seam so the bank stays org-unit-blind (REQ-BANK-008, REQ-BANK-026).
   */
  ACCOUNT_RESPONSIBLE
}
