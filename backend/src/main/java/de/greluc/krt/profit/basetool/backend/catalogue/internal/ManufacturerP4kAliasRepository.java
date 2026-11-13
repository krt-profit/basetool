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

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for reviewed P4K manufacturer aliases. */
public interface ManufacturerP4kAliasRepository extends JpaRepository<ManufacturerP4kAlias, UUID> {

  /**
   * Loads each alias with its manufacturer in one read.
   *
   * @return the reviewed P4K identities and the brands they reference
   */
  @Query("SELECT a FROM ManufacturerP4kAlias a JOIN FETCH a.manufacturer")
  List<ManufacturerP4kAlias> findLinkedAliases();

  /**
   * Loads the P4K aliases that follow a manufacturer in a merge.
   *
   * @param manufacturerId the manufacturer that owns the aliases
   * @return its P4K aliases
   */
  @Query("SELECT a FROM ManufacturerP4kAlias a WHERE a.manufacturer.id = :manufacturerId")
  List<ManufacturerP4kAlias> findOfManufacturer(@Param("manufacturerId") UUID manufacturerId);
}
