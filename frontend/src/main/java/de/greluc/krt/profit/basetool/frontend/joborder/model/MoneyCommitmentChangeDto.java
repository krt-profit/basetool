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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A unit commitment's recorded change in the order history.
 *
 * @param reason what changed the commitment
 * @param before the outstanding amount before the change
 * @param after the outstanding amount after the change
 * @param operationId the credited financial operation, when applicable
 * @param recordedAt when the change took effect
 */
@DtoMirror
public record MoneyCommitmentChangeDto(
    Reason reason, String before, String after, @Nullable UUID operationId, Instant recordedAt) {

  /** Reasons a unit commitment changes or ends. */
  public enum Reason {
    OPENED,
    REVISED,
    WITHDRAWN,
    CREDITED,
    FULFILLED,
    CORRECTED,
    PARTICIPATION_ENDED,
    RESPONSIBILITY_ENDED,
    UNIT_INELIGIBLE,
    ORDER_ENDED,
    CURRENCY_WIPE
  }
}
