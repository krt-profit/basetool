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

import de.greluc.krt.profit.basetool.backend.model.Role;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Role. */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
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
   * The whole role catalogue with each role's {@code permissions} already loaded.
   *
   * <p>The roles outlive the reading transaction and their permissions are iterated on the
   * authentication path, where a lazy load would fail on every login. {@code DISTINCT} because the
   * join multiplies each role by its permission rows.
   *
   * @return every role, with its permission set initialised
   */
  @Query("SELECT DISTINCT r FROM Role r LEFT JOIN FETCH r.permissions")
  List<Role> findAllWithPermissions();

  /**
   * Returns the {@code name} of every role in the local catalogue as a scalar projection; the
   * Keycloak user sync fetches role memberships for exactly these names.
   *
   * @return the set of role names; never {@code null}, possibly empty
   */
  @Query("SELECT r.name FROM Role r")
  Set<String> findAllNames();
}
