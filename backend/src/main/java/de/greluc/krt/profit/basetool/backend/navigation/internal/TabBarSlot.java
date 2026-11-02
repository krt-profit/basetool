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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * One stored slot of a phone tab bar: the entry key chosen for a slot of a mode. A slot without a
 * row is filled automatically.
 *
 * @param mode the navigation mode whose bar holds the slot
 * @param slotIndex the zero-based position, below {@link NavigationLimits#TAB_BAR_SLOTS}
 * @param entryKey the entry key chosen for the slot
 */
@Embeddable
public record TabBarSlot(
    @Enumerated(EnumType.STRING) @Column(name = "mode", nullable = false, length = 16)
        NavigationMode mode,
    @Column(name = "slot_index", nullable = false) int slotIndex,
    @Column(name = "entry_key", nullable = false, length = NavigationLimits.MAX_ENTRY_KEY_LENGTH)
        String entryKey) {}
