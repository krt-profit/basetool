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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipStoreSlug;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Cross-checks the UEX store URLs against the catalogue's curated {@code shipStoreSlugs}
 * (REQ-P4K-031): a slug whose ship differs from the P4K-known ship type holding that store URL is
 * reported to {@code external_sync_report}; nothing is written.
 */
@Component
@RequiredArgsConstructor
class P4kShipStoreSlugCheck {

  private final ShipTypeRepository shipTypeRepository;
  private final SyncReportService syncReportService;

  /**
   * Checks the slugs of an APPLY; a preview checks nothing.
   *
   * @param slugs the catalogue's ship store slugs
   * @param context the run
   */
  void check(@NotNull List<P4kShipStoreSlug> slugs, @NotNull P4kImportContext context) {
    UUID runId = context.getRunId();
    if (!context.isApply() || runId == null || slugs.isEmpty()) {
      return;
    }
    Map<String, List<ShipType>> byStoreSlug = new HashMap<>();
    for (ShipType row : shipTypeRepository.findByUrlStoreIsNotNull()) {
      String slug = storeSlug(row.getUrlStore());
      if (slug != null) {
        byStoreSlug.computeIfAbsent(slug, _ -> new ArrayList<>()).add(row);
      }
    }
    for (P4kShipStoreSlug slug : slugs) {
      UUID shipGuid = P4kGuids.parse(slug.shipGuid());
      if (Boolean.TRUE.equals(slug.retired()) || shipGuid == null || slug.slug() == null) {
        continue;
      }
      for (ShipType row :
          byStoreSlug.getOrDefault(slug.slug().toLowerCase(Locale.ROOT), List.of())) {
        if (row.getP4kUuid() != null && !row.getP4kUuid().equals(shipGuid)) {
          syncReportService.logP4kEvent(
              runId,
              SyncEventType.P4K_DISAGREEMENT,
              "ship_type",
              row.getP4kUuid(),
              row.getName(),
              "column=url_store;slug="
                  + slug.slug()
                  + ";stored="
                  + row.getP4kUuid()
                  + ";source="
                  + shipGuid);
        }
      }
    }
  }

  /**
   * Reads the slug of a store URL: its last path segment, lower-cased.
   *
   * @param url the URL, or {@code null}
   * @return the slug, or {@code null} when the URL has no path segment
   */
  @Nullable
  static String storeSlug(@Nullable String url) {
    if (url == null) {
      return null;
    }
    String path = url.strip();
    int cut = indexOfAny(path, '?', '#');
    if (cut >= 0) {
      path = path.substring(0, cut);
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    int slash = path.lastIndexOf('/');
    String slug = slash < 0 ? path : path.substring(slash + 1);
    return slug.isBlank() ? null : slug.toLowerCase(Locale.ROOT);
  }

  /**
   * Finds the first of two characters.
   *
   * @param text the text
   * @param a one character
   * @param b the other character
   * @return the first index of either, or {@code -1}
   */
  private static int indexOfAny(@NotNull String text, char a, char b) {
    int ia = text.indexOf(a);
    int ib = text.indexOf(b);
    if (ia < 0) {
      return ib;
    }
    return ib < 0 ? ia : Math.min(ia, ib);
  }
}
