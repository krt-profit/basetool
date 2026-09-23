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

import de.greluc.krt.profit.basetool.backend.repository.PairKeyRef;
import de.greluc.krt.profit.basetool.backend.repository.UexKeyRef;
import java.util.UUID;

/** Test factories for the id-projection rows the UEX syncs preload (BE-PERF-09). */
final class UexRefs {

  private UexRefs() {}

  /**
   * A (UEX id, local id) projection row.
   *
   * @param uexId the UEX id
   * @param id the local id
   * @return the row
   */
  static UexKeyRef ref(Integer uexId, UUID id) {
    return new UexKeyRef() {
      @Override
      public Integer getUexId() {
        return uexId;
      }

      @Override
      public UUID getId() {
        return id;
      }
    };
  }

  /**
   * A (parent, terminal, row id) projection row of a matrix table.
   *
   * @param parentId the catalogue parent id
   * @param terminalId the terminal id
   * @param id the row id
   * @return the row
   */
  static PairKeyRef pair(UUID parentId, UUID terminalId, UUID id) {
    return new PairKeyRef() {
      @Override
      public UUID getParentId() {
        return parentId;
      }

      @Override
      public UUID getTerminalId() {
        return terminalId;
      }

      @Override
      public UUID getId() {
        return id;
      }
    };
  }
}
