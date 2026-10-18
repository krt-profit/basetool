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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.ItemPickerOption;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Unit tests for {@link ItemPickerLabels} over the real message bundles (REQ-INV-064). */
class ItemPickerLabelsTest {

  private static final UUID ID = UUID.randomUUID();

  private ItemPickerLabels labels;

  @BeforeEach
  void setUp() {
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    labels = new ItemPickerLabels(messageSource);
    LocaleContextHolder.setLocale(Locale.GERMAN);
  }

  @AfterEach
  void tearDown() {
    LocaleContextHolder.resetLocaleContext();
  }

  private static InventoryGameItemReferenceDto component(
      Integer size, String grade, String itemClass) {
    return new InventoryGameItemReferenceDto(
        ID, "FR-66", "Gorgon", "SHIP_ITEM", "SHIELD", size, grade, itemClass);
  }

  @Test
  void component_showsSizeGradeAndClass() {
    assertThat(labels.label(component(1, "A", "MILITARY"))).isEqualTo("FR-66 · S1 · A · Militär");
  }

  @Test
  void component_usesTheRequestLocaleForTheClass() {
    LocaleContextHolder.setLocale(Locale.ENGLISH);

    assertThat(labels.label(component(1, "A", "MILITARY"))).isEqualTo("FR-66 · S1 · A · Military");
  }

  @Test
  void component_withSizeOnly_showsTheSize() {
    assertThat(labels.label(component(2, null, null))).isEqualTo("FR-66 · S2");
  }

  @Test
  void component_withGradeOnly_showsTheGrade() {
    assertThat(labels.label(component(null, "B", null))).isEqualTo("FR-66 · Grad B");
  }

  @Test
  void component_withUnknownClass_leavesTheClassOut() {
    assertThat(labels.label(component(1, "A", "EXPERIMENTAL"))).isEqualTo("FR-66 · S1 · A");
  }

  @Test
  void component_withoutAttributes_showsThePlainName() {
    assertThat(labels.label(component(null, null, null))).isEqualTo("FR-66");
  }

  @Test
  void nonComponent_showsThePlainName() {
    assertThat(labels.label(new InventoryGameItemReferenceDto(ID, "P8-SC SMG", null, "WEAPON")))
        .isEqualTo("P8-SC SMG");
  }

  @Test
  void nonComponentWithStrayAttributes_showsThePlainName() {
    InventoryGameItemReferenceDto item =
        new InventoryGameItemReferenceDto(
            ID, "P8-SC SMG", null, "WEAPON", null, 1, "A", "MILITARY");

    assertThat(labels.label(item)).isEqualTo("P8-SC SMG");
  }

  @Test
  void options_ofAMissingPage_isEmpty() {
    assertThat(labels.options((PageResponse<InventoryGameItemReferenceDto>) null)).isEmpty();
  }

  @Test
  void options_keepIdAndNameBesideTheLabel() {
    assertThat(labels.options(List.of(component(1, "A", "MILITARY"))))
        .containsExactly(new ItemPickerOption(ID, "FR-66", "FR-66 · S1 · A · Militär"));
  }
}
