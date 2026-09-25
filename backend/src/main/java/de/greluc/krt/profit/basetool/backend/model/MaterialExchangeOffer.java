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
 * A single offer on the Materialbörse (REQ-MARKET-001): what a player offers, in which quality and
 * quantity; negotiation and handover happen off-tool.
 *
 * <ul>
 *   <li>A {@link MaterialExchangeOfferKind#MATERIAL} offer overlays an {@link InventoryItem},
 *       reading material and quality live from {@link #inventoryItem} and offering {@link
 *       #offeredAmount}; the item's location is never exposed (REQ-MARKET-004).
 *   <li>A {@link MaterialExchangeOfferKind#ITEM} offer names a craftable item by {@link
 *       #itemProductKey} with {@link #itemQuantity}; it is free-stated (no {@link #inventoryItem})
 *       or stock-backed, capped by the Lager row's stock (REQ-MARKET-014).
 * </ul>
 *
 * <p>Offers are signal-only; interest lives in {@link MaterialExchangeInterest}. At most one active
 * offer exists per Lager row; free-stated item offers are not de-duplicated.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "material_exchange_offer")
public class MaterialExchangeOffer extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * The offer kind, deciding whether {@link #inventoryItem} or {@link #itemProductKey}/{@link
   * #itemName}/{@link #itemQuantity} is populated; a DB {@code CHECK} enforces the exclusivity.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "offer_kind", nullable = false, length = 16)
  private MaterialExchangeOfferKind kind;

  /**
   * The Lager row backing this offer: set for a material offer and a stock-backed item offer,
   * {@code null} for a free-stated item offer. Its location is never read; deleting the row
   * cascades to the offer.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "inventory_item_id")
  private InventoryItem inventoryItem;

  /**
   * The normalized blueprint {@code product_key} of an item offer ({@code null} for a material
   * offer), validated on release so only items an active blueprint produces can be listed.
   */
  @Column(name = "item_product_key", length = 255)
  private String itemProductKey;

  /**
   * The display spelling of the offered item, snapshotted from the resolved blueprint product at
   * release time ({@code null} for a material offer). Stored so the board and detail never have to
   * re-resolve the product; it is the audit subject label of an item offer.
   */
  @Column(name = "item_name", length = 255)
  private String itemName;

  /**
   * Whole-piece quantity of an item offer ({@code null} for a material offer); positive. For a
   * stock-backed offer it never exceeds the Lager row's current stock (REQ-MARKET-013).
   */
  @Column(name = "item_quantity")
  private Integer itemQuantity;

  /**
   * The offering player (the Anbieter) — denormalised from {@code inventoryItem.user} at release.
   * The owner's handle is shown to everyone on the board ("von {Spieler}"); it is the interessenten
   * names that stay owner-only, not the anbieter.
   */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id", nullable = false)
  private User owner;

  /**
   * The offering player's org unit at release, used for the squadron badge; {@code null} when the
   * owner belongs to no Staffel/SK or the org unit was removed.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id")
  private OrgUnit owningOrgUnit;

  /**
   * The offered SCU quantity of a material offer, possibly only part of the item's stock
   * (REQ-MARKET-002, ADR-0086); {@code null} for an item offer.
   *
   * <p>Validated {@code > 0} and {@code <=} the item's current amount on release and edit, and
   * clamped to current stock on read.
   */
  @Column(name = "offered_amount")
  private Double offeredAmount;

  /**
   * The trade remark as free-form Markdown, up to 20 000 characters, rendered server-side through
   * the sanitizing {@code @markdown} renderer. Only its length ever enters an audit payload.
   */
  @Column(name = "remark", length = 20000)
  private String remark;

  /** Whether the offer is publicly listed ({@code ACTIVE}) or taken off the board. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private MaterialExchangeOfferStatus status;

  /** The instant the offer was last released to the board; drives "Freigegeben vor X". */
  @Column(name = "released_at", nullable = false)
  private Instant releasedAt;

  /**
   * Renders the offer from its id, status and associated ids only, so logging it never triggers a
   * lazy load or exposes the owner's name or e-mail.
   *
   * @return a PII-free single-line representation
   */
  @NotNull
  @Override
  public String toString() {
    return "MaterialExchangeOffer{id="
        + id
        + ", kind="
        + kind
        + ", inventoryItemId="
        + (inventoryItem != null ? inventoryItem.getId() : null)
        + ", itemProductKey="
        + itemProductKey
        + ", itemQuantity="
        + itemQuantity
        + ", ownerId="
        + (owner != null ? owner.getId() : null)
        + ", owningOrgUnitId="
        + (owningOrgUnit != null ? owningOrgUnit.getId() : null)
        + ", offeredAmount="
        + offeredAmount
        + ", status="
        + status
        + ", releasedAt="
        + releasedAt
        + '}';
  }
}
