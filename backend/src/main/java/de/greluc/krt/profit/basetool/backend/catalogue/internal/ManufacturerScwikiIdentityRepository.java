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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for the {@link ManufacturerScwikiIdentity} rows (ADR-0361). */
@Repository
public interface ManufacturerScwikiIdentityRepository
    extends JpaRepository<ManufacturerScwikiIdentity, UUID> {

  /**
   * Resolves the manufacturer a Wiki manufacturer UUID belongs to, primary link or alias.
   *
   * @param scwikiUuid Wiki manufacturer UUID
   * @return the manufacturer, or empty when the UUID is unknown
   */
  @Query("SELECT i.manufacturer FROM ManufacturerScwikiIdentity i WHERE i.scwikiUuid = :scwikiUuid")
  Optional<Manufacturer> findManufacturerByScwikiUuid(@Param("scwikiUuid") UUID scwikiUuid);

  /**
   * Every Wiki UUID with the id of the manufacturer it belongs to, in one query, for the Wiki item
   * sync's manufacturer lookup.
   *
   * @return one pair per identity
   */
  @Query(
      "SELECT i.scwikiUuid AS scwikiUuid, i.manufacturer.id AS manufacturerId"
          + " FROM ManufacturerScwikiIdentity i")
  List<IdentityRef> findIdentityRefs();

  /**
   * Every identity of a manufacturer that has more than one, with its manufacturer, ordered by
   * manufacturer name and Wiki name.
   *
   * @return the identities of every manufacturer the Wiki lists more than once
   */
  @Query(
      "SELECT i FROM ManufacturerScwikiIdentity i JOIN FETCH i.manufacturer m"
          + " WHERE m.id IN (SELECT j.manufacturer.id FROM ManufacturerScwikiIdentity j"
          + " GROUP BY j.manufacturer.id HAVING COUNT(j) > 1)"
          + " ORDER BY LOWER(m.name), m.id, LOWER(i.wikiName), i.scwikiUuid")
  List<ManufacturerScwikiIdentity> findDuplicated();

  /**
   * Every identity of one manufacturer, ordered by Wiki name.
   *
   * @param manufacturerId the local manufacturer id
   * @return the manufacturer's Wiki identities
   */
  @Query(
      "SELECT i FROM ManufacturerScwikiIdentity i WHERE i.manufacturer.id = :manufacturerId"
          + " ORDER BY LOWER(i.wikiName), i.scwikiUuid")
  List<ManufacturerScwikiIdentity> findOfManufacturer(@Param("manufacturerId") UUID manufacturerId);

  /**
   * Deletes the identities a complete Wiki census no longer carries.
   *
   * @param census every Wiki manufacturer UUID of the census; never empty
   * @return the number of rows deleted
   */
  @Modifying
  @Query("DELETE FROM ManufacturerScwikiIdentity i WHERE i.scwikiUuid NOT IN :census")
  int deleteAbsentFrom(@Param("census") Collection<UUID> census);

  /** A Wiki UUID with the id of its manufacturer. */
  interface IdentityRef {

    /**
     * Returns the Wiki manufacturer UUID.
     *
     * @return the Wiki UUID
     */
    UUID getScwikiUuid();

    /**
     * Returns the id of the manufacturer the Wiki UUID belongs to.
     *
     * @return the manufacturer id
     */
    UUID getManufacturerId();
  }
}
