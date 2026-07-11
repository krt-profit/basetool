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
 * A parsed, validated live-sync topic: its {@link LiveSyncTopicClass}, the optional resource id,
 * and the canonical wire string used as the room key (REQ-FE-015, ADR-0094).
 *
 * <p>Only {@link #parse(String)} constructs instances, so a {@code LiveSyncTopic} is always a
 * well-formed topic of a known class with the scope its class demands (a resource UUID present iff
 * the class is {@link LiveSyncTopicClass#scoped()}). The {@link #canonical()} string is what the
 * relay uses to key rooms and what crosses Redis, so it is normalised (lower-cased UUID) to make
 * two spellings of the same topic collide in one room.
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
   * Parses a raw wire topic string into a {@link LiveSyncTopic}, or returns {@code null} if it
   * names no known class, violates its class's scope (a UUID where none is allowed or vice versa),
   * or the id segment is not a UUID.
   *
   * <p>Accepted shapes: the bare {@link LiveSyncTopicClass#prefix()} for a global class, or {@code
   * prefix:uuid} for a scoped class. Matching is exact on the prefix — an unknown prefix yields
   * {@code null} rather than a fallback class.
   *
   * @param raw the raw topic string from a client frame (may be {@code null})
   * @return the parsed topic, or {@code null} if {@code raw} is not a valid topic of a known class
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
   * Resolves the topic class whose {@link LiveSyncTopicClass#prefix()} equals {@code prefix} and
   * whose scope matches the request. When a prefix is shared between a global and a scoped class
   * ({@code bank} → {@link LiveSyncTopicClass#BANK_STAFF} global vs {@link
   * LiveSyncTopicClass#BANK_ACCOUNT} scoped), the caller's id segment decides which: the scoped
   * class when an id segment is present, the global one otherwise. Returns {@code null} for an
   * unknown prefix or when no class of the requested scope uses it (e.g. {@code orders:{id}} — a
   * scoped request on a prefix that only has a global class).
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
