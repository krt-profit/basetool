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
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A blueprint product unlocked by default on every Star Citizen account and therefore granted to
 * every user as a {@link PersonalBlueprint} (REQ-INV-016).
 *
 * <p>The admin-curated source of the default set (REQ-INV-017). Identity is the unique normalized
 * {@link #productKey}, which makes adding a default idempotent; {@link #outputItem} is
 * informational only.
 */
@Entity
@Table(
    name = "default_blueprint",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_default_blueprint_product_key",
            columnNames = {"product_key"}))
@Getter
@Setter
@ToString(exclude = "outputItem")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DefaultBlueprint extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * Normalized product identity (lowercased, collapsed whitespace, normalized punctuation of the SC
   * Wiki output name). Matches {@link PersonalBlueprint#getProductKey()}; unique across the table.
   */
  @Column(name = "product_key", nullable = false, length = 255)
  private String productKey;

  /** Display spelling of the default product captured when the entry was added. */
  @Column(name = "product_name", nullable = false, length = 255)
  private String productName;

  /**
   * Optional link to the resolved produced item. {@code null} when the product is not (yet) present
   * in {@code game_item}; informational only, never the ownership identity.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "output_item_id")
  private GameItem outputItem;

  /**
   * Optional representative SC Wiki recipe key captured when the default was resolved against the
   * catalog. Audit aid only; resolution dereferences {@link #productKey}.
   */
  @Column(name = "scwiki_key", length = 255)
  private String scwikiKey;

  /**
   * Identifier of the entry creator: {@code "system"} for the initial seed, the admin's Keycloak
   * {@code sub} for entries added through the admin surface.
   */
  @Column(name = "created_by", length = 64)
  private String createdBy;
}
