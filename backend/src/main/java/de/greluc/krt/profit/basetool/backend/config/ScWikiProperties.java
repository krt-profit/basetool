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
 * Configuration properties under {@code krt.scwiki.*}: the SC Wiki base URL, its endpoint paths,
 * pacing and the sync switches.
 *
 * <p>{@code schedulerEnabled} is the master switch; each per-sync flag defaults to {@code false},
 * so a ticking scheduler does not imply that any individual sync runs.
 *
 * @param apiUrl the base URL of the SC Wiki API
 * @param commoditiesEndpoint the commodity catalogue path
 * @param blueprintsEndpoint the crafting-blueprint path
 * @param itemsEndpoint the full game-item pool path
 * @param vehiclesEndpoint the vehicle catalogue path
 * @param vehicleItemsEndpoint the ship / vehicle component path
 * @param vehicleWeaponsEndpoint the mounted ship weapon path
 * @param weaponsEndpoint the hand-held FPS weapon path
 * @param weaponAttachmentsEndpoint the weapon attachment path
 * @param armorEndpoint the FPS armor path
 * @param clothesEndpoint the clothing path
 * @param foodEndpoint the food / drink path
 * @param manufacturersEndpoint the manufacturer catalogue path
 * @param schedulerEnabled the master switch for {@code ScWikiScheduler}; {@code false} silences it
 * @param commoditySyncEnabled enables the commodity merge ({@code ScWikiCommoditySyncService})
 * @param blueprintSyncEnabled enables the blueprint sync ({@code ScWikiBlueprintSyncService})
 * @param itemSyncEnabled enables the closure-mode item sync ({@code ScWikiItemSyncService})
 * @param vehicleSyncEnabled enables the vehicle sync ({@code ScWikiVehicleSyncService})
 * @param manufacturerSyncEnabled enables the manufacturer reconciliation, which only stamps Wiki
 *     ids onto existing rows and never inserts or overwrites UEX-canonical fields
 * @param schedulerDelay the fixed delay between two scheduler ticks, in milliseconds
 * @param pageSize the {@code ?page[size]=…} value, between 50 and 200
 * @param vehiclesPageSize the page size for the {@code /api/vehicles} walk, overriding {@code
 *     pageSize} because vehicle rows are very large
 * @param requestsPerSecond the pacing target between page fetches; the sleep is {@code 1000 /
 *     requestsPerSecond} milliseconds
 * @param gameVersion an optional {@code ?version=…} pin; {@code null} follows the current game
 *     version
 * @param syncAllItems {@code true} pages in every Wiki item; {@code false} runs the item sync in
 *     closure mode
 * @param reconcileUuidlessByName whether the full backfill merges a Wiki item into a single
 *     matching uuid-less {@code UEX_ONLY} row by slug or name instead of inserting a duplicate;
 *     only consulted while {@code syncAllItems} is on
 * @param armorFilter the {@code filter[classification]} for {@code /api/armor}; must stay
 *     non-blank, or the endpoint returns the full item pool
 * @param clothesFilter the classification filter for {@code /api/clothes}; blank disables it
 * @param foodFilter the classification filter for {@code /api/food}; blank disables it
 * @param vehicleItemsFilter the classification filter for {@code /api/vehicle-items}; blank by
 *     default
 * @param vehicleWeaponsFilter the classification filter for {@code /api/vehicle-weapons}; blank by
 *     default
 * @param weaponsFilter the classification filter for {@code /api/weapons}; blank by default
 * @param weaponAttachmentsFilter the classification filter for {@code /api/weapon-attachments};
 *     blank by default
 * @param backfillKindSanityCap the maximum row count of one kind pass; a larger pass is skipped and
 *     suppresses the orphan sweep for the run
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
