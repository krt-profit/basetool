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

package de.greluc.krt.profit.basetool.backend.notification.api;

import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captures an entity's exact superseded notices for after-commit cleanup. */
@Service
@RequiredArgsConstructor
public class NotificationPromptCleanup {
  private final NotificationRepository notifications;

  /**
   * Reads notice identities while the owning entity is locked by the caller.
   *
   * @param types the obsolete notice types
   * @param entityType the owning entity tag
   * @param entityId the owning entity id
   * @return the matching notice identities
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public @NotNull @Unmodifiable Set<UUID> capture(
      @NotNull Set<NotificationType> types, @NotNull String entityType, @NotNull UUID entityId) {
    return Set.copyOf(notifications.findIdsByTypeInAndEntity(types, entityType, entityId));
  }
}
