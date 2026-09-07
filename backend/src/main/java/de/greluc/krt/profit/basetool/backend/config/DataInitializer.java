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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.Permissions;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seeds the default roles and the IRIDIUM squadron on first startup.
 *
 * <p>Roles are matched by {@code code}, not by {@code name}, so an admin renaming a role in the
 * admin UI does not trigger a silent re-create with the default permissions on the next boot. The
 * permission sets here are the baseline a fresh DB needs to bring up the security model — admins
 * extend them at runtime via the role-management screens.
 */
@Configuration
@RequiredArgsConstructor
public class DataInitializer {

  private final RoleRepository roleRepository;
  private final SquadronRepository squadronRepository;

  /**
   * Returns a {@link CommandLineRunner} that runs the role + squadron seeding exactly once at boot.
   * Each individual upsert is guarded by an existence check, so the runner is safe to invoke on a
   * non-empty database.
   *
   * @return the seeding runner Spring Boot executes after the context is ready
   */
  @Bean
  public CommandLineRunner initRoles() {
    return args -> {
      // Lookup is by `code`, not by `name`: an admin renaming a role no longer
      // triggers a silent re-create with default permissions on the next boot.
      createRoleIfNotFound(
          Roles.KRT_MEMBER,
          "KRT Member",
          Set.of(Permissions.HANGAR_READ, Permissions.HANGAR_WRITE, Permissions.MISSION_READ));
      createRoleIfNotFound(
          Roles.OFFICER,
          "Officer",
          Set.of(
              Permissions.HANGAR_READ,
              Permissions.HANGAR_WRITE,
              Permissions.MISSION_READ,
              Permissions.MISSION_WRITE,
              Permissions.MISSION_MANAGE,
              Permissions.USER_MANAGE));
      createRoleIfNotFound(
          Roles.ADMIN,
          "Admin",
          Set.of(
              Permissions.HANGAR_READ,
              Permissions.HANGAR_WRITE,
              Permissions.MISSION_READ,
              Permissions.MISSION_WRITE,
              Permissions.MISSION_MANAGE,
              Permissions.USER_MANAGE,
              Permissions.ROLE_MANAGE));
      // Kartell bank (epic #556, REQ-BANK-007): two coarse roles; the fine-grained per-account
      // capabilities are app-managed grant rows (bank_account_grant), not permission strings.
      createRoleIfNotFound(Roles.BANK_EMPLOYEE, "Bank Employee", Set.of());
      createRoleIfNotFound(Roles.BANK_MANAGEMENT, "Bank Management", Set.of());

      seedIridiumIfMissing();
    };
  }

  /**
   * Seeds the canonical IRIDIUM squadron with the fixed {@link Squadron#IRIDIUM_ID} UUID so that
   * Flyway backfills, application-level lookups and tests refer to a deterministic id
   * (MULTI_SQUADRON_PLAN.md section 3). Idempotent — no-op when a row already exists at the
   * canonical id. Flyway migration V80 seeds the same row at boot in {@code dev}/{@code prod}; this
   * fallback only matters in the test profile (Flyway disabled, Hibernate {@code ddl-auto}
   * generates the schema) where V80 never runs and DataInitializer is the only seeder.
   */
  private void seedIridiumIfMissing() {
    if (squadronRepository.existsById(Squadron.IRIDIUM_ID)) {
      return;
    }
    if (squadronRepository.findByShorthand("IRI").isPresent()) {
      // Pre-V80 install: a row with shorthand "IRI" exists at a non-canonical UUID. We do not
      // rewrite it here — V80 covers that case in dev/prod. In the test profile we simply skip
      // to keep the seeder side-effect-free.
      return;
    }
    Squadron iridium = new Squadron();
    iridium.setId(Squadron.IRIDIUM_ID);
    iridium.setName("IRIDIUM");
    iridium.setShorthand("IRI");
    iridium.setDescription("The main squadron.");
    squadronRepository.save(iridium);
  }

  private void createRoleIfNotFound(String code, String displayName, Set<String> permissions) {
    if (roleRepository.findByCode(code).isPresent()) {
      return;
    }
    Role role = new Role();
    role.setCode(code);
    role.setName(displayName);
    role.setPermissions(new HashSet<>(permissions)); // Ensure mutable
    roleRepository.save(role);
  }
}
