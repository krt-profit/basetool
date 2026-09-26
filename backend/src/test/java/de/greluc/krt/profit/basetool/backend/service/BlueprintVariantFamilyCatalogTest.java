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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductRow;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests {@link BlueprintVariantFamilyCatalog}: the active blueprint master is grouped into {@code
 * familyKey -> product keys}, collapsing a base and its cosmetic variants into one family, keeping
 * magazines atomic, skipping nameless rows, and returning a deeply-immutable index.
 */
@ExtendWith(MockitoExtension.class)
class BlueprintVariantFamilyCatalogTest {

  @Mock private BlueprintRepository blueprintRepository;

  private final BlueprintVariantFamilyResolver resolver =
      new BlueprintVariantFamilyResolver(
          new BlueprintNameNormalizer(), new BlueprintVariantAliasOverrides());

  private BlueprintVariantFamilyCatalog catalog;

  @BeforeEach
  void setUp() {
    catalog =
        new BlueprintVariantFamilyCatalog(
            blueprintRepository, new BlueprintNameNormalizer(), resolver);
  }

  private static BlueprintProductRow row(String outputName) {
    return new BlueprintProductRow(outputName, null, null, null);
  }

  @Test
  void groupsBaseAndVariantsIntoOneFamily_keepsMagazineAtomic_skipsNamelessRows() {
    when(blueprintRepository.findActiveProductRows(""))
        .thenReturn(
            List.of(
                row("Fresnel Energy LMG"),
                row("Fresnel \"Molten\" Energy LMG"),
                row("Fresnel \"Rockfall\" Energy LMG"),
                row("Fresnel Energy LMG Magazine (200 cap)"),
                row(null),
                row("   ")));

    Map<String, Set<String>> index = catalog.familyIndex();

    assertEquals(
        Set.of(
            "fresnel energy lmg",
            "fresnel \"molten\" energy lmg",
            "fresnel \"rockfall\" energy lmg"),
        index.get("fresnel energy lmg"));
    String magazineFamily = resolver.familyKey("Fresnel Energy LMG Magazine (200 cap)");
    assertEquals(Set.of("fresnel energy lmg magazine (200 cap)"), index.get(magazineFamily));
    assertTrue(index.keySet().stream().noneMatch(String::isEmpty));
  }

  @Test
  void index_isDeeplyImmutable() {
    when(blueprintRepository.findActiveProductRows("")).thenReturn(List.of(row("Aurora MR")));

    Map<String, Set<String>> index = catalog.familyIndex();

    assertThrows(UnsupportedOperationException.class, () -> index.put("x", Set.of()));
    assertThrows(UnsupportedOperationException.class, () -> index.get("aurora mr").add("intruder"));
  }
}
