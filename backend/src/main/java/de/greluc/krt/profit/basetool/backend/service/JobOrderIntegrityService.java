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
 * Read-only integrity sweep over the ordered-item lines of {@code ITEM} job orders
 * (REQ-ORDERS-033), mirroring {@link BankLedgerIntegrityService}'s shape: {@link #verify()}
 * collects the violations, logs each at {@code ERROR}, and hands the caller a report whose sizes
 * feed the integrity gauges.
 *
 * <p>The invariant it guards is the one the write path can only enforce <em>at write time</em>: an
 * ordered-item line's blueprint must produce that line's game item. {@code
 * de.greluc.krt.profit.basetool.backend.service.scwiki.ScWikiBlueprintSyncService} re-resolves
 * every blueprint's output item from the Wiki feed on each run, so an upstream correction can
 * re-point a blueprint at a different item long after the order was placed. The line keeps its old
 * {@code blueprint_id} and its snapshotted materials then mirror a foreign recipe — with no error,
 * no audit event and, before this sweep, no signal at all. Detection is deliberately non-mutating:
 * silently re-pointing a line at another recipe would change what people have to supply, so the fix
 * stays a human decision (re-saving the order re-derives the line).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderIntegrityService {

  private final JobOrderItemRepository jobOrderItemRepository;

  /**
   * Runs the sweep: queries the drifted ordered-item lines and logs one {@code ERROR} per finding
   * with the order's display id, the ordered item, and what its blueprint produces now.
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
