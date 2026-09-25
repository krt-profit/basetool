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

import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Fan-out of the real-time notification push across backend replicas (ADR-0094).
 *
 * <p>{@code LocalNotificationFanout} delivers to this instance only; {@code
 * RedisNotificationFanout} delivers locally first, then via Redis to the other replicas.
 */
public interface NotificationFanout {

  /**
   * Pushes a notification signal to the given recipients across all backend replicas; must not
   * throw.
   *
   * @param recipientUserIds the Keycloak subjects of the users to notify
   * @param signal what those recipients are being told, or {@link NotificationSignal#refreshOnly()}
   *     when their inbox changed without a new message
   */
  void publish(@NotNull Collection<UUID> recipientUserIds, @NotNull NotificationSignal signal);
}
