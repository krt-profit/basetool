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

package de.greluc.krt.profit.basetool.frontend.websocket;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A parsed, validated live-sync topic: its {@link LiveSyncTopicClass}, optional resource id and
 * canonical wire string used as the room key (REQ-FE-015, ADR-0094).
 *
 * <p>Instances come only from {@link #parse(String)}; {@link #canonical()} is normalised
 * (lower-case UUID) so equivalent spellings share a room.
 *
 * @param topicClass the class this topic belongs to
 * @param resourceId the resource UUID for a scoped class, or {@code null} for a global class
 * @param canonical the canonical wire string ({@code prefix} or {@code prefix:uuid})
 */
public record LiveSyncTopic(
    @NotNull LiveSyncTopicClass topicClass, @Nullable UUID resourceId, @NotNull String canonical) {

  /** Hard cap on a raw topic string, so a crafted client cannot ship a megabyte "topic". */
  private static final int MAX_RAW_LENGTH = 128;

  /**
   * Parses a raw wire topic ({@code prefix} for a global class, {@code prefix:uuid} for a scoped
   * one) into a {@link LiveSyncTopic}.
   *
   * @param raw the raw topic string from a client frame (may be {@code null})
   * @return the parsed topic, or {@code null} for an unknown prefix, a scope mismatch or a non-UUID
   *     id
   */
  @Nullable
  public static LiveSyncTopic parse(@Nullable String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > MAX_RAW_LENGTH) {
      return null;
    }
    int colon = raw.indexOf(':');
    String prefix = (colon < 0) ? raw : raw.substring(0, colon);
    String idPart = (colon < 0) ? null : raw.substring(colon + 1);
    LiveSyncTopicClass topicClass = classForPrefix(prefix, idPart != null);
    if (topicClass == null) {
      return null;
    }
    if (topicClass.scoped()) {
      if (idPart == null || idPart.isEmpty()) {
        return null;
      }
      UUID id;
      try {
        id = UUID.fromString(idPart);
      } catch (IllegalArgumentException e) {
        return null;
      }
      return new LiveSyncTopic(topicClass, id, prefix + ":" + id);
    }
    if (idPart != null) {
      return null;
    }
    return new LiveSyncTopic(topicClass, null, prefix);
  }

  /**
   * Resolves the topic class for a prefix and scope; for a prefix shared by a global and a scoped
   * class (e.g. {@code bank}), the presence of an id segment decides.
   *
   * @param prefix the wire prefix (never {@code null})
   * @param hasIdSegment whether the raw topic carried a {@code :id} segment
   * @return the matching class, or {@code null} if no class of the requested scope uses that prefix
   */
  @Nullable
  private static LiveSyncTopicClass classForPrefix(@NotNull String prefix, boolean hasIdSegment) {
    LiveSyncTopicClass scopedMatch = null;
    LiveSyncTopicClass globalMatch = null;
    for (LiveSyncTopicClass candidate : LiveSyncTopicClass.values()) {
      if (candidate.prefix().equals(prefix)) {
        if (candidate.scoped()) {
          scopedMatch = candidate;
        } else {
          globalMatch = candidate;
        }
      }
    }
    return hasIdSegment ? scopedMatch : globalMatch;
  }
}
