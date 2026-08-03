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

package de.greluc.krt.profit.basetool.frontend.model.dto;

/**
 * Frontend mirror of the backend's Terms-of-Use consent status (REQ-SEC-028).
 *
 * @param accepted {@code true} when the caller has accepted the version currently in force
 * @param currentVersion content digest of that wording; carried so a mismatch between what the gate
 *     asked about and what the backend recorded is visible in a network trace without needing
 *     server logs
 */
public record TermsStatusDto(boolean accepted, String currentVersion) {}
