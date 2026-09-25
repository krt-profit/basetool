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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.ManufacturerUexCompany;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the {@link ManufacturerUexCompany} alias mapping (UEX company id →
 * {@link Manufacturer}). The UEX item and vehicle syncs resolve a manufacturer through {@link
 * #findManufacturerByUexCompanyId(Integer)} so every id-variant of a brand (canonical + duplicate)
 * lands on the same surviving manufacturer row — see {@code ADR-0023} / {@code REQ-DATA-004}.
 */
@Repository
public interface ManufacturerUexCompanyRepository
    extends JpaRepository<ManufacturerUexCompany, Integer> {

  /**
   * Resolves the manufacturer a UEX company id maps to, covering a brand's canonical id and every
   * duplicate id UEX ships for it.
   *
   * @param uexCompanyId UEX integer company id (from {@code /companies[].id} or a row's {@code
   *     id_company})
   * @return the mapped manufacturer if the id is known, otherwise empty
   */
  @Query("SELECT a.manufacturer FROM ManufacturerUexCompany a WHERE a.uexCompanyId = :uexCompanyId")
  Optional<Manufacturer> findManufacturerByUexCompanyId(
      @Param("uexCompanyId") Integer uexCompanyId);

  /**
   * Every alias as a (UEX company id, manufacturer id) pair, in one query — the item sync resolves
   * each row's manufacturer against the resulting map instead of per row (BE-PERF-09).
   *
   * @return one row per alias
   */
  @Query("SELECT a.uexCompanyId AS uexId, a.manufacturer.id AS id FROM ManufacturerUexCompany a")
  List<UexKeyRef> findCompanyRefs();
}
