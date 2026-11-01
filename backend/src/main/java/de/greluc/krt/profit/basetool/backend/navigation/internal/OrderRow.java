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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One stored key of a mode's order: the key sits in a scope, the groups of the mode or the links of
 * one group, in the position its row has in the member's order list.
 *
 * @param mode the {@link NavigationMode#getTag() mode tag}
 * @param scope {@code groups} or the key of the group whose links the key orders
 * @param entryKey the group or link key
 */
@Embeddable
public record OrderRow(
    @Column(name = "mode", nullable = false, length = NavigationLimits.MAX_MODE_LENGTH)
        String mode,
    @Column(name = "scope", nullable = false, length = NavigationLimits.MAX_ENTRY_KEY_LENGTH)
        String scope,
    @Column(name = "entry_key", nullable = false, length = NavigationLimits.MAX_ENTRY_KEY_LENGTH)
        String entryKey) {}
