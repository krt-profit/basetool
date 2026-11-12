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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Records the owning sale or discard audit payload after its physical stock write. */
@Service
@RequiredArgsConstructor
public class InventoryCheckoutAudits {
  private final AuditRecorder auditRecorder;

  /**
   * Records the audit event of a {@code DISCARD} or {@code SELL} book-out; a sale carries terminal,
   * amount and finance entries, a discard the consumed and remaining amounts.
   *
   * @param type the resolved checkout type (never {@code TRANSFER} here)
   * @param sourceId the source row id (snapshotted before a possible delete)
   * @param sourceLabel the source row's {@code material @ location} label snapshot
   * @param materialName the material name snapshot
   * @param ownerId the source row owner's id
   * @param dto the book-out request (read for terminal/sell amount)
   * @param remaining the post-decrement amount (0 when the row was depleted)
   * @param financeEntryIds the finance entry ids created for a sale (empty otherwise)
   */
  void recordBookOutTail(
      CheckoutType type,
      UUID sourceId,
      String sourceLabel,
      String materialName,
      UUID ownerId,
      InventoryItemBookOutDto dto,
      double remaining,
      List<UUID> financeEntryIds) {
    boolean rowDepleted = remaining <= LotRows.DEPLETED_BELOW;
    if (type == CheckoutType.SELL) {
      auditRecorder.record(
          AuditEventType.INVENTORY_ITEM_SOLD,
          sourceId,
          sourceLabel,
          ownerId,
          AuditDetails.of("material", materialName)
              .with("amount", dto.amount())
              .with("terminalLength", dto.terminal() == null ? 0 : dto.terminal().trim().length())
              .with("sellAmount", dto.sellAmount())
              .with(
                  "financeEntries",
                  financeEntryIds.stream()
                      .filter(Objects::nonNull)
                      .map(UUID::toString)
                      .reduce((a, b) -> a + "," + b)
                      .orElse("-"))
              .with("depleted", rowDepleted));
    } else {
      auditRecorder.record(
          AuditEventType.INVENTORY_ITEM_CONSUMED,
          sourceId,
          sourceLabel,
          ownerId,
          AuditDetails.of("type", type)
              .with("material", materialName)
              .with("amount", dto.amount())
              .with("remaining", remaining)
              .with("depleted", rowDepleted));
    }
  }
}
