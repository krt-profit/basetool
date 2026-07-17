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

/**
 * A single offer on the Materialbörse — something its owner has released for trade
 * (REQ-MARKET-001…). The Börse is a central, org-wide-visible marketplace that shows only <b>which
 * player offers what, in which quality and quantity</b>; negotiation and handover happen off-tool
 * between the players.
 *
 * <p>An offer is one of two {@link MaterialExchangeOfferKind kinds}, discriminated by {@link
 * #kind}:
 *
 * <ul>
 *   <li>A {@link MaterialExchangeOfferKind#MATERIAL} offer is a thin overlay on a {@link
 *       InventoryItem}: material and quality are read <b>live</b> from {@link #inventoryItem}
 *       (single source of truth, no drift), while the offered quantity is the owner-chosen {@link
 *       #offeredAmount} — a member may offer only a <b>part</b> of the row's stock (REQ-MARKET-002,
 *       ADR-0086). The item's {@code location} is deliberately <b>never</b> read into any board
 *       query or DTO — the Standort stays private (REQ-MARKET-004).
 *   <li>A {@link MaterialExchangeOfferKind#ITEM} offer (#1185, REQ-MARKET-012) references a
 *       craftable item ("an item for which a blueprint exists") by its normalized {@link
 *       #itemProductKey} — with the display {@link #itemName} snapshotted at release — and carries
 *       the owner-stated {@link #itemQuantity}; it never carries an {@link #offeredAmount}, and has
 *       no quality and no location. An item offer comes in two flavours (REQ-MARKET-014, ADR-0108):
 *       a <b>free-stated</b> offer has <b>no</b> Lager row ({@link #inventoryItem} {@code null},
 *       craft-on-demand), while a <b>stock-backed</b> offer released from a game-item Lager row
 *       (V220, REQ-INV-029) <b>does</b> carry {@link #inventoryItem} (the physical stock): its
 *       {@link #itemQuantity} is validated {@code <=} the row's whole-unit stock at release/edit
 *       and ratcheted down on every stock decrement (REQ-MARKET-013), and its {@link
 *       #itemProductKey}/ {@link #itemName} are derived from the row's game item via its blueprint
 *       product.
 * </ul>
 *
 * <p>{@link #owner} and {@link #owningOrgUnit} are denormalised at release time (from the item for
 * a material offer, from the acting member for an item offer) so the board list and the "Meine
 * Angebote" filter never have to join for ownership or the squadron badge.
 *
 * <p>Offers are <b>signal-only</b>: releasing one never moves inventory. Interest registrations are
 * an independent aggregate ({@link MaterialExchangeInterest}, no mapped collection here), so
 * registering or withdrawing interest never bumps this offer's {@code @Version}. A partial-unique
 * constraint {@code (inventory_item_id) WHERE status = 'ACTIVE'} (V210) enforces one active offer
 * per Lager row, so re-releasing an item re-activates the row instead of inserting a duplicate — it
 * governs material offers and <b>stock-backed</b> item offers alike (both carry a non-null {@code
 * inventory_item_id}). <b>Free-stated</b> item offers carry a {@code NULL} {@code
 * inventory_item_id} (distinct under that partial index) and are deliberately not de-duplicated (a
 * member may list the same item several times, REQ-MARKET-012).
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
   * Which kind of offer this is — a Lager-backed {@link MaterialExchangeOfferKind#MATERIAL} offer
   * or a blueprint-product {@link MaterialExchangeOfferKind#ITEM} offer. Drives which of the two
   * mutually-exclusive branches ({@link #inventoryItem} vs {@link #itemProductKey}/{@link
   * #itemName}/ {@link #itemQuantity}) is populated; the DB {@code CHECK} (V213) enforces the
   * exclusivity.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "offer_kind", nullable = false, length = 16)
  private MaterialExchangeOfferKind kind;

  /**
   * The source Lager row this offer is backed by. Always set for a {@link
   * MaterialExchangeOfferKind#MATERIAL} offer (its material, quality and amount are read live from
   * it) and for a <b>stock-backed</b> {@link MaterialExchangeOfferKind#ITEM} offer (a game-item
   * Lager row whose whole-unit stock caps {@link #itemQuantity}); {@code null} only for a
   * <b>free-stated</b> item offer (craft-on-demand, REQ-MARKET-012/014). The row's location is
   * never read. {@code ON DELETE CASCADE} (V210) removes the offer when the underlying stock row is
   * deleted, so the board never lists an offer whose stock no longer exists. The V221 CHECK relaxed
   * the V213 rule that forbade an item offer from carrying this FK.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "inventory_item_id")
  private InventoryItem inventoryItem;

  /**
   * The normalized blueprint {@code product_key} of an {@link MaterialExchangeOfferKind#ITEM} offer
   * ({@code null} for a material offer). This is the canonical identity of a craftable item, shared
   * with {@code personal_blueprint} / {@code default_blueprint}; a release validates it against
   * {@code BlueprintProductService.resolveByProductKey(...)} so only items an active blueprint
   * produces can be listed (#1185).
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
   * The quantity (whole pieces) of an {@link MaterialExchangeOfferKind#ITEM} offer ({@code null}
   * for a material offer). The owner states this number; the DB {@code CHECK} (V213) requires it to
   * be positive. For a <b>free-stated</b> item offer it is an unbacked point-in-time claim; for a
   * <b>stock-backed</b> item offer (with {@link #inventoryItem} set) the service validates it
   * {@code <=} the row's current stock at release/edit and the ratchet persists it down on every
   * stock decrement (REQ-MARKET-013/014), so it never exceeds the physical stock.
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
   * The offering player's org unit at release, used to render the squadron badge and detect a
   * foreign-squadron offer. Nullable for an ownerless-personal item whose owner belongs to no
   * Staffel/SK; {@code ON DELETE SET NULL} (V210) drops the badge if the org unit is later removed.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id")
  private OrgUnit owningOrgUnit;

  /**
   * The offered quantity in SCU for a {@link MaterialExchangeOfferKind#MATERIAL} offer — the part
   * of the linked item's stock the owner releases to the board (REQ-MARKET-002, ADR-0086); {@code
   * null} for an {@link MaterialExchangeOfferKind#ITEM} offer, which states its quantity in {@link
   * #itemQuantity} instead. Unlike material and quality (read live from {@link #inventoryItem}),
   * this is a stored, owner-chosen value: it may be the whole row or only a part of it. The service
   * validates it to be {@code > 0} and {@code <=} the item's <em>current</em> amount at every
   * release and edit, and clamps it to the item's current stock on read (never advertising more
   * than is in stock, ADR-0086). Backfilled (V212) to the item's amount for pre-partial-offer rows;
   * V213 relaxed its {@code NOT NULL} so item offers can leave it {@code null}.
   */
  @Column(name = "offered_amount")
  private Double offeredAmount;

  /**
   * The trade remark — free-form Markdown ("was suchst du im Gegenzug?"), up to 20 000 characters.
   * Stored raw; rendered server-side through the sanitizing {@code @markdown} renderer on display
   * (never a client-side Markdown library). Never copied into an audit details payload — only its
   * length is recorded.
   */
  @Column(name = "remark", length = 20000)
  private String remark;

  /** Whether the offer is publicly listed ({@code ACTIVE}) or taken off the board. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private MaterialExchangeOfferStatus status;

  /** The instant the offer was (last) released to the board — drives "Freigegeben vor X". */
  @Column(name = "released_at", nullable = false)
  private Instant releasedAt;

  /**
   * Renders the offer using only safe scalar identifiers — its own id, the status, and the
   * (null-safe) foreign-key ids of the associated entities. Deliberately does <b>not</b> call
   * {@code toString()} on the {@code @ManyToOne} associations: those are {@code FetchType.LAZY}, so
   * dereferencing them could trigger a lazy load (or fail outside a session), and the {@link
   * #owner} must never surface as a name/email in a log line. Reading only the foreign-key id off a
   * lazy proxy does not initialise it.
   *
   * @return a stable, PII-free single-line representation of this offer.
   */
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
