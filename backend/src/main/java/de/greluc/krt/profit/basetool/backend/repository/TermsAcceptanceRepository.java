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
import de.greluc.krt.profit.basetool.backend.model.dto.TermsAcceptanceStatusDto;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

  /**
   * Backs the admin consent overview: every login-capable user together with the moment they
   * accepted the given wording, or {@code null} where they have not (REQ-SEC-028).
   *
   * <p>The query is rooted in {@code User} rather than in this repository's own entity because the
   * question is "which users are still missing", and only a left join from the user side can
   * produce a row for someone who has no acceptance at all. It lives here rather than in {@code
   * UserRepository} to keep the terms-consent reads in one place.
   *
   * <p>Restricted to {@code inKeycloak = true} on purpose: an account whose login was already
   * removed from Keycloak cannot sign in and therefore cannot ever accept, so including it would
   * park a permanently-pending row in the admin's worklist and make the overview unusable as one.
   *
   * @param termsVersion the wording to report against — normally the version currently in force
   * @param filter {@code ALL}, {@code ACCEPTED} (only users who accepted) or {@code PENDING} (only
   *     users who have not); passed as a string so no nullable-boolean parameter typing is involved
   * @param pageable page, size and sort; sort fields are whitelisted by the controller
   *     (REQ-API-005)
   * @return one page of consent rows
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.TermsAcceptanceStatusDto(
               u.id, u.username, u.displayName, ta.acceptedAt)
      FROM User u
      LEFT JOIN TermsAcceptance ta
             ON ta.userId = u.id AND ta.termsVersion = :termsVersion
      WHERE u.inKeycloak = true
        AND (:filter = 'ALL'
             OR (:filter = 'ACCEPTED' AND ta.acceptedAt IS NOT NULL)
             OR (:filter = 'PENDING' AND ta.acceptedAt IS NULL))
      """)
  Page<TermsAcceptanceStatusDto> findAcceptanceStatus(
      @Param("termsVersion") String termsVersion,
      @Param("filter") String filter,
      Pageable pageable);

  /**
   * Counts login-capable users who have <em>not</em> accepted the given wording — the headline
   * number of the admin overview.
   *
   * @param termsVersion the wording to report against
   * @return how many users who can still sign in are missing consent for that version
   */
  @Query(
      """
      SELECT COUNT(u)
      FROM User u
      LEFT JOIN TermsAcceptance ta
             ON ta.userId = u.id AND ta.termsVersion = :termsVersion
      WHERE u.inKeycloak = true AND ta.acceptedAt IS NULL
      """)
  long countPendingUsers(@Param("termsVersion") String termsVersion);
}
