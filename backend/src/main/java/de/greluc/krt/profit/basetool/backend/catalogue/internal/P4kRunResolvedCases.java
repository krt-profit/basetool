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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The lifecycle of the review cases an APPLY derives from rows that carry no GUID of their own
 * (REQ-P4K-016): a run closes an open case it no longer finds as {@code RESOLVED} and reopens such
 * a case once it finds it again; an admin's decision is never reopened. The case GUID is a
 * name-based UUID of a stable key.
 */
@Component
@RequiredArgsConstructor
public class P4kRunResolvedCases {

  private final P4kReviewCaseRepository caseRepository;

  /**
   * One case a run found.
   *
   * @param guid the case GUID
   * @param reason the reason
   */
  public record Found(@NotNull UUID guid, @NotNull P4kReviewReason reason) {}

  /**
   * Derives the stable GUID of a case from its key.
   *
   * @param key the stable key, prefixed by its owner
   * @return a name-based UUID of the key
   */
  @NotNull
  public static UUID caseGuid(@NotNull String key) {
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Settles the cases of the given reasons with one read: a case a run closed is opened again when
   * this run found it once more, and an open case this run did not find is closed as {@code
   * RESOLVED}.
   *
   * @param kind the kind
   * @param reasons the reasons the caller owns
   * @param found the cases this run found
   * @param now the run time, stamped on the closed cases
   */
  public void settle(
      @NotNull P4kKind kind,
      @NotNull List<P4kReviewReason> reasons,
      @NotNull Set<Found> found,
      @NotNull Instant now) {
    for (P4kReviewCase reviewCase : caseRepository.findByKindAndReasonIn(kind, reasons)) {
      boolean foundAgain = found.contains(new Found(reviewCase.getGuid(), reviewCase.getReason()));
      if (foundAgain && reviewCase.getResolution() == P4kReviewResolution.RESOLVED) {
        reviewCase.setStatus(P4kReviewStatus.OPEN);
        reviewCase.setResolution(null);
        reviewCase.setResolvedAt(null);
      } else if (!foundAgain && reviewCase.getStatus() == P4kReviewStatus.OPEN) {
        reviewCase.setStatus(P4kReviewStatus.REVIEWED);
        reviewCase.setResolution(P4kReviewResolution.RESOLVED);
        reviewCase.setResolvedAt(now);
      }
    }
  }
}
