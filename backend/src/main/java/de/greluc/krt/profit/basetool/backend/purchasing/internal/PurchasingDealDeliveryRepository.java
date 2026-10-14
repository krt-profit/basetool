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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence of the deliveries (REQ-PUR-015). */
@Repository
public interface PurchasingDealDeliveryRepository
    extends JpaRepository<PurchasingDealDelivery, UUID> {

  /**
   * Lists the deliveries of a deal, oldest first.
   *
   * @param dealId the deal
   * @return the deliveries
   */
  List<PurchasingDealDelivery> findByDealIdOrderByCreatedAtAsc(UUID dealId);

  /**
   * Lists the deliveries of several deals.
   *
   * @param dealIds the deals
   * @return the deliveries, in no particular order
   */
  List<PurchasingDealDelivery> findByDealIdIn(Collection<UUID> dealIds);
}
