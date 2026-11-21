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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The part of one material handover item credited to one requirement line (ADR-0400).
 *
 * @param lineId the credited {@link JobOrderMaterial} of the same order and material
 * @param amount the credited quantity, positive and at SCU scale
 */
@Embeddable
public record JobOrderHandoverCredit(
    @NotNull @Column(name = "job_order_material_id", nullable = false) UUID lineId,
    @Column(name = "amount", nullable = false) double amount) {}
