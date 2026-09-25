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
 * Seeds the default roles and the IRIDIUM squadron on startup.
 *
 * <p>Roles are matched by {@code code}, so renamed roles are not re-created.
 */
@Configuration
@RequiredArgsConstructor
public class DataInitializer {

  private final RoleRepository roleRepository;
  private final SquadronRepository squadronRepository;

  /**
   * Returns a {@link CommandLineRunner} that seeds the roles and the IRIDIUM squadron; each upsert
   * checks for existence first, so it is safe on a populated database.
   *
   * @return the seeding runner Spring Boot executes after the context is ready
   */
  @Bean
  public CommandLineRunner initRoles() {
    return args -> {
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
      createRoleIfNotFound(Roles.BANK_EMPLOYEE, "Bank Employee", Set.of());
      createRoleIfNotFound(Roles.BANK_MANAGEMENT, "Bank Management", Set.of());

      seedIridiumIfMissing();
    };
  }

  /**
   * Inserts the IRIDIUM squadron under the fixed {@link Squadron#IRIDIUM_ID} when neither that row
   * nor an {@code IRI} row exists; otherwise does nothing.
   */
  private void seedIridiumIfMissing() {
    if (squadronRepository.existsById(Squadron.IRIDIUM_ID)) {
      return;
    }
    if (squadronRepository.findByShorthand("IRI").isPresent()) {
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
    role.setPermissions(new HashSet<>(permissions));
    roleRepository.save(role);
  }
}
