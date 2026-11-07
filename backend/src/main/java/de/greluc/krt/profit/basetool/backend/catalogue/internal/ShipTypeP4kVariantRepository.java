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

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for the P4K ship variant groups (REQ-P4K-032). */
@Repository
public interface ShipTypeP4kVariantRepository extends JpaRepository<ShipTypeP4kVariant, UUID> {

  /**
   * Deletes the variant rows of other GUIDs that hold a class name, so a renamed variant can take
   * it; a bulk delete, so it runs before the following insert.
   *
   * @param className the class name, compared ignoring case
   * @param variantUuid the GUID that keeps the class name
   * @return the rows deleted
   */
  @Modifying
  @Query(
      """
      DELETE FROM ShipTypeP4kVariant v
      WHERE lower(v.className) = lower(:className) AND v.variantUuid <> :variantUuid
      """)
  int deleteClassNameHeldByOther(
      @Param("className") String className, @Param("variantUuid") UUID variantUuid);

  /**
   * Deletes every variant row whose GUID a complete ships kind no longer lists as a variant.
   *
   * @param variantUuids the variant GUIDs of the catalogue; never empty
   * @return the rows deleted
   */
  @Modifying
  @Query("DELETE FROM ShipTypeP4kVariant v WHERE v.variantUuid NOT IN :variantUuids")
  int deleteAllExcept(@Param("variantUuids") Collection<UUID> variantUuids);
}
