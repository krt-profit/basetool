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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;

/**
 * A catalogue target with the evidence and versions shown for a review action.
 *
 * @param action the operation offered for the target
 * @param targetId the target catalogue row
 * @param targetVersion the target version
 * @param subjectVersion the version of the row to change, when distinct from the target
 * @param name the target display name
 * @param className the target catalogue class
 * @param p4kGuid the target's current P4K GUID
 * @param scwikiUuid the target's SC Wiki UUID
 * @param uexIds the target's UEX identifiers
 * @param evidence the match and conflict signals for the target
 */
@DtoMirror
public record P4kReviewCandidate(
    @BackendEnumAsString String action,
    String targetId,
    Long targetVersion,
    Long subjectVersion,
    String name,
    String className,
    String p4kGuid,
    String scwikiUuid,
    List<Integer> uexIds,
    List<String> evidence) {}
