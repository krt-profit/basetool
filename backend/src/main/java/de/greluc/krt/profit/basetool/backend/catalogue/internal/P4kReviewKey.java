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
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The identity of a P4K review case: one per kind, GUID and reason (REQ-P4K-016), shared by a run's
 * findings and the stored cases.
 *
 * @param kind the kind
 * @param guid the P4K GUID
 * @param reason the reason
 */
public record P4kReviewKey(
    @NotNull P4kKind kind, @NotNull UUID guid, @NotNull P4kReviewReason reason) {

  /**
   * Keys a finding of a run.
   *
   * @param finding the finding
   * @return its key
   */
  @NotNull
  static P4kReviewKey of(@NotNull P4kImportReport.ReviewFinding finding) {
    return new P4kReviewKey(finding.kind(), finding.guid(), finding.reason());
  }

  /**
   * Keys a stored case.
   *
   * @param reviewCase the case
   * @return its key
   */
  @NotNull
  static P4kReviewKey of(@NotNull P4kReviewCase reviewCase) {
    return new P4kReviewKey(reviewCase.getKind(), reviewCase.getGuid(), reviewCase.getReason());
  }
}
