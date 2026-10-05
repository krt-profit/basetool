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

package de.greluc.krt.profit.basetool.frontend.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The catalogue-cache evictions a page controller triggers after an admin write, so a controller
 * reaches the backend only through its typed client and never holds {@link BackendApiClient} itself
 * (plan F3, REQ-DATA-007, FE-CACHE-2).
 */
@Service
@RequiredArgsConstructor
public class CatalogueCacheEviction {

  /** Owns the catalogue caches. */
  private final BackendApiClient backendApiClient;

  /**
   * Evicts the cache of each given domain.
   *
   * @param domains the invalidation domains to clear
   */
  public void evict(@NotNull CacheDomain... domains) {
    backendApiClient.evict(domains);
  }

  /** Evicts every catalogue domain, for a write whose affected domains are not known. */
  public void evictAllCatalogues() {
    backendApiClient.evictAllCatalogues();
  }

  /** Evicts every catalogue domain after an admin write of unknown reach (REQ-DATA-007). */
  public void clearStaticDataCache() {
    backendApiClient.clearStaticDataCache();
  }
}
