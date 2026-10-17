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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * Filters the section keys of a {@code changed} frame against its topic class's whitelist
 * (REQ-FE-010, REQ-FE-015): only opaque, whitelisted keys cross the live-sync socket.
 */
final class LiveSyncSections {

  /** Hard cap on the number of section keys relayed per {@code changed} frame (abuse guard). */
  static final int MAX_CHANGED_SECTIONS = 16;

  private LiveSyncSections() {}

  /**
   * Outcome of filtering one frame's section keys against a class whitelist; duplicates and
   * non-string entries do not count as rejected.
   *
   * @param accepted the accepted, de-duplicated, {@link #MAX_CHANGED_SECTIONS}-capped keys
   * @param rejected how many keys the whitelist refused
   * @param firstRejectedKey the first refused key, or {@code null} when none was refused
   */
  record Filtered(List<String> accepted, int rejected, String firstRejectedKey) {}

  /**
   * Keeps only whitelisted section keys, de-duplicated and capped; the {@link List} counterpart of
   * {@link #sanitise(JsonNode, LiveSyncTopicClass)}.
   *
   * @param sections the raw section keys
   * @param topicClass the class whose whitelist applies
   * @return the accepted keys plus the rejection evidence
   */
  @NotNull
  static Filtered retainAllowed(
      @NotNull List<String> sections, @NotNull LiveSyncTopicClass topicClass) {
    Set<String> allowed = topicClass.allowedSections();
    List<String> result = new ArrayList<>();
    int rejected = 0;
    String firstRejected = null;
    for (String section : sections) {
      if (result.size() >= MAX_CHANGED_SECTIONS) {
        break;
      }
      if (section == null) {
        continue;
      }
      if (!allowed.contains(section)) {
        rejected++;
        if (firstRejected == null) {
          firstRejected = section;
        }
        continue;
      }
      if (!result.contains(section)) {
        result.add(section);
      }
    }
    return new Filtered(result, rejected, firstRejected);
  }

  /**
   * Sanitises an inbound {@code sections} array: drops non-strings and keys outside {@link
   * LiveSyncTopicClass#allowedSections()}, collapses duplicates and caps at {@link
   * #MAX_CHANGED_SECTIONS}.
   *
   * @param sectionsNode the raw {@code sections} node (may be {@code null} or not an array)
   * @param topicClass the class whose whitelist applies
   * @return the accepted keys plus the rejection evidence (never {@code null})
   */
  @NotNull
  static Filtered sanitise(JsonNode sectionsNode, @NotNull LiveSyncTopicClass topicClass) {
    List<String> sections = new ArrayList<>();
    if (sectionsNode == null || !sectionsNode.isArray()) {
      return new Filtered(sections, 0, null);
    }
    Set<String> allowed = topicClass.allowedSections();
    int rejected = 0;
    String firstRejected = null;
    for (JsonNode element : sectionsNode) {
      if (sections.size() >= MAX_CHANGED_SECTIONS) {
        break;
      }
      if (element == null || !element.isString()) {
        continue;
      }
      String key = element.asString();
      if (!allowed.contains(key)) {
        rejected++;
        if (firstRejected == null) {
          firstRejected = key;
        }
        continue;
      }
      if (!sections.contains(key)) {
        sections.add(key);
      }
    }
    return new Filtered(sections, rejected, firstRejected);
  }
}
