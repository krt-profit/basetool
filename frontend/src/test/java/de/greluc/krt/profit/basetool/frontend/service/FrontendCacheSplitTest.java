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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for the per-domain frontend cache split (FE-CACHE-1/2). Pins the {@link
 * CachedCatalog} URIs and fetch modes (so a refactor cannot silently change a cache target or
 * re-truncate a page-walked catalogue, REQ-ADMIN-003), proves every domain is a registered Caffeine
 * cache, and — the split's whole point — that {@code evict(domain)} drops only that domain's cache
 * while a sibling domain is retained.
 */
@SpringBootTest
@ActiveProfiles("test")
class FrontendCacheSplitTest {

  @Autowired private CacheManager cacheManager;
  @Autowired private BackendApiClient backendApiClient;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @Test
  void cachedCatalogUrisArePinned() {
    assertEquals("/api/v1/squadrons?size=1000&sort=name,asc", CachedCatalog.SQUADRONS.getUri());
    assertEquals(
        "/api/v1/special-commands?size=1000&sort=name,asc",
        CachedCatalog.SPECIAL_COMMANDS.getUri());
    assertEquals("/api/v1/org-units/active", CachedCatalog.ORG_UNITS_ACTIVE.getUri());
    assertEquals(
        "/api/v1/org-units/active-all-kinds", CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS.getUri());
    assertEquals("/api/v1/terminals?size=10000", CachedCatalog.TERMINALS.getUri());
    assertEquals("/api/v1/materials/matrix?size=100000", CachedCatalog.MATERIALS_MATRIX.getUri());
  }

  @Test
  void cachedCatalogFetchModesArePinned() {
    // REQ-ADMIN-003: exactly these paged catalogues are assembled complete by the page walk
    // inside getCached — dropping one from this set silently reintroduces the bounded-page
    // truncation this test exists to prevent.
    java.util.EnumSet<CachedCatalog> expectedPageWalked =
        java.util.EnumSet.of(
            CachedCatalog.SQUADRONS,
            CachedCatalog.SQUADRONS_UNSORTED,
            CachedCatalog.SPECIAL_COMMANDS,
            CachedCatalog.JOB_TYPES_MISSION,
            CachedCatalog.JOB_TYPES_CREW,
            CachedCatalog.MATERIALS,
            CachedCatalog.MATERIALS_MATRIX,
            CachedCatalog.LOCATIONS,
            CachedCatalog.SHIP_TYPES,
            CachedCatalog.SHIP_TYPES_SORTED,
            CachedCatalog.REFINING_METHODS,
            CachedCatalog.FREQUENCY_TYPES_ACTIVE,
            CachedCatalog.TERMINALS,
            CachedCatalog.MANUFACTURERS);
    for (CachedCatalog catalog : CachedCatalog.values()) {
      assertEquals(
          expectedPageWalked.contains(catalog),
          catalog.isPageWalked(),
          () -> catalog.name() + " has an unexpected fetch mode");
    }
    // ITEM_CATALOG is a deliberate single-row existence probe (size=1) — pinned SINGLE so nobody
    // "fixes" it into a walk and pulls the whole item catalogue into the probe cache entry.
    assertEquals(CachedCatalog.Fetch.SINGLE, CachedCatalog.ITEM_CATALOG.getFetch());
  }

  @Test
  void pageWalkedCatalogUrisSupportPageAppending() {
    // The walk appends "&page=N" to the pinned URI, so every page-walked URI must already carry
    // a query string with an explicit chunk size and must not pin a page index of its own.
    for (CachedCatalog catalog : CachedCatalog.values()) {
      if (!catalog.isPageWalked()) {
        continue;
      }
      String uri = catalog.getUri();
      assertTrue(
          uri.contains("?") && uri.contains("size="),
          () -> catalog.name() + " must carry a query string with an explicit chunk size");
      assertFalse(
          uri.contains("page="),
          () -> catalog.name() + " must not pin a page index — the walk appends &page=N");
    }
  }

  @Test
  void everyDomainIsARegisteredCacheAndEveryCatalogMapsToOne() {
    for (CacheDomain domain : CacheDomain.values()) {
      assertNotNull(
          cacheManager.getCache(domain.getCacheName()),
          () -> "domain cache '" + domain.getCacheName() + "' must be registered by CacheConfig");
    }
    for (CachedCatalog catalog : CachedCatalog.values()) {
      assertTrue(
          catalog.getUri().startsWith("/api/v1/"),
          () -> catalog.name() + " must cache an /api/v1 backend URI");
      assertNotNull(
          cacheManager.getCache(catalog.getDomain().getCacheName()),
          () -> catalog.name() + " maps to an unregistered domain");
    }
  }

  @Test
  void evict_dropsOnlyTheGivenDomain_siblingRetained() {
    Cache squadron = cacheManager.getCache(CacheDomain.SQUADRON.getCacheName());
    Cache terminal = cacheManager.getCache(CacheDomain.TERMINAL.getCacheName());
    assertNotNull(squadron);
    assertNotNull(terminal);
    squadron.put("k", "v");
    terminal.put("k", "v");

    backendApiClient.evict(CacheDomain.SQUADRON);

    assertNull(squadron.get("k"), "the evicted domain must be cleared");
    assertNotNull(
        terminal.get("k"),
        "a sibling domain must be retained — the whole point of the split is that a squadron "
            + "mutation no longer cold-starts the terminal list");
  }

  @Test
  void evictAllCatalogues_dropsEveryDomain() {
    for (CacheDomain domain : CacheDomain.values()) {
      cacheManager.getCache(domain.getCacheName()).put("k", "v");
    }

    backendApiClient.evictAllCatalogues();

    for (CacheDomain domain : CacheDomain.values()) {
      assertNull(
          cacheManager.getCache(domain.getCacheName()).get("k"),
          () -> "evictAllCatalogues must clear " + domain.getCacheName());
    }
  }
}
