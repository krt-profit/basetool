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

package de.greluc.krt.profit.basetool.backend.notification.api.events;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One logical revision's original notification events in descending delivery priority.
 *
 * @param id stable identity of this revision
 * @param events the original events, highest priority first
 */
public record NotificationCause(@NotNull UUID id, @NotNull List<NotificationEvent> events) {
  /**
   * Captures a non-null revision identity and an immutable event list.
   *
   * @param id stable identity of this revision
   * @param events the original events, highest priority first
   */
  public NotificationCause {
    Objects.requireNonNull(id);
    events = List.copyOf(events);
  }
}
