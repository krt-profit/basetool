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
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An explicitly attributed provision, separate from current stock coverage.
 *
 * @param id provision identity addressed by connected corrections
 * @param version observed provision version
 * @param amount truthful amount credited to this contribution
 * @param quantityAmount observed total or verified credited subset, distinguished by evidence
 * @param providedAt original provision instant
 * @param canCorrect whether the actor may correct the original attribution
 * @param evidence the verified scope of retained physical quantity and composition
 * @param suppliedQuality exact declared original quality, absent when only its floor is known
 * @param minimumSuppliedQuality the verified original quality floor
 * @param factChanges retained original declarations and explicit corrections
 */
public record MaterialProvisionCreditDto(
    UUID id,
    Long version,
    double amount,
    double quantityAmount,
    Instant providedAt,
    boolean canCorrect,
    MaterialProvisionEvidence evidence,
    @Nullable Integer suppliedQuality,
    int minimumSuppliedQuality,
    @NotNull List<MaterialProvisionFactChange> factChanges) {
  /**
   * Retains immutable receipt declarations independently of transport collections.
   *
   * @param id the receipt identity
   * @param version the observed receipt version
   * @param amount truthful credited quantity
   * @param quantityAmount observed total or known credited subset
   * @param providedAt original provision instant
   * @param canCorrect current correction permission
   * @param evidence retained quantity and composition evidence
   * @param suppliedQuality exact original quality when known
   * @param minimumSuppliedQuality verified original quality floor
   * @param factChanges ordered original and corrected declarations
   */
  public MaterialProvisionCreditDto {
    factChanges = List.copyOf(factChanges);
  }
}
