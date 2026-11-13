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

/**
 * A group of open review cases with one case to show when the group has a single member.
 *
 * @param id the opaque group identifier
 * @param kind the catalogue kind of the cases
 * @param reason the common review reason
 * @param name the group label
 * @param readiness the evaluated readiness of the group
 * @param contextPath the shared source path, when available
 * @param count the number of open cases in the group
 * @param batchable whether selected members can be resolved together
 * @param representative a case in the group
 */
@DtoMirror
public record P4kReviewGroupDto(
    String id,
    @BackendEnumAsString String kind,
    @BackendEnumAsString String reason,
    String name,
    @BackendEnumAsString String readiness,
    String contextPath,
    int count,
    boolean batchable,
    P4kReviewCaseDto representative) {}
