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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The member who caused an event, as the event needs them: the id to exclude from the recipients
 * and the name to render.
 *
 * @param id the member's id, or {@code null} when nobody acted (a scheduled job)
 * @param name the member's effective name, or an em dash when unknown
 */
public record ActorRef(@Nullable UUID id, @NotNull String name) {

  /** The text for a name that is not known. */
  public static final String UNKNOWN_NAME = "\u2014";

  /**
   * The actor of an event no member caused.
   *
   * @return an actor without id and with the unknown name
   */
  @NotNull
  public static ActorRef system() {
    return new ActorRef(null, UNKNOWN_NAME);
  }
}
