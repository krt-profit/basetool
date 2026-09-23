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

package de.greluc.krt.profit.basetool.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code krt.scwiki.*}.
 *
 * <p>Holds the SC Wiki (api.star-citizen.wiki) base URL and every endpoint path {@code
 * ScWikiClient} uses. The endpoints are stored here, not hardcoded in the client, so that a Wiki
 * schema rename is a one-line config change and so the {@code application-test.yml} can point the
 * client at a {@code MockWebServer} URL without touching code.
 *
 * <p>{@code schedulerEnabled} is the master switch and defaults to {@code true}. It defaulted to
 * {@code false} in R1/R2, while {@code ScWikiScheduler} was a skeleton with nothing to drive; R3
 * flipped it when {@code ScWikiCommoditySyncService} shipped. Do not read the default as "the Wiki
 * sync is off by default": {@code application-prod.yml} and {@code application-dev.yml} both set it
 * to {@code true} explicitly, and only {@code application-test.yml} (and the local test stack) sets
 * {@code false}. The per-sync flags are the opposite — each defaults to {@code false} and ships
 * dark — so a ticking scheduler does not imply that any individual sync runs.
 *
 * <p>{@code requestsPerSecond} caps the inter-page sleep to a safe rate (default 5/s) — Wiki's
 * advertised limits are 60/min for search and 10/min for image search; plain list endpoints have no
 * published cap but the conservative pacing leaves headroom for a future tightening.
 *
 * <p>An immutable record bound by {@code @ConfigurationPropertiesScan} (BE-MOD-04).
 *
 * @param apiUrl the base URL of the SC Wiki API; overridden in tests to point at a {@code
 *     MockWebServer}
 * @param commoditiesEndpoint the commodity (trade goods + ammo / atmosphere) catalogue path
 * @param blueprintsEndpoint the crafting-blueprint path (R3)
 * @param itemsEndpoint the full game-item pool path (R4+)
 * @param vehiclesEndpoint the vehicle catalogue path that drives the R4 Wiki vehicle fill ({@code
 *     ScWikiVehicleSyncService})
 * @param vehicleItemsEndpoint the ship / vehicle component catalogue path (R4+)
 * @param vehicleWeaponsEndpoint the mounted ship weapon path (R4+)
 * @param weaponsEndpoint the hand-held FPS weapon path (R4+)
 * @param weaponAttachmentsEndpoint the weapon attachment / scope / magazine path (R4+)
 * @param armorEndpoint the FPS armor path (R4+)
 * @param clothesEndpoint the clothing path (R4+)
 * @param foodEndpoint the food / drink path (R4+)
 * @param manufacturersEndpoint the manufacturer / in-universe vendor catalogue path (R6)
 * @param schedulerEnabled the master switch for {@code ScWikiScheduler}; R3 flipped the default to
 *     {@code true} once the scheduler drove a real implementation. The scheduler still does nothing
 *     harmful while the per-sync flags are off — it ticks and each sync self-guards. {@code false}
 *     silences it entirely.
 * @param commoditySyncEnabled the per-sync flag for the R3 Wiki commodity merge ({@code
 *     ScWikiCommoditySyncService}). {@code false} by default so R3 shipped "dark"; an operator
 *     flips it on per the deployment runbook §3 (the pattern of {@code
 *     krt.uex.item-price-sync-enabled}). Decoupled from {@code schedulerEnabled} so the scheduler
 *     can stay live for other syncs while the commodity merge is still being soaked.
 * @param blueprintSyncEnabled the per-sync flag for the R4 Wiki blueprint sync ({@code
 *     ScWikiBlueprintSyncService}); {@code false} by default, flipped on per runbook §4
 * @param itemSyncEnabled the per-sync flag for the R4 closure-mode Wiki item sync ({@code
 *     ScWikiItemSyncService}); {@code false} by default. When on it fills the Wiki columns of every
 *     existing {@code game_item} (~5000 single fetches at the configured rate), so it is flipped on
 *     deliberately.
 * @param vehicleSyncEnabled the per-sync flag for the R4 Wiki vehicle sync ({@code
 *     ScWikiVehicleSyncService}); {@code false} by default, flipped on per runbook §4
 * @param manufacturerSyncEnabled the per-sync flag for the R6 Wiki manufacturer reconciliation
 *     ({@code ScWikiManufacturerSyncService}); {@code false} by default, flipped on per runbook §6.
 *     When on, it walks {@code /api/manufacturers} and stamps {@code scwiki_uuid} / {@code
 *     scwiki_code} onto the rows the UEX sync already created — it never inserts rows and never
 *     overwrites the UEX-canonical {@code name} / {@code abbreviation} / {@code industry}.
 * @param schedulerDelay the fixed delay between two {@code ScWikiScheduler} ticks in milliseconds;
 *     24h (86 400 000 ms) by default, because Wiki data changes only on game patches (every 2-6
 *     weeks)
 * @param pageSize the {@code ?page[size]=…} value; capped at the Wiki's documented maximum of 200,
 *     and values below 50 are rejected to keep the per-cycle round-trip count reasonable
 * @param vehiclesPageSize the page size for the {@code /api/vehicles} walk, overriding {@code
 *     pageSize}. Vehicle rows are two orders of magnitude larger than any other list row — each
 *     carries its full port / hardpoint / shield / power tree — so at 200 the first page alone is
 *     10.4 MB against the client's 16 MB in-memory ceiling (measured 2026-08-28). Crossing it is
 *     not a partial read: the decode throws, the error becomes an empty list, and the vehicle sync
 *     aborts with "No vehicles received from SC Wiki API". At 50 the page is ~3.2 MB and the walk
 *     costs four extra paced requests. Kept apart from {@code pageSize} because the other endpoints
 *     would pay a real request-count cost for a page size they do not need.
 * @param requestsPerSecond the inter-page sleep target, used by {@code
 *     ScWikiClient.paceForRateLimit} between page fetches of one sync run; the sleep is {@code 1000
 *     / requestsPerSecond} milliseconds
 * @param gameVersion an optional pin for the {@code ?version=…} query parameter, e.g. {@code
 *     "4.8.0-LIVE.11875683"}; {@code null} follows the upstream default, the current game version.
 *     Pinning is reserved for incidents where a Wiki patch introduces an unexpected schema drift.
 * @param syncAllItems the R4 toggle for the full-item backfill mode. {@code false} (the default)
 *     runs the Wiki item sync in closure mode, re-fetching only items already in {@code game_item}
 *     plus those an ingested blueprint references; {@code true} pages in every Wiki item — ~12 700
 *     rows, ~10-15 min per cycle at the default 5 req/s.
 * @param reconcileUuidlessByName the Weg-2 toggle for the full-backfill name/slug reconciliation.
 *     {@code true} by default: before inserting a fresh {@code WIKI_ONLY} row for a Wiki item no
 *     row carries by {@code external_uuid}, the Mode-B backfill looks for a uuid-less {@code
 *     UEX_ONLY} row matching its {@code slug} or name and, on a single unambiguous match, merges
 *     into it (backfilling {@code external_uuid}, flipping {@code UEX_ONLY → BOTH}) instead of
 *     creating a duplicate. {@code false} falls back to the pure-UUID join. Only consulted while
 *     {@code syncAllItems} is on.
 * @param armorFilter the Mode-B {@code filter[classification]} for {@code /api/armor}. <b>Must stay
 *     non-blank</b>: without it the live endpoint returns the FULL {@code /api/items} pool (~12 700
 *     rows; SC_WIKI_SYNC_PLAN.md §3.4 quirk #1). The probed default {@code "FPS.Armor"}
 *     prefix-matches all six {@code FPS.Armor.*} sub-classes (2 318 rows on 4.8.0). Cleared, the
 *     {@code backfillKindSanityCap} guard refuses the full-pool dump rather than mis-filing it
 *     under {@code ARMOR}.
 * @param clothesFilter the Mode-B filter for {@code /api/clothes}; the probed {@code
 *     "FPS.Clothing"} prefix (1 826 rows on 4.8.0, identical to the endpoint's native total) makes
 *     the intent visible and survives a future armor-style quirk. Blank disables the filter.
 * @param foodFilter the Mode-B filter for {@code /api/food}; the probed {@code
 *     "FPS.Consumable.Food"} prefix (221 rows on 4.8.0 = Bottle + Drink + Food). Blank disables the
 *     filter.
 * @param vehicleItemsFilter the Mode-B filter for {@code /api/vehicle-items}; blank by default,
 *     because the endpoint already returns only ship components (3 211 rows on 4.8.0, paints under
 *     {@code Ship.Paints} included by design as {@code VEHICLE_ITEM}). Set only to narrow the pass.
 * @param vehicleWeaponsFilter the Mode-B filter for {@code /api/vehicle-weapons}; blank by default,
 *     because the endpoint natively returns only mounted ship weapons (168 rows on 4.8.0)
 * @param weaponsFilter the Mode-B filter for {@code /api/weapons}; blank by default, because the
 *     endpoint natively returns only hand-held FPS weapons (391 rows on 4.8.0) and a {@code
 *     FPS.Weapon} prefix disagrees slightly with it
 * @param weaponAttachmentsFilter the Mode-B filter for {@code /api/weapon-attachments}; blank by
 *     default, because the endpoint natively returns only attachments (104 rows on 4.8.0) while the
 *     {@code FPS.WeaponAttachment} prefix is broader (163)
 * @param backfillKindSanityCap the Mode-B safety cap on the row count of one <em>kind</em> pass
 *     (armor, clothes, food, weapons, weapon-attachments, vehicle-items, vehicle-weapons). A pass
 *     returning more rows is assumed to have hit the §3.4 full-pool quirk and is <b>skipped</b> —
 *     its rows are not ingested and the cross-kind orphan sweep is suppressed for the run. 9 000
 *     sits above the largest legitimate kind (vehicle-items, 3 211) and well below the full pool;
 *     the residual {@code /api/items} {@code GENERIC} pass is exempt.
 */
@Validated
@ConfigurationProperties(prefix = "krt.scwiki")
public record ScWikiProperties(
    @DefaultValue("https://api.star-citizen.wiki") @NotBlank String apiUrl,
    @DefaultValue("/api/commodities") @NotBlank String commoditiesEndpoint,
    @DefaultValue("/api/blueprints") @NotBlank String blueprintsEndpoint,
    @DefaultValue("/api/items") @NotBlank String itemsEndpoint,
    @DefaultValue("/api/vehicles") @NotBlank String vehiclesEndpoint,
    @DefaultValue("/api/vehicle-items") @NotBlank String vehicleItemsEndpoint,
    @DefaultValue("/api/vehicle-weapons") @NotBlank String vehicleWeaponsEndpoint,
    @DefaultValue("/api/weapons") @NotBlank String weaponsEndpoint,
    @DefaultValue("/api/weapon-attachments") @NotBlank String weaponAttachmentsEndpoint,
    @DefaultValue("/api/armor") @NotBlank String armorEndpoint,
    @DefaultValue("/api/clothes") @NotBlank String clothesEndpoint,
    @DefaultValue("/api/food") @NotBlank String foodEndpoint,
    @DefaultValue("/api/manufacturers") @NotBlank String manufacturersEndpoint,
    @DefaultValue("true") @NotNull Boolean schedulerEnabled,
    @DefaultValue("false") @NotNull Boolean commoditySyncEnabled,
    @DefaultValue("false") @NotNull Boolean blueprintSyncEnabled,
    @DefaultValue("false") @NotNull Boolean itemSyncEnabled,
    @DefaultValue("false") @NotNull Boolean vehicleSyncEnabled,
    @DefaultValue("false") @NotNull Boolean manufacturerSyncEnabled,
    @DefaultValue("86400000") @NotBlank String schedulerDelay,
    @DefaultValue("200") @Min(50) @Max(200) Integer pageSize,
    @DefaultValue("50") @Min(50) @Max(200) Integer vehiclesPageSize,
    @DefaultValue("5") @Min(1) @Max(20) Integer requestsPerSecond,
    String gameVersion,
    @DefaultValue("false") @NotNull Boolean syncAllItems,
    @DefaultValue("true") @NotNull Boolean reconcileUuidlessByName,
    @DefaultValue("FPS.Armor") String armorFilter,
    @DefaultValue("FPS.Clothing") String clothesFilter,
    @DefaultValue("FPS.Consumable.Food") String foodFilter,
    @DefaultValue("") String vehicleItemsFilter,
    @DefaultValue("") String vehicleWeaponsFilter,
    @DefaultValue("") String weaponsFilter,
    @DefaultValue("") String weaponAttachmentsFilter,
    @DefaultValue("9000") @NotNull @Min(100) Integer backfillKindSanityCap) {}
