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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link P4kReviewCase}. */
public interface P4kReviewCaseRepository extends JpaRepository<P4kReviewCase, UUID> {

  /**
   * Finds the case of one record and reason.
   *
   * @param kind the kind
   * @param guid the P4K GUID
   * @param reason the reason
   * @return the case, if one exists
   */
  Optional<P4kReviewCase> findByKindAndGuidAndReason(
      P4kKind kind, UUID guid, P4kReviewReason reason);

  /**
   * Pages the cases of one status, oldest first (REQ-P4K-016).
   *
   * @param status the status
   * @param pageable the page
   * @return the page of cases
   */
  Page<P4kReviewCase> findByStatusOrderByCreatedAtAscIdAsc(
      P4kReviewStatus status, Pageable pageable);

  /**
   * Finds the cases of one status, kind and set of reasons.
   *
   * @param status the status
   * @param kind the kind
   * @param reasons the reasons
   * @return the cases
   */
  List<P4kReviewCase> findByStatusAndKindAndReasonIn(
      P4kReviewStatus status, P4kKind kind, Collection<P4kReviewReason> reasons);
}
