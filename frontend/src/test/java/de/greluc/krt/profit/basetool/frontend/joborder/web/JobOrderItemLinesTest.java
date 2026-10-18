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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ItemDerivationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SubAssemblySuggestionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ItemDerivationView;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.SubAssemblyOption;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ItemPickerLabels;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Unit tests for {@link JobOrderItemLines}, the labelled item-line data (REQ-INV-064). */
class JobOrderItemLinesTest {

  private static final GameItemReferenceDto SHIELD =
      new GameItemReferenceDto(
          UUID.randomUUID(), "FR-66", "VEHICLE_ITEM", "SHIELD", 1, "A", "MILITARY");
  private static final GameItemReferenceDto RIFLE =
      new GameItemReferenceDto(UUID.randomUUID(), "P4-AR", "WEAPON");
  private static final BlueprintReferenceDto BLUEPRINT =
      new BlueprintReferenceDto(UUID.randomUUID(), "FR-66", "fr-66");

  private JobOrderItemLines lines;

  @BeforeEach
  void setUp() {
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    lines = new JobOrderItemLines(new ItemPickerLabels(messageSource));
    LocaleContextHolder.setLocale(Locale.GERMAN);
  }

  @AfterEach
  void tearDown() {
    LocaleContextHolder.resetLocaleContext();
  }

  private static JobOrderItemDto line(UUID id, GameItemReferenceDto item, UUID parentId) {
    return new JobOrderItemDto(id, item, BLUEPRINT, 2, 1, 0, parentId, List.of(), false, 0L);
  }

  @Test
  void editPrefill_labelsAComponentAndKeepsThePlainNameOfAnyOtherItem() {
    UUID parentId = UUID.randomUUID();
    List<Map<String, Object>> prefill =
        lines.editPrefill(
            List.of(line(parentId, SHIELD, null), line(UUID.randomUUID(), RIFLE, parentId)));

    assertThat(prefill)
        .extracting(entry -> entry.get("gameItemName"))
        .containsExactly("FR-66 · S1 · A · Militär", "P4-AR");
    assertThat(prefill.get(1)).containsEntry("parentId", 0).containsEntry("manufactured", 1);
  }

  @Test
  void editPrefill_ofALineWithoutItem_hasNoName() {
    Map<String, Object> entry =
        lines.editPrefill(List.of(line(UUID.randomUUID(), null, null))).get(0);

    assertThat(entry).containsEntry("gameItemId", null).containsEntry("gameItemName", null);
  }

  @Test
  void derivation_labelsEachSubAssembly() {
    ItemDerivationDto derivation =
        new ItemDerivationDto(
            BLUEPRINT,
            1,
            List.of(),
            List.of(
                new SubAssemblySuggestionDto(SHIELD, 2, List.of()),
                new SubAssemblySuggestionDto(RIFLE, 1, List.of())),
            List.of("Unknown"));

    ItemDerivationView view = lines.derivation(derivation);

    assertThat(view.subAssemblies())
        .extracting(SubAssemblyOption::label)
        .containsExactly("FR-66 · S1 · A · Militär", "P4-AR");
    assertThat(view.subAssemblies().get(0).gameItem()).isEqualTo(SHIELD);
    assertThat(view.unresolvedIngredients()).containsExactly("Unknown");
    assertThat(view.blueprint()).isEqualTo(BLUEPRINT);
  }

  @Test
  void derivation_ofAMissingPreview_isNull() {
    assertThat(lines.derivation(null)).isNull();
  }
}
