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

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Recorded result of one accepted provision attempt.
 *
 * @param operationId submitted logical identity
 * @param holderId credited order-local holder
 * @param amount exact credited whole aUEC
 * @param bankHandling the submitted choice
 * @param recordedAt the booking time
 * @param holderName the permitted current holder label
 * @param actorName the permitted current original actor label
 */
public record JobOrderMoneyOutcome(
    UUID operationId,
    UUID holderId,
    String amount,
    boolean bankHandling,
    Instant recordedAt,
    @Nullable String holderName,
    @Nullable String actorName) {

  static JobOrderMoneyOutcome of(
      JobOrderMoneyOperation operation, @Nullable String holderName, @Nullable String actorName) {
    return new JobOrderMoneyOutcome(
        operation.getOperationId(),
        operation.getHolderId(),
        operation.getAmount().toPlainString(),
        operation.isBankHandling(),
        operation.getRecordedAt(),
        holderName,
        actorName);
  }
}
