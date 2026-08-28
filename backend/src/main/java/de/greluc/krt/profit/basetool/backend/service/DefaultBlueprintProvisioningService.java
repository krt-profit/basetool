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

import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialises the admin-curated default blueprints (REQ-INV-016) into {@code personal_blueprint}
 * rows for users who do not yet own them.
 *
 * <p>Both grants are single set-based {@code INSERT … SELECT … ON CONFLICT (owner_user_id,
 * product_key) DO NOTHING} statements, so they are idempotent and never produce a duplicate —
 * running them repeatedly (first-login event, admin add, periodic sweep, startup backfill) only
 * ever inserts the still-missing rows. Because each is one bulk insert that touches no managed
 * entity, the CLAUDE.md detach-clear trap does not apply.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultBlueprintProvisioningService {

  private final PersonalBlueprintRepository personalBlueprintRepository;

  /**
   * Grants every default blueprint the given user does not yet own. Idempotent.
   *
   * @param ownerUserId {@code app_user.id} of the user to provision
   * @return the number of newly inserted owned-blueprint rows
   */
  @Transactional
  public int grantDefaultsToUser(@NotNull UUID ownerUserId) {
    int granted = personalBlueprintRepository.grantDefaultBlueprintsToUser(ownerUserId);
    if (granted > 0) {
      log.info("Granted {} default blueprint(s) to ownerUserId={}", granted, ownerUserId);
    }
    return granted;
  }

  /**
   * Grants every default blueprint to every active ({@code in_keycloak = true}) user who does not
   * yet own it, in one statement. Idempotent — backs the startup backfill, the periodic
   * provisioning sweep, and the "grant to everyone" step after an admin adds a new default.
   *
   * @return the number of newly inserted owned-blueprint rows across all users
   */
  @Transactional
  public int grantDefaultsToAllUsers() {
    int granted = personalBlueprintRepository.grantDefaultBlueprintsToAllUsers();
    if (granted > 0) {
      log.info("Granted {} default blueprint row(s) across all users", granted);
    }
    return granted;
  }
}
