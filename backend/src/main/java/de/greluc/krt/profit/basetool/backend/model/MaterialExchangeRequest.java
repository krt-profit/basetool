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
 * A single wanted-listing (Gesuch) on the Materialbörse — something its owner is <b>looking for</b>
 * (REQ-MARKET-015…). It is the inverse of a {@link MaterialExchangeOffer}: instead of releasing
 * owned stock, a member advertises what they want, in which minimum quality and quantity, so other
 * members can signal they can supply it. Negotiation and handover happen off-tool between the
 * players, exactly like an offer (REQ-MARKET-003, signal-only).
 *
 * <p>A request is one of two {@link MaterialExchangeRequestKind kinds}, discriminated by {@link
 * #kind}:
 *
 * <ul>
 *   <li>A {@link MaterialExchangeRequestKind#MATERIAL} request names a catalogue {@link Material}
 *       ({@link #requestedMaterial}) the member wants, in a stated {@link #requestedAmount} (SCU or
 *       Stück per the material's {@link Material#getQuantityType() quantity type}); {@link
 *       #itemProductKey}/{@link #itemName}/{@link #itemQuantity} are {@code null}.
 *   <li>An {@link MaterialExchangeRequestKind#ITEM} request names a craftable item ("an item for
 *       which a blueprint exists") by its normalized {@link #itemProductKey} — with the display
 *       {@link #itemName} snapshotted at posting — in a stated whole-piece {@link #itemQuantity};
 *       {@link #requestedMaterial}/{@link #requestedAmount} are {@code null}.
 * </ul>
 *
 * <p>Either kind may carry an optional {@link #minQuality} (0–1000) the requester desires; unlike
 * an offer's live-read quality this is a stated preference and is kept even for an item request
 * (items carry no intrinsic quality, so it is purely the requester's wish, REQ-MARKET-015). There
 * is deliberately <b>no backing {@code InventoryItem}</b>: the member states the identity and
 * quantity directly (closest to a free-stated item offer), so none of the offer's stock-derived
 * rules (clamp-on-read, ratchet-on-decrement, one-active-per-Lager-row) apply.
 *
 * <p>{@link #owner} (the requester) and {@link #owningOrgUnit} are stamped from the acting member
 * at posting time so the board list and the "Meine Gesuche" filter never have to join for ownership
 * or the squadron badge.
 *
 * <p>Requests are <b>signal-only</b>: posting one never moves inventory. Fulfilment signals ("Ich
 * kann liefern") are an independent aggregate ({@link MaterialExchangeRequestInterest}, no mapped
 * collection here), so signalling or withdrawing never bumps this request's {@code @Version}. There
 * is no one-active-per-row uniqueness — a member may list the same material or item several times
 * (REQ-MARKET-015).
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
   * Which kind of request this is — a catalogue {@link MaterialExchangeRequestKind#MATERIAL}
   * request or a blueprint-product {@link MaterialExchangeRequestKind#ITEM} request. Drives which
   * of the two mutually-exclusive branches ({@link #requestedMaterial}/{@link #requestedAmount} vs
   * {@link #itemProductKey}/{@link #itemName}/{@link #itemQuantity}) is populated; the DB {@code
   * CHECK} (V224) enforces the exclusivity.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "request_kind", nullable = false, length = 16)
  private MaterialExchangeRequestKind kind;

  /**
   * The catalogue material this request is for — always set for a {@link
   * MaterialExchangeRequestKind#MATERIAL} request, {@code null} for an {@link
   * MaterialExchangeRequestKind#ITEM} request. {@code ON DELETE CASCADE} (V224) removes the request
   * if the material is ever deleted, so the board never lists a request whose material no longer
   * exists.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "requested_material_id")
  private Material requestedMaterial;

  /**
   * The normalized blueprint {@code product_key} of an {@link MaterialExchangeRequestKind#ITEM}
   * request ({@code null} for a material request). This is the canonical identity of a craftable
   * item, shared with {@code personal_blueprint} / {@code default_blueprint}; a posting validates
   * it against {@code BlueprintProductService.resolveByProductKey(...)} so only items an active
   * blueprint produces can be requested (mirroring REQ-MARKET-012).
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
   * The desired quantity of a {@link MaterialExchangeRequestKind#MATERIAL} request, in the
   * material's own unit (SCU for bulk materials, Stück for {@code PIECE} materials); {@code null}
   * for an {@link MaterialExchangeRequestKind#ITEM} request, which states its quantity in {@link
   * #itemQuantity} instead. Stored as a {@link Double} to carry SCU fractions, rounded to
   * three-decimal SCU precision on write. The DB {@code CHECK} (V224) requires it to be positive.
   */
  @Column(name = "requested_amount")
  private Double requestedAmount;

  /**
   * The optional minimum desired quality (0–1000) the requester is looking for. May be set for
   * <b>either</b> kind (REQ-MARKET-015): a material request naturally has a desired quality, and an
   * item request may carry one as a pure requester preference even though items have no intrinsic
   * quality. {@code null} when the requester states no quality floor. The 0–1000 bound is enforced
   * by the DB {@code CHECK} (V224), which — unlike an offer — the request owns directly, since
   * there is no backing Lager row to inherit it from.
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
   * The requesting player's org unit at posting, used to render the squadron badge. Nullable for a
   * requester who belongs to no Staffel/SK; {@code ON DELETE SET NULL} (V224) drops the badge if
   * the org unit is later removed.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id")
  private OrgUnit owningOrgUnit;

  /**
   * The free-form Markdown description ("was bietest du im Gegenzug? / weitere Details"), up to 20
   * 000 characters. Stored raw; rendered server-side through the sanitizing {@code @markdown}
   * renderer on display (never a client-side Markdown library). Never copied into an audit details
   * payload — only its length is recorded.
   */
  @Column(name = "remark", length = 20000)
  private String remark;

  /** Whether the request is publicly listed ({@code ACTIVE}) or taken off the board. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private MaterialExchangeRequestStatus status;

  /** The instant the request was (last) posted to the board — drives "Gesucht vor X". */
  @Column(name = "posted_at", nullable = false)
  private Instant postedAt;

  /**
   * Renders the request using only safe scalar identifiers — its own id, the kind/status, and the
   * (null-safe) foreign-key ids of the associated entities. Deliberately does <b>not</b> call
   * {@code toString()} on the {@code @ManyToOne} associations: those are {@code FetchType.LAZY}, so
   * dereferencing them could trigger a lazy load (or fail outside a session), and the {@link
   * #owner} must never surface as a name/email in a log line. Reading only the foreign-key id off a
   * lazy proxy does not initialise it.
   *
   * @return a stable, PII-free single-line representation of this request.
   */
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
