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

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Notes the row of every earmark or donation pledge a transaction inserts, updates or deletes for
 * the relocation lock's guard (REQ-PATCH-023): such a write changes no column of the row, and the
 * row's forced version increment runs no update callback of its own.
 */
@Component
@RequiredArgsConstructor
class InventorySliceRelocationListener {

  private final ObjectProvider<InventoryRelocationGuard> guard;

  /**
   * Notes the row of a slice about to be written.
   *
   * @param slice the slice; JPA hands a listener shared by several entities their common supertype
   */
  @PrePersist
  @PreUpdate
  @PreRemove
  void changing(Object slice) {
    if (slice instanceof StockRowSlice rowSlice) {
      guard.getObject().changed(rowSlice.getInventoryItem());
    }
  }
}
