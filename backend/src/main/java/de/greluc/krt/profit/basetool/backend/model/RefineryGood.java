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

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Refinery Good JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class RefineryGood extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "input_material_id", nullable = false)
  @ToString.Exclude
  private Material inputMaterial;

  @Column(nullable = false)
  @Min(1)
  private Integer inputQuantity;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "output_material_id", nullable = false)
  @ToString.Exclude
  private Material outputMaterial;

  @Column(nullable = false)
  @Min(1)
  private Integer outputQuantity;

  @Column(nullable = false)
  @Min(0)
  @Max(1000)
  private Integer quality;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "refinery_order_id", nullable = false)
  @JsonIgnore
  @ToString.Exclude
  private RefineryOrder refineryOrder;
}
