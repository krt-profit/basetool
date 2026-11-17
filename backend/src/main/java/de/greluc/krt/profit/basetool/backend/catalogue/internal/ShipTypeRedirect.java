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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the id of a ship type merged into another as an alias of the one that stays; rows are
 * written by {@link ShipTypeRedirectRepository#alias}, the entity serves the queries.
 */
@Entity
@Table(name = "ship_type_redirect")
@Getter
@NoArgsConstructor
public class ShipTypeRedirect extends AbstractEntity<UUID> {

  /** The id of the merged ship type. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  private UUID id;

  /** The ship type that stays; a redirect always points at a live row. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "canonical_id", nullable = false)
  private ShipType canonicalShipType;

  /** Both ship type rows as they were before the merge, as JSON. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "retired_snapshot", nullable = false, columnDefinition = "jsonb")
  private String retiredSnapshot;

  /**
   * The UEX vehicle id the merged ship type held and the one that stays did not take, so the UEX
   * sync knows that listing as a second listing of the ship (REQ-DATA-043).
   */
  @Column(name = "uex_vehicle_id")
  @Nullable
  private Integer uexVehicleId;
}
