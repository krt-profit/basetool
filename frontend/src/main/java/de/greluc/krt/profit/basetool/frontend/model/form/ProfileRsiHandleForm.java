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

package de.greluc.krt.profit.basetool.frontend.model.form;

import jakarta.validation.constraints.Pattern;

/**
 * Form-binding object for the member's own RSI handle on the profile page (REQ-SEC-072).
 *
 * @param rsiHandle the handle; empty clears it, otherwise 3 to 60 letters, digits, underscores or
 *     hyphens
 * @param version the optimistic-lock version of the user row, echoed back so a concurrent edit
 *     surfaces as a 409 rather than a silent overwrite
 */
public record ProfileRsiHandleForm(
    @Pattern(
            regexp = "^\\s*$|^\\s*[A-Za-z0-9_-]{3,60}\\s*$",
            message = "{profile.rsiHandle.invalid}")
        String rsiHandle,
    Long version) {}
