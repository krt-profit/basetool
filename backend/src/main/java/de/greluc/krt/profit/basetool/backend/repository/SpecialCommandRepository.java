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

import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link SpecialCommand}. Hibernate's single-table discriminator narrows
 * every query to {@code kind = 'SPECIAL_COMMAND'}.
 */
@Repository
public interface SpecialCommandRepository extends LookupTableRepository<SpecialCommand, UUID> {

  /**
   * Returns the SK whose {@code shorthand} matches the given string exactly. Used by the admin UI
   * to dereference a Spezialkommando from its short identifier (e.g. resolving a chip click on
   * "ALPHA" to the underlying row). Empty when no SK carries the given shorthand.
   *
   * @param shorthand the short identifier to look up; case-sensitive, never {@code null}.
   * @return the matching SK if present, empty otherwise.
   */
  Optional<SpecialCommand> findByShorthand(String shorthand);

  /**
   * Returns every active SK in an arbitrary order, intended for dropdown population (small result
   * sets, no pagination concern). Soft-deleted SKs ({@code active = false}) are excluded.
   *
   * @return list of active SKs; never {@code null}, possibly empty.
   */
  List<SpecialCommand> findAllByActiveTrue();

  /**
   * Paged version of {@link #findAllByActiveTrue()} for the admin list view, which may grow large
   * enough to warrant pagination. The {@link Pageable} carries the sort instructions (defaulting to
   * {@code name ASC} when the controller omits a sort).
   *
   * @param pageable the page request (size, offset, sort); never {@code null}.
   * @return page of active SKs in the requested order.
   */
  Page<SpecialCommand> findAllByActiveTrue(Pageable pageable);
}
