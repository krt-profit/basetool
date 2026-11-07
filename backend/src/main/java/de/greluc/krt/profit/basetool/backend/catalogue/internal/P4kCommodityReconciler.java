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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code commodities} with {@code material}: match by P4K GUID, SC Wiki
 * UUID and a name exactly one commodity carries, fill the empty description, refresh the name key
 * and stamp. An unmatched commodity creates nothing; a material an earlier seeding created goes to
 * review (REQ-P4K-010).
 */
@Component
@RequiredArgsConstructor
public class P4kCommodityReconciler {

  private final MaterialRepository materialRepository;

  /**
   * Reconciles every commodity.
   *
   * @param commodities the catalogue's commodities
   * @param context the run
   */
  public void reconcile(
      @NotNull List<P4kCommodityDto> commodities, @NotNull P4kImportContext context) {
    P4kRowMatcher<Material> matcher =
        new P4kRowMatcher<>(
            P4kKind.COMMODITY,
            materialRepository::findByP4kUuid,
            materialRepository::findByScwikiUuid,
            Material::getScwikiUuid,
            "scwiki_uuid",
            _ -> List.of(),
            name -> materialRepository.findByNameIgnoreCase(name).map(List::of).orElseGet(List::of),
            row -> row.getSourceSystems() == MaterialSourceSystem.P4K);
    for (P4kCommodityDto commodity : commodities) {
      P4kRowMatcher.Match<Material> match =
          matcher.match(commodity, null, commodity.nameEn(), false, context);
      if (match instanceof P4kRowMatcher.Matched<Material>(Material row, UUID guid)) {
        P4kRowWriter writer = new P4kRowWriter(context, P4kKind.COMMODITY, commodity);
        writer.fillGameText(
            "description",
            row.getDescription(),
            P4kLocalisedText.enOf(commodity.description()),
            row::setDescription);
        writer.lead(
            "name_de", row.getNameDe(), P4kFields.germanName(commodity.name()), row::setNameDe);
        writer.nameKey(row.getNameKey(), P4kLocalisedText.keyOf(commodity.name()), row::setNameKey);
        context.stamp(P4kKind.COMMODITY, commodity, row, guid);
        context.getReport().matched(P4kKind.COMMODITY, writer.isChanged());
      } else if (match instanceof P4kRowMatcher.Unmatched<Material>) {
        context.getReport().unmatched(P4kKind.COMMODITY, commodity);
      }
    }
  }
}
