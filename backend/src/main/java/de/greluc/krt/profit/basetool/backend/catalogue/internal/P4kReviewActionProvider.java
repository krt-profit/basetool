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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Supplies and applies review proposals for a catalogue kind. */
public interface P4kReviewActionProvider {

  /**
   * Checks whether this provider handles the catalogue kind.
   *
   * @param kind the review case kind
   * @return whether the provider handles the kind
   */
  boolean supports(@NotNull P4kKind kind);

  /**
   * Computes proposals using the current catalogue.
   *
   * @param review the case and its last import evidence
   * @param recordPath the source record path, if recorded
   * @return the options supported by the current source identities
   */
  @NotNull
  P4kReviewOptions options(@NotNull P4kReviewCaseDto review, @Nullable String recordPath);

  /**
   * Summarizes the next available action with request-local source evidence.
   *
   * @param reviews the open cases of the supported kinds
   * @return the current readiness of cases with catalogue proposals
   */
  @NotNull
  default Map<UUID, P4kReviewReadiness> summarize(@NotNull List<P4kReviewCaseSource> reviews) {
    return Map.of();
  }

  /**
   * Applies a revalidated proposal within the review transaction.
   *
   * @param review the current case
   * @param recordPath the source record path, if recorded
   * @param candidate the selected current proposal
   * @param adminUserId the administrator confirming the proposal
   */
  void apply(
      @NotNull P4kReviewCaseDto review,
      @Nullable String recordPath,
      @NotNull P4kReviewCandidate candidate,
      @NotNull UUID adminUserId);
}
