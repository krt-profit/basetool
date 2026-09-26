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

import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of one holder-registry row (REQ-BANK-003) with the holder's global custody total.
 *
 * @param id the holder row's id
 * @param userId the linked basetool user, or {@code null} after user deletion
 * @param handle the linked user's live effective name, or the handle snapshot once the user is
 *     deleted
 * @param active whether the holder accepts new incoming postings
 * @param totalHeld signed global sum the holder physically holds across the whole bank (may be
 *     negative)
 * @param roleManaged whether the holder was auto-created from a bank role (REQ-BANK-029)
 * @param version optimistic-locking version to echo on mutations
 */
public record BankHolderDto(
    UUID id,
    @Nullable UUID userId,
    String handle,
    boolean active,
    BigDecimal totalHeld,
    boolean roleManaged,
    Long version) {}
