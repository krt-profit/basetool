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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The Spezialkommando claim view of a job order, as the order's stock projection embeds it (plan
 * §5.3); implemented by the job-order module.
 */
public interface ClaimBucketSource {

  /**
   * Projects the per-bucket claim view of one order.
   *
   * @param order the managed order whose buckets and claims to project
   * @return the per-bucket claim view, never {@code null}
   */
  @NotNull
  List<ClaimBucketDto> getClaimBucketsForOrder(@NotNull JobOrder order);

  /**
   * Projects the claim views of many orders, loading their claims in one query (REQ-DATA-003).
   *
   * @param orders the orders whose claim views to project; empty yields an empty map
   * @return order id to its per-bucket claim view, never {@code null}
   */
  @NotNull
  Map<UUID, List<ClaimBucketDto>> getClaimBucketsForOrders(@NotNull Collection<JobOrder> orders);
}
