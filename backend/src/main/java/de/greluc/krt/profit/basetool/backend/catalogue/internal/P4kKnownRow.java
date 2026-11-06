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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A row the P4K knows (its {@code p4k_uuid} is set), as the sweep reads it (REQ-P4K-011).
 *
 * @param id the row
 * @param guid its P4K GUID
 * @param className its class name, or {@code null}
 * @param name its name
 * @param stamped whether the sweep stamped it already
 * @param stillLiveElsewhere whether UEX or the SC Wiki still list it as live (REQ-P4K-013)
 */
public record P4kKnownRow(
    @NotNull UUID id,
    @NotNull UUID guid,
    @Nullable String className,
    @Nullable String name,
    boolean stamped,
    boolean stillLiveElsewhere) {}
