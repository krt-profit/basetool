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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Manufacturer readiness reads shared source facts once per queue request. */
class P4kManufacturerReadinessTest {

  @Test
  void summarizesSeveralCasesFromOneSnapshotAndKeepsAuxiliaryCasesForInspection() {
    ManufacturerRepository manufacturers = mock(ManufacturerRepository.class);
    ManufacturerScwikiIdentityRepository wikiIdentities =
        mock(ManufacturerScwikiIdentityRepository.class);
    ManufacturerUexCompanyRepository uexCompanies = mock(ManufacturerUexCompanyRepository.class);
    P4kManufacturerReviewActions actions =
        new P4kManufacturerReviewActions(
            manufacturers,
            mock(ManufacturerP4kAliasRepository.class),
            wikiIdentities,
            uexCompanies,
            mock(ManufacturerReviewMerge.class),
            mock(EntityManager.class));
    Manufacturer first = manufacturer("First manufacturer", "FIRST");
    Manufacturer second = manufacturer("Second manufacturer", "SECOND");
    when(manufacturers.findAll()).thenReturn(List.of(first, second));
    String records = "libs/foundry/records/scitemmanufacturer/";
    P4kReviewCaseSource firstCase = review(first, records + "first");
    P4kReviewCaseSource secondCase = review(second, records + "second");
    P4kReviewCaseSource auxiliary = review(first, records + "paintcolorlogos/paint_first");

    assertThat(actions.summarize(List.of(firstCase, secondCase, auxiliary)))
        .containsEntry(firstCase.review().id(), P4kReviewReadiness.ACTIONABLE)
        .containsEntry(secondCase.review().id(), P4kReviewReadiness.ACTIONABLE)
        .containsEntry(auxiliary.review().id(), P4kReviewReadiness.CHECK_REQUIRED);
    verify(manufacturers).findAll();
    verify(wikiIdentities).findAll();
    verify(uexCompanies).findCompanyRefs();
    verifyNoMoreInteractions(manufacturers, wikiIdentities, uexCompanies);
  }

  private static Manufacturer manufacturer(String name, String code) {
    Manufacturer row = new Manufacturer();
    row.setId(UUID.randomUUID());
    row.setName(name);
    row.setAbbreviation(code);
    row.setP4kUuid(UUID.randomUUID());
    row.setScwikiUuid(UUID.randomUUID());
    return row;
  }

  private static P4kReviewCaseSource review(Manufacturer row, String recordPath) {
    UUID run = UUID.randomUUID();
    P4kReviewCaseDto review =
        new P4kReviewCaseDto(
            UUID.randomUUID(),
            0,
            P4kKind.MANUFACTURER,
            row.getScwikiUuid(),
            P4kReviewReason.GUID_CONFLICT,
            P4kReviewStatus.OPEN,
            row.getAbbreviation(),
            row.getName(),
            row.getId(),
            row.getName(),
            null,
            run,
            run,
            null,
            null,
            null);
    return new P4kReviewCaseSource(review, recordPath);
  }
}
