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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.Nullable;

/**
 * Creates a partner (REQ-PUR-004). An individual is kept as an in-game handle with a reputation
 * note only; no contact person, no Discord id, no real name.
 *
 * @param kind organisation or individual
 * @param name the organisation's name or the individual's handle
 * @param orgTag the organisation's tag, or {@code null}
 * @param channel how the partner is reached
 * @param conditionsNote the conditions note, or {@code null}
 * @param reputationNote the reputation note, or {@code null}
 * @param contactVia how the contact was made
 */
public record CreatePartnerRequest(
    @NotNull PartnerKind kind,
    @NotBlank @Size(max = 120) String name,
    @Nullable @Size(max = 16) String orgTag,
    @NotNull PartnerChannel channel,
    @Nullable @Size(max = 500) String conditionsNote,
    @Nullable @Size(max = 500) String reputationNote,
    @NotNull PartnerContact contactVia) {}
