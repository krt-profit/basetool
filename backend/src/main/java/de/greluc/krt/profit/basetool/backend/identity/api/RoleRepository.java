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

package de.greluc.krt.profit.basetool.backend.identity.api;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Role. */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
  /**
   * Locks the role before an independently versioned section write.
   *
   * @param name the role display name
   * @return the locked role, or empty
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM Role r WHERE r.name = :name")
  Optional<Role> lockByName(@Param("name") String name);

  /**
   * Derived Spring-Data query - returns entities matching {@code Name}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"permissions"})
  Optional<Role> findByName(String name);

  /**
   * Derived Spring-Data query - returns entities matching {@code NameIgnoreCase}. Eagerly fetches
   * the configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"permissions"})
  Optional<Role> findByNameIgnoreCase(String name);

  /**
   * Derived Spring-Data query - returns entities matching {@code Code}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"permissions"})
  Optional<Role> findByCode(String code);

  /**
   * Finds a role by its stable code, ignoring case.
   *
   * <p>For the write side: callers resolve through this and store the catalogue's own upper-case
   * code, since every read-time match is case-sensitive.
   *
   * @param code the role code in any casing
   * @return the role, or empty when the catalogue knows no such code
   */
  Optional<Role> findByCodeIgnoreCase(String code);

  /**
   * Loads a role under a row lock held to the end of the transaction, so that checks against its
   * holders run one at a time (the last-admin rule, REQ-SEC-088).
   *
   * @param code the stable role code
   * @return the locked role, or empty when the catalogue knows no such code
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM Role r WHERE r.code = :code")
  Optional<Role> lockByCode(@Param("code") String code);
}
