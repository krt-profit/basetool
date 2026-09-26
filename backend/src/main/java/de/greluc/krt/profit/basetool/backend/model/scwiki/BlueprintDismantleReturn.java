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

package de.greluc.krt.profit.basetool.backend.model.scwiki;

import de.greluc.krt.profit.basetool.backend.model.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.model.Material;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One dismantle-return line of a {@link Blueprint}: the commodity and SCU quantity recovered when
 * its output is dismantled.
 *
 * <p>{@link #material} may be {@code null} while unresolved; {@link #wikiResourceUuid} and {@link
 * #wikiNameSnapshot} are always persisted for later re-resolution.
 */
@Entity
@Table(name = "blueprint_dismantle_return")
@Getter
@Setter
@ToString(exclude = {"blueprint", "material"})
@NoArgsConstructor
public class BlueprintDismantleReturn extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning blueprint. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blueprint_id", nullable = false)
  private Blueprint blueprint;

  /** Position within the blueprint's dismantle-return list (drives {@code @OrderBy}). */
  @Column(name = "order_index", nullable = false)
  private Integer orderIndex;

  /** Resolved commodity recovered on dismantle; {@code null} while unresolved. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id")
  private Material material;

  /** Raw Wiki resource UUID — kept for forensic re-resolution. */
  @Column(name = "wiki_resource_uuid")
  private UUID wikiResourceUuid;

  /** Wiki display name of the returned commodity at sync time. */
  @Column(name = "wiki_name_snapshot")
  private String wikiNameSnapshot;

  /** Quantity recovered, in SCU. */
  @Column(name = "quantity_scu")
  private Double quantityScu;
}
