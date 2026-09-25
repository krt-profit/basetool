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
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps one UEX {@code /companies} id to the local {@link Manufacturer} it belongs to.
 *
 * <p>UEX ships several distinct company records per brand (e.g. separate item and vehicle ids);
 * every id, canonical and alias, maps to the single manufacturer row so both syncs resolve a brand
 * consistently (ADR-0023). The UEX id is the primary key; there is no version column, as the syncs
 * are serialised.
 */
@Entity
@Table(name = "manufacturer_uex_company")
@Getter
@Setter
@ToString(exclude = "manufacturer")
@NoArgsConstructor
@AllArgsConstructor
public class ManufacturerUexCompany {

  /** UEX integer company id (from {@code /companies[].id}); the natural primary key. */
  @Id
  @Column(name = "uex_company_id", nullable = false)
  private Integer uexCompanyId;

  /** The local manufacturer this UEX company id resolves to. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "manufacturer_id", nullable = false)
  private Manufacturer manufacturer;
}
