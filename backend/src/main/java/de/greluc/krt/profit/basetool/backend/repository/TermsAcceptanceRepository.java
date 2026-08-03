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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.TermsAcceptance;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Read/write access to the append-only Terms-of-Use acceptance log (REQ-SEC-028). */
@Repository
public interface TermsAcceptanceRepository extends JpaRepository<TermsAcceptance, UUID> {

  /**
   * Answers the hot per-request question "has this user already accepted this exact wording?".
   * Resolves against {@code uq_terms_acceptance_user_version}, so it is a single index probe and
   * safe to call on the request path.
   *
   * @param userId the accepting user's {@code app_user.id}, i.e. the Keycloak {@code sub}
   * @param termsVersion the content digest of the wording currently in force
   * @return {@code true} if an acceptance row for that user and version exists
   */
  boolean existsByUserIdAndTermsVersion(UUID userId, String termsVersion);

  /**
   * Returns one user's consent history, newest first — the evidence view. Backed by {@code
   * idx_terms_acceptance_user_accepted_at}.
   *
   * @param userId the user's {@code app_user.id}
   * @return every acceptance the user has recorded, most recent first; empty if they never accepted
   */
  List<TermsAcceptance> findByUserIdOrderByAcceptedAtDesc(UUID userId);

  /**
   * Counts how many distinct users have accepted a given wording. Drives the {@code
   * basetool_terms_accepted_users} gauge, which is what makes a stalled rollout visible — after a
   * terms change the count climbs from zero, and a flat line means people are being blocked rather
   * than accepting.
   *
   * @param termsVersion the content digest to count acceptances for
   * @return the number of acceptance rows carrying that version
   */
  long countByTermsVersion(String termsVersion);
}
