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

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A member's <em>interest</em> registration on a {@link MaterialExchangeOffer} — "Interesse
 * anmelden" (REQ-MARKET-006). It records that a member wants the anbieter to open the negotiation;
 * the actual trade happens off-tool.
 *
 * <p>Like {@link MaterialClaim}, interest is a <b>signal-only, independent aggregate</b>: no mapped
 * collection lives on the offer, so registering or withdrawing interest never bumps the offer's
 * {@code @Version}. A unique constraint on {@code (offer_id, interested_user_id)} (V210) makes
 * "Interesse anmelden" an idempotent upsert (the service treats a duplicate-key race as success);
 * withdrawing deletes the row.
 *
 * <p><b>Anonymity (REQ-MARKET-006):</b> the interessenten names are visible only to the offer's
 * owner. Every other viewer receives just the count — the redaction is applied in the service,
 * never by exposing this entity or a name-carrying projection to non-owners.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "material_exchange_interest")
public class MaterialExchangeInterest extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The offer this interest is registered against. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "offer_id", nullable = false)
  private MaterialExchangeOffer offer;

  /** The member who registered interest. Their handle is disclosed only to the offer's owner. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "interested_user_id", nullable = false)
  private User interestedUser;

  /**
   * Renders the interest using only safe scalar identifiers — its own id and the (null-safe)
   * foreign-key ids of the offer and the interested user. Deliberately does <b>not</b> call {@code
   * toString()} on the {@code @ManyToOne} associations: those are {@code FetchType.LAZY}, and the
   * {@link #interestedUser} must never surface as a name/email in a log line. Reading only the
   * foreign-key id off a lazy proxy does not initialise it.
   *
   * @return a stable, PII-free single-line representation of this interest registration.
   */
  @Override
  public String toString() {
    return "MaterialExchangeInterest{id="
        + id
        + ", offerId="
        + (offer != null ? offer.getId() : null)
        + ", interestedUserId="
        + (interestedUser != null ? interestedUser.getId() : null)
        + '}';
  }
}
