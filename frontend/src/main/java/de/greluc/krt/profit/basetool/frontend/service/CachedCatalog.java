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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Compile-time allowlist of the backend GET requests that {@link BackendApiClient#getCached} may
 * cache (FE-CACHE-1). Each constant pins its exact request URI and its invalidation {@link
 * CacheDomain}; the cache key is the constant's {@code name()}.
 *
 * <p>This makes the unsafe state <b>unrepresentable</b>: because the only cached-read entry point
 * takes a {@code CachedCatalog} (the raw {@code getCached(String, …)} overloads were removed), a
 * per-principal / per-active-org-unit URI such as {@code /api/v1/users/me}, {@code
 * /api/v1/me/capabilities} or {@code /api/v1/me/active-org-unit} simply cannot be cached — no
 * constant names it, and adding one is a reviewable, spec-gated act (REQ-DATA-007). Every catalogue
 * here is verified <b>global</b>: no variance by caller {@code sub}, roles, the {@code
 * X-Active-Org-Unit-Id} header, or guest redaction — so the shared, URI/name-keyed cache cannot
 * cross-contaminate callers.
 *
 * <p>The URIs are copied verbatim from the former inline literals; {@code CachedCatalogTest} pins
 * each one so a refactor cannot silently change a cache target.
 */
@Getter
@RequiredArgsConstructor
public enum CachedCatalog {
  /** The squadron catalogue (sorted) — admin switcher + job-order picker. */
  SQUADRONS("/api/v1/squadrons?size=1000&sort=name,asc", CacheDomain.SQUADRON),
  /** The squadron catalogue (unsorted variant used by the mission page). */
  SQUADRONS_UNSORTED("/api/v1/squadrons?size=1000", CacheDomain.SQUADRON),
  /** The Spezialkommando catalogue — admin switcher (eviction-gated, REQ-DATA-007). */
  SPECIAL_COMMANDS("/api/v1/special-commands?size=1000&sort=name,asc", CacheDomain.SQUADRON),
  /** Active Staffel + SK owner picker (public — the anonymous job-order form uses it). */
  ORG_UNITS_ACTIVE("/api/v1/org-units/active", CacheDomain.ORG_UNIT),
  /** Active Staffel + SK + Bereich + OL owner picker (authenticated). */
  ORG_UNITS_ACTIVE_ALL_KINDS("/api/v1/org-units/active-all-kinds", CacheDomain.ORG_UNIT),
  /** Mission job-type archetype list. */
  JOB_TYPES_MISSION("/api/v1/job-types?archetype=MISSION&size=1000", CacheDomain.JOB_TYPE),
  /** Crew job-type archetype list. */
  JOB_TYPES_CREW("/api/v1/job-types?archetype=CREW&size=1000", CacheDomain.JOB_TYPE),
  /** Full material catalogue. */
  MATERIALS("/api/v1/materials?size=1000", CacheDomain.MATERIAL),
  /** Job-order material subset. */
  MATERIALS_JOB_ORDER("/api/v1/materials/job-order", CacheDomain.MATERIAL),
  /** Material typeahead lookup projection. */
  MATERIALS_LOOKUP("/api/v1/materials/lookup", CacheDomain.MATERIAL),
  /** The full material &times; terminal price matrix (heavy — 100&nbsp;000-row pull). */
  MATERIALS_MATRIX("/api/v1/materials/matrix?size=100000", CacheDomain.MATERIAL),
  /** Full location catalogue. */
  LOCATIONS("/api/v1/locations?size=1000", CacheDomain.LOCATION),
  /** Location typeahead lookup projection. */
  LOCATIONS_LOOKUP("/api/v1/locations/lookup", CacheDomain.LOCATION),
  /** Home-location subset. */
  LOCATIONS_HOME("/api/v1/locations/home-locations", CacheDomain.LOCATION),
  /** Refinery-location subset. */
  LOCATIONS_REFINERIES("/api/v1/locations/refineries", CacheDomain.LOCATION),
  /** Ship-type catalogue. */
  SHIP_TYPES("/api/v1/ship-types?size=1000", CacheDomain.SHIP_TYPE),
  /** Ship-type catalogue (sorted variant used by the profit calculator). */
  SHIP_TYPES_SORTED("/api/v1/ship-types?size=1000&sort=name,asc", CacheDomain.SHIP_TYPE),
  /** Refining-method catalogue. */
  REFINING_METHODS("/api/v1/refining-methods?size=1000", CacheDomain.REFINING_METHOD),
  /** Active frequency-type catalogue (sorted by sort index). */
  FREQUENCY_TYPES_ACTIVE(
      "/api/v1/frequency-types?size=1000&active=true&sort=sortIndex,asc",
      CacheDomain.FREQUENCY_TYPE),
  /** Terminal catalogue (the large price-matrix source). */
  TERMINALS("/api/v1/terminals?size=10000", CacheDomain.TERMINAL),
  /** Manufacturer catalogue. */
  MANUFACTURERS("/api/v1/manufacturers?size=1000", CacheDomain.MANUFACTURER),
  /** Orderable-item reference catalogue (a single-row probe used for the typeahead). */
  ITEM_CATALOG("/api/v1/orders/item-catalog?size=1&sort=name,asc", CacheDomain.ITEM_CATALOG),
  /** Job-order yellow-age-threshold setting. */
  SETTING_JOB_ORDER_AGE_YELLOW("/api/v1/settings/job_order.age_yellow_days", CacheDomain.SETTINGS),
  /** Job-order red-age-threshold setting. */
  SETTING_JOB_ORDER_AGE_RED("/api/v1/settings/job_order.age_red_days", CacheDomain.SETTINGS);

  /** The exact backend request URI this catalogue caches. */
  private final String uri;

  /** The invalidation domain (named cache) this catalogue lives in. */
  private final CacheDomain domain;
}
