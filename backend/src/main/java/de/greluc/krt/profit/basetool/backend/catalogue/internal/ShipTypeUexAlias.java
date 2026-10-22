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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Ties one UEX vehicle id to the game class name of the ship type it describes, for a vehicle whose
 * UEX record carries neither the in-game UUID nor the in-game name (REQ-DATA-026, ADR-0307).
 *
 * <p>The UEX vehicle sync, the SC Wiki vehicle sync and the P4K import consult it through the
 * {@code ShipTypeRepository} alias queries before they create a ship type, so both halves of such a
 * vehicle land on one row. Curated by migration; no version column, as no request writes it.
 */
@Entity
@Table(name = "ship_type_uex_alias")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ShipTypeUexAlias {

  /** UEX integer vehicle id (from {@code /vehicles[].id}); the natural primary key. */
  @Id
  @Column(name = "uex_vehicle_id", nullable = false)
  private Integer uexVehicleId;

  /** Game class name of the ship type, compared case-insensitively; unique ignoring case. */
  @Column(name = "class_name", nullable = false)
  private String className;
}
