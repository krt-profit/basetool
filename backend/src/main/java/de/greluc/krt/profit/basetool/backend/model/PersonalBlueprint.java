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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A crafting blueprint a single user (identified by their {@code app_user.id}, stored in {@link
 * #ownerUserId}) has unlocked in-game. Part of the Personal Inventory area (#327), alongside {@link
 * PersonalInventoryItem}.
 *
 * <p>Ownership is modelled <strong>per product</strong>, not per recipe: several SC Wiki blueprint
 * recipes can share one product name, and the SCMDB import only knows the product name, so a single
 * row stands for "I own the blueprint for product X". Identity is the normalized {@link
 * #productKey} (derived from the SC Wiki output name); {@link #productName} keeps the original
 * display spelling and {@link #outputItem} optionally links the resolved {@link GameItem} for later
 * cross-feature use — it is informational, not the identity.
 *
 * <p>The unique constraint on {@code (owner_user_id, product_key)} guarantees a user owns each
 * product at most once. Optimistic locking is inherited via {@link AbstractEntity#getVersion()}.
 */
@Entity
@Table(
    name = "personal_blueprint",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_personal_blueprint_owner_product",
            columnNames = {"owner_user_id", "product_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonalBlueprint extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * {@code app_user.id} of the owning user. Never expose to clients.
   *
   * <p>A plain id rather than a {@code @ManyToOne User}: the column carries a foreign key with
   * {@code ON DELETE CASCADE} (V235, REQ-DATA-008), and an association would make every row the
   * cascade removes a managed entity holding a reference to the user being deleted -- the {@code
   * TransientPropertyValueException} landmine REQ-DATA-008 documents.
   */
  @Column(name = "owner_user_id", nullable = false)
  private UUID ownerUserId;

  /**
   * Normalized product identity (lowercased, collapsed whitespace, normalized punctuation of the SC
   * Wiki output name). Unique per owner; the import and search both match on this key.
   */
  @Column(name = "product_key", nullable = false, length = 255)
  private String productKey;

  /** Original display spelling of the product at the time of save. */
  @Column(name = "product_name", nullable = false, length = 255)
  private String productName;

  /**
   * Optional link to the resolved produced item. {@code null} when the product is not (yet) present
   * in {@code game_item}; informational only, never the ownership identity.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "output_item_id")
  private GameItem outputItem;

  /** Optional in-game acquisition time; pre-filled from the export timestamp on import. */
  @Column(name = "acquired_at")
  private Instant acquiredAt;

  /** Optional free-form note the owner attaches to the entry. */
  @Column(length = 2000)
  private String note;
}
