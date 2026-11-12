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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One truthful change of a contribution's outstanding quantity or historical credit.
 *
 * @param reason the bounded business cause
 * @param previousAmount the open promise before the change
 * @param amount the open promise after the change
 * @param creditDelta the change of truthful fulfilled contribution
 * @param recordedAt the change instant
 * @param creditId connected provision identity for attribution and correction
 */
public record MaterialContributionChange(
    Reason reason,
    double previousAmount,
    double amount,
    double creditDelta,
    Instant recordedAt,
    @Nullable UUID creditId) {
  /** Distinguishes promised quantities, cancelled need, real provision and false facts. */
  public enum Reason {
    /** A permitted writer deliberately changed the outstanding promise. */
    PROMISE_CHANGED,
    /** A genuine explicitly attributed provision fulfilled an outstanding promise. */
    PROVISION_CREDITED,
    /** Uncovered demand no longer required part of the outstanding promise. */
    CAPACITY_REDUCED,
    /** The material-quality requirement ceased to exist. */
    REQUIREMENT_ENDED,
    /** A false provision quantity or unit attribution was corrected. */
    FALSE_FACT_CORRECTED,
    /** A permitted writer withdrew only this outstanding material promise. */
    PROMISE_WITHDRAWN,
    /** The contributing Staffel ceased to be eligible in the active Profit hierarchy. */
    UNIT_INELIGIBLE,
    /** The order's terminal lifecycle ended its outstanding material promises. */
    ORDER_ENDED,
    /** The permitted promise lifecycle ended the outstanding contribution. */
    PROMISE_ENDED
  }
}
