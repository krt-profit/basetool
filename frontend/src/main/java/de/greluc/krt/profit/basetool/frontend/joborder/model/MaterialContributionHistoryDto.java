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
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Readable current and historical facts of one material-quality contribution.
 *
 * @param id contribution identity
 * @param version observed contribution version
 * @param participationId the single stable Staffel participation
 * @param supporting whether that participation still supports the order
 * @param claimingOrgUnit the contributing Staffel
 * @param materialId required material identity
 * @param materialName required material display name
 * @param quantityType the material quantity unit
 * @param qualityTierCode immutable requirement tier code
 * @param minQuality the requirement quality floor
 * @param openAmount the currently outstanding promise, zero when ended
 * @param creditedAmount truthful historical provision credit
 * @param endedAt promise ending instant, if ended
 * @param changes ordered facts with their distinct causes
 * @param provisions explicitly attributed provision facts
 */
@DtoMirror
public record MaterialContributionHistoryDto(
    UUID id,
    Long version,
    UUID participationId,
    boolean supporting,
    SquadronReferenceDto claimingOrgUnit,
    UUID materialId,
    String materialName,
    String quantityType,
    String qualityTierCode,
    Integer minQuality,
    double openAmount,
    double creditedAmount,
    @Nullable Instant endedAt,
    List<MaterialContributionChange> changes,
    List<MaterialProvisionCreditDto> provisions) {}
