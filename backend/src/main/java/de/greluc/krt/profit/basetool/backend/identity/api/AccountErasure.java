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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The account deletion the identity module's registration flow needs, owned by identity and
 * implemented by the GDPR orchestrator (plan §5.3).
 *
 * <p>The implementation forwards to the orchestrator's own deletion and adds no transaction rule of
 * its own: the deletion joins the caller's transaction or opens one, and its exceptions pass
 * through.
 */
public interface AccountErasure {

  /**
   * Hard-deletes a user no longer present in Keycloak, with every reference the organisation does
   * not need to keep.
   *
   * @param userId the user to delete
   * @param presence whether the Keycloak presence probe applies
   * @throws java.util.NoSuchElementException when the user id is unknown
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the user is still
   *     present in Keycloak, by the stored flag or, under {@link KeycloakPresence#ENFORCED}, a live
   *     check
   * @throws IllegalStateException when no other admin exists to receive the reassigned references
   * @throws org.springframework.web.client.RestClientException when Keycloak cannot be reached for
   *     the live check
   */
  void deleteAccount(@NotNull UUID userId, @NotNull KeycloakPresence presence);
}
