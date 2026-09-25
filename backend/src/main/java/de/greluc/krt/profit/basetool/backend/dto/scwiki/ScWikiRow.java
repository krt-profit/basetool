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

package de.greluc.krt.profit.basetool.backend.dto.scwiki;

import java.util.UUID;

/**
 * Row identity of a paginated SC Wiki list payload, letting the {@code ScWikiClient} page walk
 * count distinct rows.
 *
 * <p>A row served twice means the pagination window shifted and another row was missed; because the
 * merged list drives the tombstone sweeps, the census counts distinct {@link #uuid()} values.
 */
public interface ScWikiRow {

  /**
   * The Wiki's stable identifier for this row, also the key of the cross-references and tombstone
   * sweeps.
   *
   * @return the row's Wiki UUID, or {@code null} when upstream served none (counted as its own row)
   */
  UUID uuid();
}
