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

import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Reconciles prepared ITEM lines while retaining booked progress and explicit parent provenance.
 */
@Service
@RequiredArgsConstructor
public class JobOrderItemReconciliation {
  private final JobOrderItemService items;

  /**
   * Revalidates the original derivation and applies all item lines in one bounded reference batch.
   *
   * @param order the managed owning order
   * @param prepared the exact immutable requirements selected before material and stock locks
   * @throws BadRequestException when an edit would discard or under-run booked production
   */
  public void apply(@NotNull JobOrder order, @NotNull JobOrderItemPlan prepared) {
    items.validateItemPlan(prepared);
    Map<UUID, JobOrderItem> existingById = new HashMap<>();
    for (JobOrderItem existing : order.getItems()) {
      if (existing.getId() != null) {
        existingById.put(existing.getId(), existing);
      }
    }
    Map<Integer, JobOrderItem> byClientId = new HashMap<>();
    List<JobOrderItem> resolved = new ArrayList<>();
    Set<UUID> keptIds = new LinkedHashSet<>();
    for (JobOrderItemPlan.Line line : prepared.lines()) {
      JobOrderItem match =
          line.payload().id() == null ? null : existingById.get(line.payload().id());
      if (match != null) {
        if (!keptIds.add(match.getId())) {
          throw new BadRequestException(
              "Item line " + match.getId() + " appears more than once in the update payload.");
        }
        assertLineEditable(match, line);
      } else {
        match = JobOrderItem.builder().build();
      }
      resolved.add(match);
      if (line.payload().clientLineId() != null) {
        byClientId.put(line.payload().clientLineId(), match);
      }
    }
    List<JobOrderItem> removed =
        order.getItems().stream()
            .filter(item -> item.getId() != null && !keptIds.contains(item.getId()))
            .toList();
    removed.forEach(line -> assertLineRemovable(line, order.getId()));
    items.applyItemLines(resolved, prepared);
    for (JobOrderItem item : resolved) {
      if (item.getId() == null) {
        order.addItem(item);
      }
      item.setParentItem(null);
    }
    for (int index = 0; index < prepared.lines().size(); index++) {
      Integer parentClientId = prepared.lines().get(index).payload().parentClientLineId();
      if (parentClientId != null) {
        JobOrderItem parent = byClientId.get(parentClientId);
        if (parent != null && !parent.equals(resolved.get(index))) {
          resolved.get(index).setParentItem(parent);
        }
      }
    }
    removed.forEach(order.getItems()::remove);
  }

  /**
   * Guards an in-place line update against booked production (REQ-ORDERS-032): once {@code
   * manufacturedAmount > 0} the ordered item is frozen and the amount may not drop below it; the
   * blueprint may still change.
   *
   * @param existing the managed line being updated
   * @param prepared the canonical item and immutable payload with its new state
   * @throws BadRequestException when the update would orphan or under-run booked production
   */
  private static void assertLineEditable(
      @NotNull JobOrderItem existing, JobOrderItemPlan.Line prepared) {
    CreateJobOrderItemLineDto line = prepared.payload();
    int manufactured =
        existing.getManufacturedAmount() == null ? 0 : existing.getManufacturedAmount();
    if (manufactured <= 0) {
      return;
    }
    if (existing.getGameItem() != null
        && !existing.getGameItem().getId().equals(prepared.gameItemId())) {
      throw new BadRequestException(
          "Item line "
              + existing.getId()
              + " already has "
              + manufactured
              + " manufactured unit(s); its ordered item can no longer be changed.");
    }
    if (line.amount() != null && line.amount() < manufactured) {
      throw new BadRequestException(
          "Item line "
              + existing.getId()
              + " already has "
              + manufactured
              + " manufactured unit(s); the amount cannot be lowered below that.");
    }
  }

  /**
   * Refuses to remove a line that carries booked production or a recorded delivery
   * (REQ-ORDERS-032).
   *
   * @param gone the line the payload dropped
   * @param orderId the owning order id, for the error message
   * @throws BadRequestException when the line carries production or delivery
   */
  private static void assertLineRemovable(@NotNull JobOrderItem gone, UUID orderId) {
    int manufactured = gone.getManufacturedAmount() == null ? 0 : gone.getManufacturedAmount();
    int delivered = gone.getDeliveredAmount() == null ? 0 : gone.getDeliveredAmount();
    if (manufactured > 0 || delivered > 0) {
      throw new BadRequestException(
          "Item line "
              + gone.getId()
              + " of order "
              + orderId
              + " already has booked production and cannot be removed.");
    }
  }
}
