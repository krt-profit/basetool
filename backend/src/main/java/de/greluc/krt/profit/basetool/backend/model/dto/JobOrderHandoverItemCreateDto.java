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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One line of a material handover: hand over {@code amount} SCU of the inventory entry {@code
 * inventoryItemId} to the job order. Since Variante C (REQ-INV-027) the handed amount also leaves
 * the entry's mission earmarks, so {@code missionReductions} optionally names how much of it comes
 * out of each mission slice (whatever it leaves comes from the mission rest). A {@code null} plan
 * lets the backend clamp the mission dimension automatically — rest-first, then proportional; the
 * modal surfaces the picker only for the ambiguous multi-mission case.
 *
 * @param inventoryItemId the inventory entry the stock is handed over from
 * @param amount the SCU amount handed over from that entry
 * @param missionReductions the per-mission "deduct from" plan for the handed amount, or {@code
 *     null} to auto-clamp the mission earmarks
 */
public record JobOrderHandoverItemCreateDto(
    @NotNull UUID inventoryItemId,
    @NotNull @Positive Double amount,
    @Nullable List<@Valid AllocationReductionDto> missionReductions) {}
