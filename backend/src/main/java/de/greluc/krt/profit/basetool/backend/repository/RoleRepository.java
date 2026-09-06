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
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
   * <p>The codes are canonical and upper-case, and every match at read time is the case-sensitive
   * {@code r.code = :roleCode}. This exists for the write side: a client that sends {@code admin}
   * names a role that exists, and refusing it (or, worse, storing it) produces a rule that either
   * cannot be saved or matches nobody. Callers resolve through this and then store the catalogue's
   * own casing, so the two sides cannot drift.
   *
   * @param code the role code in any casing
   * @return the role, or empty when the catalogue knows no such code
   */
  Optional<Role> findByCodeIgnoreCase(String code);

  /**
   * Lists every entity. Overridden here to attach an {@code @EntityGraph}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @Override
  @NotNull
  @EntityGraph(attributePaths = {"permissions"})
  Page<Role> findAll(@NotNull Pageable pageable);

  /**
   * Returns the display {@code name} of every role in the local catalog. Backs the Keycloak user
   * sync's role-indexed membership fetch: {@code KeycloakService.fetchUsers} queries {@code GET
   * /roles/{name}/users} for exactly these names (the realm role name equals the local role name,
   * the key {@code UserService.mapRoles} joins on), so ubiquitous default/technical realm roles are
   * never walked. A lightweight scalar projection — no entity or {@code permissions} graph is
   * loaded.
   *
   * @return the set of role names; never {@code null}, possibly empty.
   */
  @Query("SELECT r.name FROM Role r")
  Set<String> findAllNames();
}
