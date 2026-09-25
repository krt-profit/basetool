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
import org.jetbrains.annotations.NotNull;

/**
 * A member's interest registration ("Interesse anmelden") on a {@link MaterialExchangeOffer}
 * (REQ-MARKET-006); the trade itself happens off-tool.
 *
 * <p>Signal-only, independent aggregate: registering never bumps the offer's {@code @Version}.
 * Unique per {@code (offer_id, interested_user_id)}; withdrawing deletes the row. Names are shown
 * only to the offer's owner, redacted in the service.
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
   * Renders the interest from its id and the associated ids only, so logging it never triggers a
   * lazy load or exposes a user's name or e-mail.
   *
   * @return a PII-free single-line representation
   */
  @NotNull
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
