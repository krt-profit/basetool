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

import de.greluc.krt.profit.basetool.backend.model.projection.JobOrderItemBlueprintDrift;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only integrity sweep that finds ordered-item lines of {@code ITEM} job orders whose
 * blueprint no longer produces the line's game item (REQ-ORDERS-033).
 *
 * <p>Findings are logged at {@code ERROR} and reported for the integrity gauges; nothing is
 * repaired, since re-pointing a line would change what people have to supply.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderIntegrityService {

  private final JobOrderItemRepository jobOrderItemRepository;

  /**
   * Runs the sweep and logs one {@code ERROR} per drifted line with the order's display id, the
   * ordered item and what its blueprint produces now.
   *
   * @return the report; {@link IntegrityReport#isClean()} is {@code true} when nothing drifted
   */
  @NotNull
  public IntegrityReport verify() {
    List<JobOrderItemBlueprintDrift> drift = jobOrderItemRepository.findBlueprintOutputDrift();
    for (JobOrderItemBlueprintDrift row : drift) {
      log.error(
          "Item-order line blueprint drift: order #{} orders '{}' but its blueprint {} now produces"
              + " '{}' — the line's snapshotted materials are a foreign recipe (itemId={},"
              + " orderId={}). Re-save the order to re-derive the line.",
          row.orderDisplayId(),
          row.orderedItemName(),
          row.blueprintKey(),
          row.blueprintOutputName(),
          row.itemId(),
          row.orderId());
    }
    return new IntegrityReport(drift);
  }

  /**
   * Outcome of one {@link #verify()} pass.
   *
   * @param blueprintDrift the ordered-item lines whose blueprint no longer produces the ordered
   *     item
   */
  public record IntegrityReport(@NotNull List<JobOrderItemBlueprintDrift> blueprintDrift) {

    /**
     * Whether the sweep found nothing — every ordered-item line still points at a blueprint that
     * produces it.
     *
     * @return {@code true} when there are no violations
     */
    public boolean isClean() {
      return blueprintDrift.isEmpty();
    }

    /**
     * Total number of violations across all categories, for the summary log line.
     *
     * @return the violation count
     */
    public int violationCount() {
      return blueprintDrift.size();
    }
  }
}
