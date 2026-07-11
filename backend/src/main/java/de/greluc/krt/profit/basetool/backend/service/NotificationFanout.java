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
 * Cross-replica fan-out seam for the real-time notification push (ADR-0094).
 *
 * <p>{@link NotificationEventListener} pushes through this seam instead of calling {@link
 * NotificationStreamService#publish(Collection)} directly. The default binding ({@code
 * LocalNotificationFanout}) delivers only to this instance's SSE emitters — byte-for-byte the
 * previous behaviour. The Redis binding ({@code RedisNotificationFanout}) delivers locally first,
 * then publishes the recipient subs on a Redis channel so every backend replica delivers to its own
 * emitters; because local delivery happens first, a Redis outage degrades to exactly the
 * single-instance behaviour and the frontend polling fallback (REQ-NOTIF-006) remains the
 * correctness guarantee.
 */
public interface NotificationFanout {

  /**
   * Pushes a real-time notification signal to the given recipients across all backend replicas.
   * Best-effort: an implementation must not throw to the caller (the originating transaction has
   * already committed).
   *
   * @param recipientSubs the Keycloak subjects of the users to notify
   */
  void publish(@NotNull Collection<UUID> recipientSubs);
}
