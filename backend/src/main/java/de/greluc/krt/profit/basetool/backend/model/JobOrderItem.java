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

import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * One ordered finished-item line of an {@link JobOrderType#ITEM} {@link JobOrder}. Captures which
 * {@link GameItem} is requested, in which quantity ({@link #amount} whole units), produced via the
 * {@link #blueprint} the requester chose for this line (relevant when an item has more than one
 * blueprint). The derived material requirements are snapshotted into {@link #materials} at creation
 * time; {@link #manufacturedAmount} tracks how many units have been produced (consuming linked
 * stock) and {@link #deliveredAmount} tracks fulfilment via item handovers, holding the invariant
 * {@code 0 <= deliveredAmount <= manufacturedAmount <= amount} (a unit can only be delivered once
 * it has been manufactured). When the line was adopted from another line's blueprint sub-assembly
 * suggestion, {@link #parentItem} records that provenance.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "job_order_item")
public class JobOrderItem extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning item order. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_id", nullable = false)
  private JobOrder jobOrder;

  /** The finished item requested on this line. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "game_item_id", nullable = false)
  private GameItem gameItem;

  /**
   * The blueprint chosen to produce {@link #gameItem} on this line. Stored so the material
   * derivation is reproducible and so the picked recipe stays explicit when the item has more than
   * one blueprint.
   */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "blueprint_id", nullable = false)
  private Blueprint blueprint;

  /** Number of whole units of {@link #gameItem} requested on this line. */
  @Column(nullable = false)
  private Integer amount;

  /** Number of whole units already delivered via item handovers; starts at zero. */
  @Column(name = "delivered_amount", nullable = false)
  @Builder.Default
  private Integer deliveredAmount = 0;

  /**
   * Number of whole units already manufactured (production booked, consuming the order's linked
   * stock); starts at zero. Bounded by {@code deliveredAmount <= manufacturedAmount <= amount} — a
   * unit must be manufactured before it can be delivered.
   */
  @Column(name = "manufactured_amount", nullable = false)
  @Builder.Default
  private Integer manufacturedAmount = 0;

  /**
   * The parent line this one was adopted from when accepting a blueprint sub-assembly suggestion,
   * or {@code null} for a directly-added line. Informational provenance only; cleared (set to null)
   * rather than cascaded when the parent line is removed.
   */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_item_id")
  private JobOrderItem parentItem;

  /** Snapshotted material requirements derived from {@link #blueprint} at creation time. */
  @OneToMany(
      mappedBy = "jobOrderItem",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private Set<JobOrderItemMaterial> materials = new HashSet<>();

  /**
   * Adds a derived material requirement and keeps the bidirectional back-reference in sync.
   *
   * @param material the child requirement row to attach; mutated so its {@code jobOrderItem}
   *     back-link points at this line.
   */
  public void addMaterial(JobOrderItemMaterial material) {
    materials.add(material);
    material.setJobOrderItem(this);
  }
}
