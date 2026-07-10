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

import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link JobOrderItemHandover}. Item handovers are created during
 * fulfilment and read back for the order detail view and the PDF delivery note; no custom queries
 * are needed beyond the inherited CRUD.
 */
@Repository
public interface JobOrderItemHandoverRepository extends JpaRepository<JobOrderItemHandover, UUID> {

  /**
   * {@code true} iff at least one item handover exists for the given job order. Backs the
   * whole-order delivery freeze of the requester-edit gate (REQ-ORDERS-023): a requesting owner may
   * only edit an order that has not yet had any delivery recorded.
   *
   * @param jobOrderId the owning job order id; never {@code null}.
   * @return {@code true} iff any {@link JobOrderItemHandover} references the order.
   */
  boolean existsByJobOrderId(UUID jobOrderId);
}
