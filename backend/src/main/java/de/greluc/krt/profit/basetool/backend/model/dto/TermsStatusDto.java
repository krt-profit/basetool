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
 * Whether the calling user has accepted the Terms of Use currently in force (REQ-SEC-028).
 *
 * <p>This is the one API surface a user who has <em>not</em> accepted may still reach — the
 * enforcement filter allowlists it — because it is what tells the frontend to route them to the
 * acceptance page instead of into the tool.
 *
 * @param accepted {@code true} when consent for {@code currentVersion} is on record
 * @param currentVersion content digest of the wording in force; surfaced so the frontend can echo
 *     it back on acceptance and a mismatch is diagnosable from a HAR alone rather than requiring
 *     server logs
 */
public record TermsStatusDto(boolean accepted, String currentVersion) {}
