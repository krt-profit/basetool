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
 * Machine identifier of a {@link Notification}'s kind.
 *
 * <p>The constant name is persisted verbatim (via {@code @Enumerated(STRING)}) and is the key the
 * frontend resolves to an i18n message under {@code notifications.type.*}. The set is intentionally
 * open-ended: a new producer adds a constant here (and the matching i18n keys + an optional default
 * rule) without a schema migration, because the {@code notification.type} column carries no CHECK
 * constraint. The data-driven recipient rules and the rendered text stay configurable; only the
 * stable type token is code.
 */
public enum NotificationType {

  /**
   * A new job order ("Auftrag") was created. Recipients are resolved per the seeded default rule
   * (officers of the responsible squadron / leads of the responsible special command, plus the
   * logisticians of that unit and the global admins; the creating actor is excluded).
   */
  JOB_ORDER_CREATED,

  /**
   * An org-unit officer/lead raised a confirm-before-post bank deposit/withdrawal request (epic
   * #666 F2, REQ-BANK-026). Recipients are resolved per the seeded default rule (the bank
   * management plus every employee granted on the target account; the requester is excluded).
   */
  BANK_BOOKING_REQUEST_CREATED,

  /**
   * A bank employee confirmed the requester's booking request (epic #666 F2, REQ-BANK-026). The
   * seeded default rule notifies the requesting officer/lead.
   */
  BANK_BOOKING_REQUEST_CONFIRMED,

  /**
   * A bank employee rejected the requester's booking request (epic #666 F2, REQ-BANK-026). The
   * seeded default rule notifies the requesting officer/lead (the reason is rendered in the text).
   */
  BANK_BOOKING_REQUEST_REJECTED,

  /**
   * A bank employee confirmed a booking request raised against an account the recipient is the
   * <em>responsible holder</em> of (Kontoverantwortliche, REQ-BANK-026/-034). Distinct from {@link
   * #BANK_BOOKING_REQUEST_CONFIRMED} so the responsible holder gets account-centric text ("a
   * request on your account was confirmed") rather than the requester-directed text; the seeded
   * rule resolves the responsible holder via the {@code ACCOUNT_RESPONSIBLE} selector.
   */
  BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED,

  /**
   * A bank employee rejected a booking request raised against an account the recipient is the
   * <em>responsible holder</em> of (Kontoverantwortliche, REQ-BANK-026/-034). Distinct from {@link
   * #BANK_BOOKING_REQUEST_REJECTED} so the responsible holder gets account-centric text (the reason
   * is rendered); the seeded rule resolves the responsible holder via the {@code
   * ACCOUNT_RESPONSIBLE} selector.
   */
  BANK_BOOKING_REQUEST_RESPONSIBLE_REJECTED,

  /**
   * A new Discord user registered and is awaiting admin approval (epic #720, Track 1). The seeded
   * default rule notifies every admin; the frontend renders it under {@code
   * notifications.type.DISCORD_REGISTRATION_PENDING} with the {@code username} render parameter.
   */
  DISCORD_REGISTRATION_PENDING,

  /**
   * A member registered interest in a Materialbörse offer (#1187, REQ-MARKET-011). The seeded
   * default rule notifies the offer's owner (the Anbieter); the frontend renders it under {@code
   * notifications.type.MATERIAL_EXCHANGE_INTEREST_REGISTERED} with the {@code interessent} and
   * {@code material} render parameters. Disclosing the interessent's name to the owner is permitted
   * because the notification is delivered only to the owner (REQ-MARKET-006 anonymity is
   * owner-only).
   */
  MATERIAL_EXCHANGE_INTEREST_REGISTERED
}
