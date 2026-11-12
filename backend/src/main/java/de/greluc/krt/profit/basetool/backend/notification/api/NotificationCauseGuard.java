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

import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import org.jetbrains.annotations.NotNull;

/** Lets an event's owning domain retire stale action prompts before grouped delivery. */
@ObserverSpi
public interface NotificationCauseGuard {

  /**
   * Checks the current business state before a cause creates or removes a prompt.
   *
   * @param event one original event in the committed cause
   * @return whether this event's notice and cleanup are current
   */
  boolean isCurrent(@NotNull NotificationEvent event);
}
