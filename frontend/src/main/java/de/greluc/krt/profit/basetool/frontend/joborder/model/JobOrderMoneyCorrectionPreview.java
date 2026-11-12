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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Projected result of one connected correction at its observed basis.
 *
 * @param corrections before and after effects of each corrected original
 * @param holdings resulting order holdings
 * @param provided resulting recorded new funds
 * @param netHandedOver resulting effective net handover
 * @param fees resulting order-funded fees
 * @param linkedHolderVersions observed revisions of holders on the paired order
 * @param linkedHoldings resulting holdings on the paired order, if applicable
 */
@DtoMirror
public record JobOrderMoneyCorrectionPreview(
    List<JobOrderMoneyCorrectionView> corrections,
    String holdings,
    String provided,
    String netHandedOver,
    String fees,
    Map<UUID, Long> linkedHolderVersions,
    @Nullable String linkedHoldings) {}
