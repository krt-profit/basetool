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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One parsed live-sync topic: its class, the resource it names if it names one, and the canonical
 * string both ends of the Redis channel agree on (ADR-0143).
 *
 * <p>Canonicalisation matters because the string is the room key on three sides — this instance's
 * emitter registry, a peer backend replica's, and every frontend instance's WebSocket rooms. A
 * topic that differs only in the case of its UUID would open a second, empty room next to the one
 * everybody else is in, and nothing would report it. {@link #parse(String)} therefore lower-cases
 * the id via {@link UUID#toString()} and rebuilds the string rather than keeping what arrived.
 *
 * @param topicClass the room class this topic belongs to
 * @param resourceId the resource named, or {@code null} for a global room
 * @param canonical the wire form used as the room key and published to Redis
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
   * Parses a wire topic string.
   *
   * <p>Rejects — by answering {@code null} rather than throwing, because a bad topic from a client
   * is an expected input and not an error condition — anything that is not exactly one known
   * prefix, optionally followed by one colon and one well-formed UUID, in the arity that prefix's
   * class declares. A per-resource class without an id and a global class with one are both
   * refused: those two mistakes are how {@code order} and {@code orders} would otherwise collide.
   *
   * @param raw the topic as it arrived, untrimmed and untrusted
   * @return the parsed topic, or {@code null} if it names no room this backend serves
   */
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
   * Parses a UUID strictly.
   *
   * <p>{@link UUID#fromString(String)} alone is not strict enough: it accepts short groups such as
   * {@code 1-1-1-1-1} and re-renders them padded, so two different wire strings would canonicalise
   * onto the same room. The round-trip comparison rejects anything whose canonical form differs
   * from what arrived, case aside.
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
