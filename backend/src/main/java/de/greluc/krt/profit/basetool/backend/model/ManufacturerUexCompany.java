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
 * Maps a single UEX {@code /companies} integer id to the local {@link Manufacturer} it belongs to.
 *
 * <p>UEX ships several <em>distinct</em> company records for the same real-world manufacturer — the
 * item-side record and the vehicle-side record carry different ids and frequently different names
 * (observed: id 87 {@code "Esperia"} carries the items, id 278 {@code "Esperia Incorporation"}
 * carries the ships; likewise {@code 70 "Denim Manufacture Corporation"} / {@code 287 "DMC"} and
 * {@code 62 "Covalex Shipping"} / {@code 293 "Covalex"}). Because the item sync resolves the
 * manufacturer by {@code id_company} and the vehicle sync by {@code id_company} too, but the two
 * surfaces reference <em>different</em> ids for the same brand, a single {@code manufacturer} row
 * keyed on one {@code uex_company_id} cannot serve both — it split the brand's ships from its items
 * across two rows.
 *
 * <p>This table is the fix: every UEX company id (canonical <em>and</em> duplicate) maps to the one
 * surviving {@code manufacturer} row, so both the item and vehicle syncs resolve every id-variant
 * of a brand to the same manufacturer. The canonical company (lowest {@code uex_company_id} of a
 * brand) still owns the row's display identity via {@link Manufacturer#getUexCompanyId()}; the
 * other ids live here as aliases. See {@code ADR-0023} / {@code REQ-DATA-004}.
 *
 * <p>Pure mapping entity: the natural {@link #uexCompanyId} is the primary key and there is no
 * optimistic-locking version — the sync is single-threaded per source (the {@code SyncCoordinator}
 * serialises UEX vs. SC Wiki), so concurrent writers never contend for an alias row.
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
