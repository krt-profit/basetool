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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Exposes the allowed section keys of every {@link LiveSyncTopicClass} to the templates, so the
 * head fragment renders them into {@code data-*} attributes and the page modules read them instead
 * of keeping their own copy (REQ-FE-010, ADR-0289).
 */
@Component("liveSyncSectionKeys")
public class LiveSyncSectionKeys {

  /**
   * The allowed section keys per topic class, comma-separated and sorted.
   *
   * @return the keys by topic class name, in declaration order of the classes
   */
  @NotNull
  public Map<String, String> byTopicClass() {
    Map<String, String> keys = new LinkedHashMap<>();
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      keys.put(topicClass.name(), String.join(",", new TreeSet<>(topicClass.allowedSections())));
    }
    return keys;
  }
}
