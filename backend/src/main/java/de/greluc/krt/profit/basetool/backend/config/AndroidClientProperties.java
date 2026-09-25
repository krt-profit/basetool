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

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code app.android.*}: which Android app builds the server still
 * serves (REQ-API-010).
 *
 * @param minimumVersionCode the oldest {@code versionCode} still served; older builds show the
 *     non-dismissible „Update erforderlich" screen. {@code 0} (the default) means no floor
 * @param latestVersionCode the newest published {@code versionCode}, or {@code 0} when unknown;
 *     informational only and never blocks a build
 * @param releasesUrl the release page the app opens to get a new build
 */
@Validated
@ConfigurationProperties(prefix = "app.android")
public record AndroidClientProperties(
    @DefaultValue("0") @NotNull @Min(0) Integer minimumVersionCode,
    @DefaultValue("0") @NotNull @Min(0) Integer latestVersionCode,
    @DefaultValue("https://github.com/krt-profit/basetool-android/releases/latest") @NotBlank
        String releasesUrl) {}
