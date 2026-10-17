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

package de.greluc.krt.profit.basetool.backend.livesync.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A live-sync room the backend publishes into: its class, the resource it names, and the canonical
 * wire string the frontend matches its own rooms against (ADR-0274).
 *
 * @param topicClass the room class
 * @param resourceId the resource named, or {@code null} for a global room
 * @param canonical the canonical wire form, with the id lower-cased
 */
public record LiveSyncTopic(
    @NotNull LiveSyncTopicClass topicClass, @Nullable UUID resourceId, @NotNull String canonical) {

  /** Separator between a topic's prefix and its resource id. */
  private static final char SEPARATOR = ':';

  /**
   * The room of one resource, e.g. one member's own hangar or one org unit's overview.
   *
   * @param topicClass the room class; must be per-resource
   * @param resourceId the resource the room belongs to
   * @return the room
   * @throws IllegalArgumentException if the class names no resource
   */
  @NotNull
  public static LiveSyncTopic of(@NotNull LiveSyncTopicClass topicClass, @NotNull UUID resourceId) {
    if (!topicClass.perResource()) {
      throw new IllegalArgumentException("Topic class " + topicClass + " names no resource");
    }
    return new LiveSyncTopic(topicClass, resourceId, topicClass.prefix() + SEPARATOR + resourceId);
  }

  /**
   * The one global room of a class, which every viewer of that surface listens on.
   *
   * @param topicClass the room class; must not be per-resource
   * @return the room
   * @throws IllegalArgumentException if the class names a resource
   */
  @NotNull
  public static LiveSyncTopic global(@NotNull LiveSyncTopicClass topicClass) {
    if (topicClass.perResource()) {
      throw new IllegalArgumentException("Topic class " + topicClass + " names a resource");
    }
    return new LiveSyncTopic(topicClass, null, topicClass.prefix());
  }
}
