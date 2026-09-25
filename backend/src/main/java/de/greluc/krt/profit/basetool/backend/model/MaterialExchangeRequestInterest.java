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
 * A member's fulfilment signal ("Ich kann liefern") on a {@link MaterialExchangeRequest}
 * (REQ-MARKET-019); the trade itself happens off-tool.
 *
 * <p>Signal-only, independent aggregate: signalling never bumps the request's {@code @Version}.
 * Unique per {@code (request_id, interested_user_id)}; withdrawing deletes the row. Names are shown
 * only to the request's owner, redacted in the service.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "material_exchange_request_interest")
public class MaterialExchangeRequestInterest extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The request this fulfilment signal is registered against. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "request_id", nullable = false)
  private MaterialExchangeRequest request;

  /**
   * The member who signalled they can supply the request. Their handle is disclosed only to the
   * request's owner.
   */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "interested_user_id", nullable = false)
  private User interestedUser;

  /**
   * Renders the signal from its id and the associated ids only, so logging it never triggers a lazy
   * load or exposes a user's name or e-mail.
   *
   * @return a PII-free single-line representation
   */
  @NotNull
  @Override
  public String toString() {
    return "MaterialExchangeRequestInterest{id="
        + id
        + ", requestId="
        + (request != null ? request.getId() : null)
        + ", interestedUserId="
        + (interestedUser != null ? interestedUser.getId() : null)
        + '}';
  }
}
