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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.Department;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link Bereich}, restricted by the single-table discriminator to
 * {@code kind = 'BEREICH'} rows.
 */
@Repository
public interface BereichRepository extends LookupTableRepository<Bereich, UUID> {

  /** Derived Spring-Data query — returns the Bereich whose {@code shorthand} matches, if any. */
  Optional<Bereich> findByShorthand(String shorthand);

  /** Returns every active Bereich (soft-delete-aware list view). */
  List<Bereich> findAllByActiveTrue();

  /**
   * Returns every Bereich tagged with the given department; used to resolve the {@code CARTEL_BANK}
   * responsible holders (REQ-BANK-037).
   *
   * @param department the department to match; never {@code null}
   * @return the matching Bereiche; never {@code null}, possibly empty
   */
  List<Bereich> findByDepartment(Department department);
}
