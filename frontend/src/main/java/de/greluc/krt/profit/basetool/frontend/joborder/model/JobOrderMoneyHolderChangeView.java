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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Participant-visible holder identity history.
 *
 * @param id the change identifier
 * @param kind the recorded change reason
 * @param holderId the original holder
 * @param targetHolderId the surviving holder after a merge, if applicable
 * @param previousName the visible label before the change
 * @param name the visible resulting label
 * @param recordedAt the correction time
 * @param actorName the visible recorder label
 */
@DtoMirror
public record JobOrderMoneyHolderChangeView(
    UUID id,
    @BackendEnumAsString String kind,
    UUID holderId,
    @Nullable UUID targetHolderId,
    @Nullable String previousName,
    @Nullable String name,
    Instant recordedAt,
    @Nullable String actorName) {}
