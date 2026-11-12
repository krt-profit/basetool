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
import org.jetbrains.annotations.Nullable;

/**
 * One initial receipt declaration or explicit correction of its retained facts.
 *
 * @param reason whether this is a new observed provision or a false-fact correction
 * @param previous the previously retained declaration, absent only for initial observation
 * @param current the resulting truthful declaration
 * @param recordedAt the instant this declaration or correction was recorded
 */
@DtoMirror
public record MaterialProvisionFactChange(
    Reason reason,
    @Nullable MaterialProvisionFact previous,
    MaterialProvisionFact current,
    Instant recordedAt) {
  /** Distinguishes newly observed provision from correction without a new settlement process. */
  public enum Reason {
    /** A genuine provision captured its original declared facts. */
    PROVISION_RECORDED,
    /** An explicit correction replaced a false original declaration. */
    FALSE_FACT_CORRECTED
  }
}
