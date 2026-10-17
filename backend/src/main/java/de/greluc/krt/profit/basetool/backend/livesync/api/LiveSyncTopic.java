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
 * A live-sync room the backend publishes into: its class and the resource it names (ADR-0274).
 *
 * @param topicClass the room class
 * @param resourceId the resource named, or {@code null} for a global room
 */
public record LiveSyncTopic(@NotNull LiveSyncTopicClass topicClass, @Nullable UUID resourceId) {

  /** Separator between a topic's prefix and its resource id. */
  private static final char SEPARATOR = ':';

  /**
   * Rejects a pairing the room model does not have: a per-resource class without an id, or a global
   * class with one.
   *
   * @throws IllegalArgumentException if the class and the id disagree on arity
   */
  public LiveSyncTopic {
    if (topicClass.perResource() != (resourceId != null)) {
      throw new IllegalArgumentException(
          "Topic class " + topicClass + " does not take resource id " + resourceId);
    }
  }

  /**
   * The canonical wire form the frontend matches its own rooms against, with the id lower-cased.
   *
   * @return the prefix, followed by {@code :} and the resource id for a per-resource room
   */
  @NotNull
  public String canonical() {
    return resourceId == null ? topicClass.prefix() : topicClass.prefix() + SEPARATOR + resourceId;
  }

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
    return new LiveSyncTopic(topicClass, resourceId);
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
    return new LiveSyncTopic(topicClass, null);
  }
}
