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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Owns the database coordination of material scopes and participation insertion. */
public interface MaterialPromiseScopeRepository extends Repository<JobOrder, UUID> {
  /**
   * Takes a transaction advisory lock in the common sorted material-resource protocol.
   *
   * @param key the deterministic lock key
   * @return PostgreSQL's void result
   */
  @Query(value = "SELECT pg_advisory_xact_lock(:key)", nativeQuery = true)
  Object lockResource(@Param("key") long key);

  /**
   * Reads all current and historical materials relevant to an aggregate action.
   *
   * @param orderId the order
   * @return material identities across both requirement kinds and promises
   */
  @Query(
      value =
          "SELECT material_id FROM job_order_material WHERE job_order_id = :orderId UNION SELECT"
              + " r.material_id FROM job_order_item_material r JOIN job_order_item i ON i.id ="
              + " r.job_order_item_id WHERE i.job_order_id = :orderId UNION SELECT material_id FROM"
              + " material_claim WHERE job_order_id = :orderId",
      nativeQuery = true)
  List<UUID> materialIdsOf(@Param("orderId") UUID orderId);
}
