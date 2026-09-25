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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code krt.uex.*}: the UEX (uexcorp.space) base URL, every
 * endpoint path used by {@link de.greluc.krt.profit.basetool.backend.integration.UexClient}, and
 * the sync scheduler switches.
 *
 * @param apiUrl the UEX API base URL the endpoint paths are appended to
 * @param commoditiesEndpoint the commodity catalogue path
 * @param commoditiesPricesEndpoint the all-terminals commodity price path
 * @param starSystemsEndpoint the star-system reference path
 * @param companiesEndpoint the company (manufacturer) reference path
 * @param vehiclesEndpoint the vehicle catalogue path
 * @param citiesEndpoint the city reference path
 * @param factionsEndpoint the faction reference path
 * @param jurisdictionsEndpoint the jurisdiction reference path
 * @param moonsEndpoint the moon reference path
 * @param orbitsEndpoint the orbit reference path
 * @param outpostsEndpoint the outpost reference path
 * @param planetsEndpoint the planet reference path
 * @param poiEndpoint the point-of-interest reference path
 * @param spaceStationsEndpoint the space-station reference path
 * @param terminalsEndpoint the terminal reference path
 * @param refineriesMethodsEndpoint the refining-method reference path
 * @param refineriesYieldsEndpoint the per-refinery yield-bonus path
 * @param itemsEndpoint the item-catalogue path, called per category as {@code
 *     /items?id_category=<n>}
 * @param itemsPricesEndpoint the item-price path, used only while {@code itemPriceSyncEnabled} is
 *     on
 * @param categoriesEndpoint the category reference path that drives the item walk
 * @param itemPriceSyncEnabled the master switch for {@code UexItemPriceSyncService}; {@code false}
 *     by default
 * @param schedulerEnabled whether the periodic UEX sync runs at all
 * @param schedulerDelay the fixed delay between two sync runs in milliseconds, as a string; {@code
 *     86400000} (one day) by default
 */
@Validated
@ConfigurationProperties(prefix = "krt.uex")
public record UexProperties(
    @DefaultValue("https://api.uexcorp.space/2.0") @NotBlank String apiUrl,
    @DefaultValue("/commodities") @NotBlank String commoditiesEndpoint,
    @DefaultValue("/commodities_prices_all") @NotBlank String commoditiesPricesEndpoint,
    @DefaultValue("/star_systems") @NotBlank String starSystemsEndpoint,
    @DefaultValue("/companies") @NotBlank String companiesEndpoint,
    @DefaultValue("/vehicles") @NotBlank String vehiclesEndpoint,
    @DefaultValue("/cities") @NotBlank String citiesEndpoint,
    @DefaultValue("/factions") @NotBlank String factionsEndpoint,
    @DefaultValue("/jurisdictions") @NotBlank String jurisdictionsEndpoint,
    @DefaultValue("/moons") @NotBlank String moonsEndpoint,
    @DefaultValue("/orbits") @NotBlank String orbitsEndpoint,
    @DefaultValue("/outposts") @NotBlank String outpostsEndpoint,
    @DefaultValue("/planets") @NotBlank String planetsEndpoint,
    @DefaultValue("/poi") @NotBlank String poiEndpoint,
    @DefaultValue("/space_stations") @NotBlank String spaceStationsEndpoint,
    @DefaultValue("/terminals") @NotBlank String terminalsEndpoint,
    @DefaultValue("/refineries_methods") @NotBlank String refineriesMethodsEndpoint,
    @DefaultValue("/refineries_yields") @NotBlank String refineriesYieldsEndpoint,
    @DefaultValue("/items") @NotBlank String itemsEndpoint,
    @DefaultValue("/items_prices_all") @NotBlank String itemsPricesEndpoint,
    @DefaultValue("/categories") @NotBlank String categoriesEndpoint,
    @DefaultValue("false") @NotNull Boolean itemPriceSyncEnabled,
    @DefaultValue("true") @NotNull Boolean schedulerEnabled,
    @DefaultValue("86400000") @NotBlank String schedulerDelay) {}
