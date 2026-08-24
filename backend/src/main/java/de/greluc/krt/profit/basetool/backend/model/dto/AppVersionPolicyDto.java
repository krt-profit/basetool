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
 * Which Android builds the server still serves (REQ-API-010).
 *
 * <p>The app compares its own {@code versionCode} against {@link #minimumVersionCode} and, when it
 * falls below, shows the non-dismissible „Update erforderlich" screen of design chapter 14. The
 * decision is the client's to act on, but the number is the server's to state — nothing else knows
 * when a contract stopped being served.
 *
 * <p>{@link #latestVersionCode} is carried separately and on purpose. Collapsing the two into one
 * number would make every release a forced one: the app could no longer tell "your build is no
 * longer served" from "a newer build exists", and the only screen it has for the first is a wall.
 *
 * @param minimumVersionCode the oldest {@code versionCode} still served; {@code 0} means no floor
 * @param latestVersionCode the newest {@code versionCode} published, or {@code 0} when unknown
 * @param releasesUrl where the member gets the new build — the GitHub release page, since
 *     distribution is Releases plus Obtainium rather than a store
 */
public record AppVersionPolicyDto(
    int minimumVersionCode, int latestVersionCode, String releasesUrl) {}
