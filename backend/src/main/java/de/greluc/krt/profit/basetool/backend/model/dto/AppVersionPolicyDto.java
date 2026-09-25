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

package de.greluc.krt.profit.basetool.backend.model.dto;

/**
 * Which Android builds the server still serves (REQ-API-010). An app below {@link
 * #minimumVersionCode} shows the non-dismissible update screen; {@link #latestVersionCode}
 * separately signals that a newer build exists.
 *
 * @param minimumVersionCode oldest {@code versionCode} still served; {@code 0} means no floor
 * @param latestVersionCode newest published {@code versionCode}, or {@code 0} when unknown
 * @param releasesUrl the GitHub release page to get the new build from
 */
public record AppVersionPolicyDto(
    int minimumVersionCode, int latestVersionCode, String releasesUrl) {}
