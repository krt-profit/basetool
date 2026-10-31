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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDeletionObserver;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes the minimum stock levels of a material that is deleted, recording one {@code
 * MIN_STOCK_LEVEL_REMOVED_BY_CASCADE} event per level and sending no notice (REQ-INV-068).
 */
@Component
@RequiredArgsConstructor
public class StockLevelMaterialCascade implements MaterialDeletionObserver {

  private final StockLevelRepository stockLevelRepository;
  private final AuditRecorder auditRecorder;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int beforeMaterialDelete(@NotNull UUID materialId) {
    List<StockLevel> levels = stockLevelRepository.findByMaterial(materialId);
    for (StockLevel level : levels) {
      auditRecorder.record(
          AuditEventType.MIN_STOCK_LEVEL_REMOVED_BY_CASCADE,
          level.getId(),
          level.subjectName(),
          null,
          StockLevelAuditDetails.snapshot(level));
    }
    stockLevelRepository.deleteAll(levels);
    stockLevelRepository.flush();
    return levels.size();
  }
}
