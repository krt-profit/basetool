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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Which render parameters of a notification carry a person's name (REQ-SEC-062). Every key a notice
 * writes is in exactly one of the two sets, which {@code NotificationPersonParamsTest} holds
 * against the sources.
 */
final class NotificationPersonParams {

  /** Keys whose value is the name of a member or of another person. */
  static final @Unmodifiable Set<String> PERSON_KEYS =
      Set.of(
          "actor",
          "approver",
          "donor",
          "handle",
          "holder",
          "interessent",
          "lieferant",
          "member",
          "newOwner",
          "requester",
          "username");

  /**
   * Keys whose value names no person: codes, numbers, amounts, organisational units and the names
   * of things; free text such as a rejection reason expires with the notification.
   */
  static final @Unmodifiable Set<String> OTHER_KEYS =
      Set.of(
          "accountNo",
          "actionCode",
          "amount",
          "app",
          "balance",
          "canDepositCode",
          "canTransferCode",
          "canWithdrawCode",
          "capabilities",
          "changeCode",
          "count",
          "debitCode",
          "displayId",
          "donation",
          "entry",
          "fee",
          "from",
          "fromUnit",
          "grantChangeCode",
          "item",
          "jobOrderType",
          "location",
          "lots",
          "material",
          "minimum",
          "mismatchCode",
          "mission",
          "open",
          "operation",
          "order",
          "orgUnit",
          "outputs",
          "paid",
          "pending",
          "rankCode",
          "reason",
          "reasonCode",
          "roleCode",
          "scopeCode",
          "seatCode",
          "shipType",
          "statusCode",
          "subject",
          "supportCode",
          "to",
          "total",
          "toUnit",
          "type",
          "unfinished",
          "unit",
          "vacancyCode",
          "version");

  private NotificationPersonParams() {}

  /**
   * Returns {@code params} with every person value that equals {@code spelling}, ignoring case,
   * replaced by {@code sentinel}; other values and keys stay as they are.
   *
   * @param params the stored render parameters
   * @param spelling one name the member is stored under
   * @param sentinel the erasure sentinel
   * @return the redacted parameters, in the original order
   */
  static @NotNull Map<String, String> redact(
      @NotNull Map<String, String> params, @NotNull String spelling, @NotNull String sentinel) {
    Map<String, String> redacted = new LinkedHashMap<>(params);
    redacted.replaceAll((key, value) ->
        PERSON_KEYS.contains(key) && spelling.equalsIgnoreCase(value) ? sentinel : value);
    return redacted;
  }
}
