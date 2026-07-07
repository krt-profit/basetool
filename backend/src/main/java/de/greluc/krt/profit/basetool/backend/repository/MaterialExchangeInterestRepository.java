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

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeInterest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeInterestCount;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link MaterialExchangeInterest}. */
@Repository
public interface MaterialExchangeInterestRepository
    extends JpaRepository<MaterialExchangeInterest, UUID> {

  /**
   * Returns a member's existing interest registration on one offer, if any — the upsert lookup that
   * keeps the one-registration-per-{@code (offer, user)} invariant (a duplicate "Interesse
   * anmelden" is a no-op).
   *
   * @param offerId the offer.
   * @param interestedUserId the member.
   * @return the existing registration, or empty.
   */
  Optional<MaterialExchangeInterest> findByOfferIdAndInterestedUserId(
      UUID offerId, UUID interestedUserId);

  /**
   * Whether a member has already registered interest on one offer — drives the "du dabei" /
   * toggle-to-withdraw state for that single offer.
   *
   * @param offerId the offer.
   * @param interestedUserId the member.
   * @return {@code true} if a registration exists.
   */
  boolean existsByOfferIdAndInterestedUserId(UUID offerId, UUID interestedUserId);

  /**
   * Counts the interessenten on one offer — the anonymity-safe "N Interessenten" figure every
   * non-owner viewer sees (no names).
   *
   * @param offerId the offer.
   * @return the number of registrations on that offer.
   */
  long countByOfferId(UUID offerId);

  /**
   * Loads the interessenten of one offer newest-first, eager-loading the {@code interestedUser} so
   * the owner-only name list renders without an N+1. Only ever called for the offer's owner (the
   * service enforces the anonymity gate before disclosing names).
   *
   * @param offerId the offer.
   * @return the registrations on that offer, never {@code null}.
   */
  @EntityGraph(attributePaths = {"interestedUser"})
  List<MaterialExchangeInterest> findByOfferIdOrderByCreatedAtDesc(UUID offerId);

  /**
   * Grouped interessenten counts across a batch of offers — one row per offer with at least one
   * registration — so the board list attaches every count in a single query (no N+1). Offers with
   * zero registrations are simply absent from the result (the service defaults them to 0). Returns
   * only ids and counts, never interessent identities (REQ-MARKET-006).
   *
   * @param offerIds the offers being rendered; an empty collection yields an empty list.
   * @return the per-offer counts, never {@code null}.
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeInterestCount("
          + "i.offer.id, COUNT(i)) "
          + "FROM MaterialExchangeInterest i WHERE i.offer.id IN :offerIds GROUP BY i.offer.id")
  List<MaterialExchangeInterestCount> countByOfferIdIn(
      @Param("offerIds") Collection<UUID> offerIds);

  /**
   * Returns the subset of the given offers the viewer has personally registered interest on — so
   * the board list can mark the viewer's own registrations ("du dabei") in one query.
   *
   * @param viewerId the viewing member.
   * @param offerIds the offers being rendered; an empty collection yields an empty set.
   * @return the ids of the offers the viewer is registered on, never {@code null}.
   */
  @Query(
      "SELECT i.offer.id FROM MaterialExchangeInterest i "
          + "WHERE i.interestedUser.id = :viewerId AND i.offer.id IN :offerIds")
  Set<UUID> findOfferIdsInterestedByViewer(
      @Param("viewerId") UUID viewerId, @Param("offerIds") Collection<UUID> offerIds);

  /**
   * Withdraws a member's interest from one offer ("Interesse zurückziehen"). Idempotent — deleting
   * a non-existent registration removes zero rows.
   *
   * @param offerId the offer.
   * @param interestedUserId the member withdrawing.
   * @return the number of rows removed (0 or 1).
   */
  @Modifying
  long deleteByOfferIdAndInterestedUserId(UUID offerId, UUID interestedUserId);
}
