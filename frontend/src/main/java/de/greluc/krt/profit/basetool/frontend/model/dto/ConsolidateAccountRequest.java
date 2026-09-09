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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend consolidate-account request body (REQ-SEC-055, #1828): fold the
 * duplicate account named in the path into the account the member keeps.
 *
 * <p>Bound from the member administration's consolidate dialog and relayed verbatim to {@code POST
 * /api/v1/users/{id}/consolidate}. Kept as a record rather than a map so the contract test ({@code
 * DtoOpenApiContractTest}) can check it against the committed {@code openapi.json}.
 *
 * @param targetUserId the account the member keeps and everything moves onto
 * @param version the duplicate's optimistic-lock version the admin last read; {@code null} bypasses
 *     the check
 */
public record ConsolidateAccountRequest(UUID targetUserId, @Nullable Long version) {}
