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
 * Result of an admin-triggered manual Keycloak user sync ({@code POST /api/v1/users/sync}).
 *
 * <p>Carries only the number of users the run reconciled, which the member-management page shows in
 * its success toast before re-rendering the (now-refreshed) member list. A failure of the sync is
 * NOT represented here — it surfaces as an RFC 7807 problem response, so a 2xx with this body
 * always means the run completed. A {@code syncedCount} of {@code 0} means Keycloak returned an
 * empty roster (the reconciliation treats that as a no-op skip, never a wipe).
 *
 * @param syncedCount the number of users successfully synced this run
 */
public record UserSyncResultDto(int syncedCount) {}
