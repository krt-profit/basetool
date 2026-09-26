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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A parsed live-sync topic: its class, the resource it names, and the canonical string used as the
 * room key everywhere (ADR-0143).
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
   * Longest topic string accepted, comfortably above {@code refinery-order:<uuid>} (50 characters).
   *
   * <p>A bound on the parse itself, so a crafted multi-megabyte query parameter is rejected before
   * anything splits or allocates on it.
   */
  public static final int MAX_LENGTH = 80;

  /**
   * Parses a wire topic string: one known prefix, optionally followed by a colon and a well-formed
   * UUID, in the arity the prefix's class declares.
   *
   * @param raw the topic as it arrived, untrimmed and untrusted
   * @return the parsed topic, or {@code null} if it names no room this backend serves
   */
  @Contract("null -> null")
  @Nullable
  public static LiveSyncTopic parse(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
      return null;
    }
    int separator = trimmed.indexOf(SEPARATOR);
    String prefix = (separator < 0) ? trimmed : trimmed.substring(0, separator);
    String idPart = (separator < 0) ? null : trimmed.substring(separator + 1);

    LiveSyncTopicClass topicClass = LiveSyncTopicClass.resolve(prefix, idPart != null);
    if (topicClass == null) {
      return null;
    }
    if (idPart == null) {
      return new LiveSyncTopic(topicClass, null, prefix);
    }
    UUID resourceId = parseUuid(idPart);
    if (resourceId == null) {
      return null;
    }
    return new LiveSyncTopic(topicClass, resourceId, prefix + SEPARATOR + resourceId);
  }

  /**
   * Parses a UUID strictly, rejecting any input whose canonical form differs from it apart from
   * case.
   *
   * @param candidate the id segment
   * @return the parsed id, or {@code null} if it is not a full, well-formed UUID
   */
  @Nullable
  private static UUID parseUuid(@NotNull String candidate) {
    try {
      UUID parsed = UUID.fromString(candidate);
      return parsed.toString().equals(candidate.toLowerCase(Locale.ROOT)) ? parsed : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
