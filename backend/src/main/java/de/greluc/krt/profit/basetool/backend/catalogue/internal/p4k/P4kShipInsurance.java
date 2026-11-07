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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code insurance} of a catalogue ship (contract, "Ship insurance"); facts only, each {@code
 * null} when the Record states none.
 *
 * @param restoreOnClaim whether a claim restores the ship with its loadout
 * @param shipEntityClassName the entity class name of the insurance parameters
 * @param baseWaitTimeMinutes the claim time in minutes
 * @param mandatoryWaitTimeMinutes the shortest claim time in minutes
 * @param expediteFee the expedite fee in aUEC
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kShipInsurance(
    @Nullable Boolean restoreOnClaim,
    @Nullable String shipEntityClassName,
    @Nullable Double baseWaitTimeMinutes,
    @Nullable Double mandatoryWaitTimeMinutes,
    @Nullable Long expediteFee) {}
