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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.Nullable;

/** Material Price JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"material_id", "terminal_id"}))
public class MaterialPrice extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "material_id", nullable = false)
  @ToString.Exclude
  private Material material;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "terminal_id", nullable = false)
  @ToString.Exclude
  private Terminal terminal;

  private BigDecimal priceBuy;
  private BigDecimal priceSell;

  private Integer scuBuy;
  private Integer scuSell;
  private Integer scuSellStock;

  /** UEX's running average of {@link #priceBuy}, in aUEC per SCU. */
  @Nullable private BigDecimal priceBuyAvg;

  /** UEX's running average of {@link #priceSell}, in aUEC per SCU. */
  @Nullable private BigDecimal priceSellAvg;

  @Nullable private Integer scuBuyAvg;
  @Nullable private Integer scuSellAvg;
  @Nullable private Integer scuSellStockAvg;

  /**
   * SCU container sizes the terminal handles for this commodity, in UEX order; {@code null} when
   * UEX lists none.
   */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "container_sizes", columnDefinition = "integer[]")
  @Nullable
  private List<Integer> containerSizes;

  private Boolean statusBuy;
  private Boolean statusSell;

  @Nullable private Instant dateModified;
}
