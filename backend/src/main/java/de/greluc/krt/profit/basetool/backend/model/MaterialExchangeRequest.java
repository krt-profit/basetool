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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

/**
 * A wanted-listing (Gesuch) on the Materialbörse (REQ-MARKET-015): what a member is looking for, in
 * which minimum quality and quantity; negotiation happens off-tool.
 *
 * <ul>
 *   <li>A {@link MaterialExchangeRequestKind#MATERIAL} request names {@link #requestedMaterial} and
 *       {@link #requestedAmount}.
 *   <li>An {@link MaterialExchangeRequestKind#ITEM} request names a craftable item by {@link
 *       #itemProductKey} with {@link #itemQuantity}.
 * </ul>
 *
 * <p>An optional {@link #minQuality} applies to either kind. There is no backing Lager row.
 * Requests are signal-only; supply signals live in {@link MaterialExchangeRequestInterest}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "material_exchange_request")
public class MaterialExchangeRequest extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * The request kind, deciding whether {@link #requestedMaterial}/{@link #requestedAmount} or
   * {@link #itemProductKey}/{@link #itemName}/{@link #itemQuantity} is populated; a DB {@code
   * CHECK} enforces the exclusivity.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "request_kind", nullable = false, length = 16)
  private MaterialExchangeRequestKind kind;

  /**
   * The requested catalogue material: set for a material request, {@code null} for an item request.
   * Deleting the material cascades to the request.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "requested_material_id")
  private Material requestedMaterial;

  /**
   * The normalized blueprint {@code product_key} of an item request ({@code null} for a material
   * request), validated on posting so only items an active blueprint produces can be requested.
   */
  @Column(name = "item_product_key", length = 255)
  private String itemProductKey;

  /**
   * The display spelling of the requested item, snapshotted from the resolved blueprint product at
   * posting time ({@code null} for a material request). Stored so the board and detail never have
   * to re-resolve the product; it is the audit subject label of an item request.
   */
  @Column(name = "item_name", length = 255)
  private String itemName;

  /**
   * The desired quantity (whole pieces) of an {@link MaterialExchangeRequestKind#ITEM} request
   * ({@code null} for a material request). The requester states this number; the DB {@code CHECK}
   * (V224) requires it to be positive. It is an unbacked point-in-time wish — there is no stock to
   * clamp against.
   */
  @Column(name = "item_quantity")
  private Integer itemQuantity;

  /**
   * The desired quantity of a material request in the material's own unit (SCU or Stück), rounded
   * to three decimals and positive; {@code null} for an item request.
   */
  @Column(name = "requested_amount")
  private Double requestedAmount;

  /**
   * Optional minimum desired quality (0–1000) for either request kind; {@code null} when no floor
   * is stated.
   */
  @Column(name = "min_quality")
  private Integer minQuality;

  /**
   * The requesting player (the Suchende) — stamped from the acting member at posting. The
   * requester's handle is shown to everyone on the board ("gesucht von {Spieler}"); it is the
   * interessenten (would-be supplier) names that stay owner-only, not the requester.
   */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id", nullable = false)
  private User owner;

  /**
   * The requesting player's org unit at posting, used for the squadron badge; {@code null} when the
   * requester belongs to no Staffel/SK or the org unit was removed.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id")
  private OrgUnit owningOrgUnit;

  /**
   * Free-form Markdown description, up to 20 000 characters, rendered server-side through the
   * sanitizing {@code @markdown} renderer. Only its length ever enters an audit payload.
   */
  @Column(name = "remark", length = 20000)
  private String remark;

  /** Whether the request is publicly listed ({@code ACTIVE}) or taken off the board. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private MaterialExchangeRequestStatus status;

  /** The instant the request was last posted to the board; drives "Gesucht vor X". */
  @Column(name = "posted_at", nullable = false)
  private Instant postedAt;

  /**
   * Renders the request from its id, kind, status and associated ids only, so logging it never
   * triggers a lazy load or exposes the owner's name or e-mail.
   *
   * @return a PII-free single-line representation
   */
  @NotNull
  @Override
  public String toString() {
    return "MaterialExchangeRequest{id="
        + id
        + ", kind="
        + kind
        + ", requestedMaterialId="
        + (requestedMaterial != null ? requestedMaterial.getId() : null)
        + ", itemProductKey="
        + itemProductKey
        + ", itemQuantity="
        + itemQuantity
        + ", requestedAmount="
        + requestedAmount
        + ", minQuality="
        + minQuality
        + ", ownerId="
        + (owner != null ? owner.getId() : null)
        + ", owningOrgUnitId="
        + (owningOrgUnit != null ? owningOrgUnit.getId() : null)
        + ", status="
        + status
        + ", postedAt="
        + postedAt
        + '}';
  }
}
