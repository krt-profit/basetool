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

import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.ItemPickerOption;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Builds the option labels of the game-item pickers in the request's locale (REQ-INV-064).
 *
 * <p>A ship component reads {@code name · S1 · A · Militär}, with the same messages as the Lager
 * chips (REQ-INV-061, REQ-INV-062); a missing value or a class without a label is left out. Every
 * other item reads its plain name.
 */
@Component
@RequiredArgsConstructor
public class ItemPickerLabels {

  private static final String SEPARATOR = " · ";

  /** The bundles holding the size, grade and class messages. */
  private final MessageSource messageSource;

  /**
   * Maps a search result page to picker options.
   *
   * @param page the backend page, {@code null} when the search failed
   * @return one option per item in result order, empty for a missing page or content
   */
  @NotNull
  @Unmodifiable
  public List<ItemPickerOption> options(
      @Nullable PageResponse<InventoryGameItemReferenceDto> page) {
    if (page == null || page.content() == null) {
      return List.of();
    }
    return options(page.content());
  }

  /**
   * Maps search results to picker options.
   *
   * @param items the game-item references, in result order
   * @return one option per item, in the same order
   */
  @NotNull
  @Unmodifiable
  public List<ItemPickerOption> options(@NotNull List<InventoryGameItemReferenceDto> items) {
    return items.stream()
        .map(item -> new ItemPickerOption(item.id(), item.name(), label(item)))
        .toList();
  }

  /**
   * Builds one item's option label.
   *
   * @param item the game-item reference
   * @return the label, the plain name for a non-component
   */
  public String label(@NotNull InventoryGameItemReferenceDto item) {
    if (item.componentType() == null) {
      return item.name();
    }
    Locale locale = LocaleContextHolder.getLocale();
    List<String> parts = new ArrayList<>();
    parts.add(item.name());
    String sizeGrade = sizeGrade(item, locale);
    if (sizeGrade != null) {
      parts.add(sizeGrade);
    }
    if (item.itemClass() != null) {
      String classLabel =
          messageSource.getMessage(
              "inventory.items.itemClass." + item.itemClass(), null, null, locale);
      if (classLabel != null) {
        parts.add(classLabel);
      }
    }
    return String.join(SEPARATOR, parts);
  }

  @Nullable
  private String sizeGrade(InventoryGameItemReferenceDto item, Locale locale) {
    if (item.size() != null && item.grade() != null) {
      return messageSource.getMessage(
          "inventory.items.sizeGrade", new Object[] {item.size(), item.grade()}, locale);
    }
    if (item.size() != null) {
      return messageSource.getMessage("inventory.items.size", new Object[] {item.size()}, locale);
    }
    if (item.grade() != null) {
      return messageSource.getMessage("inventory.items.grade", new Object[] {item.grade()}, locale);
    }
    return null;
  }
}
