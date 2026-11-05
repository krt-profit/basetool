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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kManufacturerDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code manufacturers} with {@code manufacturer}: match by P4K GUID, SC
 * Wiki UUID, abbreviation ({@code code}) and name, fill the empty code and description, stamp, and
 * index each match for the items and ships of the run. An unmatched manufacturer creates nothing.
 */
@Component
@RequiredArgsConstructor
public class P4kManufacturerReconciler {

  private final ManufacturerRepository manufacturerRepository;

  /**
   * Reconciles every manufacturer.
   *
   * @param manufacturers the catalogue's manufacturers
   * @param context the run
   */
  public void reconcile(
      @NotNull List<P4kManufacturerDto> manufacturers, @NotNull P4kImportContext context) {
    P4kRowMatcher<Manufacturer> matcher =
        new P4kRowMatcher<>(
            P4kKind.MANUFACTURER,
            manufacturerRepository::findByP4kUuid,
            manufacturerRepository::findByScwikiUuid,
            Manufacturer::getScwikiUuid,
            "scwiki_uuid",
            code ->
                manufacturerRepository
                    .findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc(code)
                    .map(List::of)
                    .orElseGet(List::of),
            name ->
                manufacturerRepository.findByNameIgnoreCase(name).map(List::of).orElseGet(List::of),
            _ -> false);
    for (P4kManufacturerDto manufacturer : manufacturers) {
      P4kRowMatcher.Match<Manufacturer> match =
          matcher.match(manufacturer, manufacturer.code(), manufacturer.nameEn(), false, context);
      if (match instanceof P4kRowMatcher.Matched<Manufacturer>(Manufacturer row, UUID guid)) {
        boolean apply = context.isApply();
        boolean enriched =
            P4kFields.fillIfNull(
                row.getScwikiCode(), manufacturer.code(), row::setScwikiCode, apply);
        enriched |=
            P4kFields.fillIfNull(
                row.getDescription(),
                P4kLocalisedText.enOf(manufacturer.description()),
                row::setDescription,
                apply);
        context.stamp(row, guid);
        context.indexManufacturer(guid, row);
        context.getReport().matched(P4kKind.MANUFACTURER, enriched);
      } else if (match instanceof P4kRowMatcher.Unmatched<Manufacturer>) {
        context.getReport().unmatched(P4kKind.MANUFACTURER, manufacturer);
      }
    }
  }
}
