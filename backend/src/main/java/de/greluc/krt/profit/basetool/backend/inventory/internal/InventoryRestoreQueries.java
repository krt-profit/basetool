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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityEvidence;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads immutable mission identities and stored physical quantity for named stock restoration. */
@NoRepositoryBean
interface InventoryRestoreQueries extends Repository<InventoryItem, UUID> {

  /** One current mission allocation attached to a named physical row. */
  interface MissionSlice {
    /**
     * Returns the physical row identity.
     *
     * @return the allocated row
     */
    UUID getRowId();

    /**
     * Returns the actual mission identity.
     *
     * @return the allocation target
     */
    UUID getMissionId();

    /**
     * Returns the persisted mission allocation amount, including zero.
     *
     * @return the current slice quantity
     */
    Double getAmount();
  }

  /** The stored physical quantity composition of one existing row. */
  interface Quantity {
    /**
     * Returns the existing physical identity.
     *
     * @return the stock row
     */
    UUID getRowId();

    /**
     * Returns the stored intervals without normalizing or dirtying a managed entity.
     *
     * @return the stored composition, which may be empty for a legacy row
     */
    List<StockQuantityEvidence> getQuantityProvenance();
  }

  /**
   * Reads the actual mission slices of every named existing physical row in one batch.
   *
   * @param rowIds the nonempty row selection
   * @return current allocation identities and quantities
   */
  @Query(
      """
      SELECT a.inventoryItem.id AS rowId, a.missionId AS missionId, a.amount AS amount
      FROM InventoryMissionAllocation a WHERE a.inventoryItem.id IN :rowIds
      ORDER BY a.inventoryItem.id, a.missionId
      """)
  List<MissionSlice> findRestoreMissionSlices(@Param("rowIds") Collection<UUID> rowIds);

  /**
   * Reads the exact stored physical composition of every named existing row in one batch.
   *
   * @param rowIds the nonempty row selection
   * @return stored interval values independent of the managed persistence context
   */
  @Query(
      """
      SELECT i.id AS rowId, i.quantityProvenance AS quantityProvenance
      FROM InventoryItem i WHERE i.id IN :rowIds ORDER BY i.id
      """)
  List<Quantity> findRestoreQuantities(@Param("rowIds") Collection<UUID> rowIds);

  /**
   * Locates overlapping explicit proof and the value-only fallback of an actual current empty row.
   *
   * @param parts exact complete evidence parts encoded as a JSON array
   * @return deterministic current row identities without granting mutation authority
   */
  @Query(
      value =
          """
          SELECT DISTINCT i.id
          FROM inventory_item i
          CROSS JOIN jsonb_array_elements(CAST(:parts AS jsonb)) wanted
          WHERE (
            (
              ((jsonb_exists(wanted, 'origin') AND i.quantity_provenance @>
                jsonb_build_array(jsonb_build_object('origin', wanted->>'origin')))
               OR (jsonb_exists(wanted, 'journalReference') AND i.quantity_provenance @>
                jsonb_build_array(jsonb_build_object('journalReference', wanted->>'journalReference'))))
              AND EXISTS (
                SELECT 1 FROM jsonb_array_elements(i.quantity_provenance) actual
                WHERE ((jsonb_exists(wanted, 'origin') AND actual->>'origin' = wanted->>'origin')
                    OR (jsonb_exists(wanted, 'journalReference') AND
                        actual->>'journalReference' = wanted->>'journalReference'))
                  AND (actual->>'fromQuanta')::bigint < (wanted->>'toQuanta')::bigint
                  AND (wanted->>'fromQuanta')::bigint < (actual->>'toQuanta')::bigint
              )
            )
            OR (i.quantity_provenance = '[]'::jsonb AND jsonb_exists(wanted, 'origin')
                AND CAST(i.id AS text) = wanted->>'origin'
                AND (wanted->>'toQuanta')::bigint > 0
                AND (wanted->>'fromQuanta')::bigint < round(CAST(i.amount AS numeric) * 1000))
          )
          ORDER BY i.id
          """,
      nativeQuery = true)
  List<UUID> findQuantityEvidenceRowIds(@Param("parts") String parts);
}
