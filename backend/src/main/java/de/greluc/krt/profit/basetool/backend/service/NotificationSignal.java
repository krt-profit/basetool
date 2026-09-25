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

import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a real-time push tells its recipient: the notification type, entity and render parameters
 * (REQ-APP-UI-007).
 *
 * <p>A signal is per notification type, not per event. {@link #refreshOnly()} carries no type and
 * is sent when an event only cleared stale items (REQ-NOTIF-018).
 *
 * @param type what kind of notification was raised, or {@code null} for a refresh-only push
 * @param entityType the originating aggregate's type tag, e.g. {@code JOB_ORDER}; {@code null} on a
 *     refresh-only push
 * @param entityId the originating aggregate's id, {@code null} on a refresh-only push
 * @param params the i18n render parameters; never {@code null}, empty on a refresh-only push
 */
public record NotificationSignal(
    @Nullable NotificationType type,
    @Nullable String entityType,
    @Nullable UUID entityId,
    @NotNull Map<String, String> params) {

  /**
   * The signal for a recipient whose inbox changed without a new message arriving.
   *
   * @return a signal carrying no kind and no entity
   */
  @NotNull
  public static NotificationSignal refreshOnly() {
    return new NotificationSignal(null, null, null, Map.of());
  }

  /**
   * Whether this signal describes a message, as opposed to a bare "your inbox changed".
   *
   * @return {@code true} when a kind is present
   */
  public boolean describesNotification() {
    return type != null;
  }
}
