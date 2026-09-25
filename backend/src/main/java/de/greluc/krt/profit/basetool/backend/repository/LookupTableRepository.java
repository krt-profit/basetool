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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Shared base for lookup-table repositories with a case-insensitive-unique {@code name} column,
 * providing the existence checks that let create and rename flows return 409 before the UNIQUE
 * constraint trips.
 *
 * @param <T> the entity type, which must expose a case-insensitive-unique {@code name} column
 * @param <IdT> the entity's identifier type
 */
@NoRepositoryBean
public interface LookupTableRepository<T, IdT> extends JpaRepository<T, IdT> {

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * NameIgnoreCase}.
   *
   * @param name the proposed name; never {@code null}
   * @return {@code true} iff at least one row already carries this name (case-insensitive)
   */
  boolean existsByNameIgnoreCase(String name);

  /**
   * Checks whether another row, other than {@code id}, already carries {@code name}
   * case-insensitively.
   *
   * @param name the proposed name; never {@code null}
   * @param id the id of the row being renamed; never {@code null}
   * @return {@code true} iff at least one other row already carries this name
   */
  boolean existsByNameIgnoreCaseAndIdNot(String name, IdT id);
}
