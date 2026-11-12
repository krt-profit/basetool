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
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Collects the original notice policies of one logical cause in the caller's transaction. */
public interface NotificationCauseCollector {

  /**
   * Registers the cause before other before-commit synchronizations append notices.
   *
   * @param causeId the stable identity of the committed business outcome
   */
  void prepare(@NotNull UUID causeId);

  /**
   * Adds original events in delivery priority order.
   *
   * @param causeId the stable identity of the committed business outcome
   * @param events the original events
   */
  void append(@NotNull UUID causeId, @NotNull List<NotificationEvent> events);

  /**
   * Adds an owning outcome whose resolved audience cannot receive its fallback notice.
   *
   * @param causeId the stable identity of the committed business outcome
   * @param event the original owning outcome
   */
  void appendPreferred(@NotNull UUID causeId, @NotNull NotificationEvent event);
}
