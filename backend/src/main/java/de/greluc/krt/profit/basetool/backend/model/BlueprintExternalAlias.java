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
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Curated mapping of an external catalogue's blueprint name onto a local product, used by the
 * personal-blueprint import when a normalized exact match fails.
 *
 * <p>Points at a product by its normalized {@link #productKey} rather than a single recipe. {@code
 * (source_system, LOWER(external_name))} is unique in the database, so a duplicate add, including a
 * case-only variant, returns HTTP 409 (REQ-INV-020).
 */
@Entity
@Table(name = "blueprint_external_alias")
@Getter
@Setter
@ToString(exclude = "outputItem")
@NoArgsConstructor
@AllArgsConstructor
public class BlueprintExternalAlias extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** External catalogue the alias belongs to (currently {@code SCMDB}). */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_system", nullable = false, length = 32)
  private BlueprintExternalAliasSource sourceSystem;

  /**
   * Blueprint product name as it appears in the external catalogue (the SCMDB export {@code
   * productName}).
   */
  @Column(name = "external_name", nullable = false, length = 255)
  private String externalName;

  /**
   * Normalized product key this external name resolves to (matches {@link
   * PersonalBlueprint#getProductKey()}).
   */
  @Column(name = "product_key", nullable = false, length = 255)
  private String productKey;

  /** Display spelling of the resolved product at the time the alias was created. */
  @Column(name = "product_name", nullable = false, length = 255)
  private String productName;

  /**
   * Optional link to the resolved produced item ({@code null} when the product is not present in
   * {@code game_item}). Audit aid only; the resolution dereferences {@link #productKey}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "output_item_id")
  private GameItem outputItem;

  /** Free-form note explaining the alias provenance (e.g. who verified the in-game match). */
  @Column(columnDefinition = "TEXT")
  private String note;

  /**
   * Identifier of the alias creator: {@code "system"} for seeded rows, the JWT {@code sub} for
   * user/admin-created rows.
   */
  @Column(name = "created_by", length = 255)
  private String createdBy;
}
