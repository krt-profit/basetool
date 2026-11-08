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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The P4K's own values of the game-item attributes it leads (REQ-P4K-017…019, ADR-0337), as the
 * {@code p4k_*} columns hold them.
 *
 * @param name the English name, or {@code null}
 * @param manufacturer the manufacturer the catalogue's manufacturer GUID resolves to, or {@code
 *     null}
 * @param type the item {@code type}, or {@code null}
 * @param subType the item {@code subType}, or {@code null}
 * @param size the item {@code size}, or {@code null}
 * @param gradeRaw the raw DataForge grade, or {@code null}
 * @param itemClass the component class, or {@code null} when absent or unknown
 * @param mass the mass in kg, or {@code null}
 */
record P4kItemValues(
    @Nullable String name,
    @Nullable Manufacturer manufacturer,
    @Nullable String type,
    @Nullable String subType,
    @Nullable Integer size,
    @Nullable Integer gradeRaw,
    @Nullable ShipComponentClass itemClass,
    @Nullable Double mass) {

  /** No P4K value at all, the state of a row the P4K never matched. */
  static final P4kItemValues NONE =
      new P4kItemValues(null, null, null, null, null, null, null, null);

  /**
   * Reads the values a row's {@code p4k_*} columns hold.
   *
   * @param row the row
   * @return the row's P4K values
   */
  @NotNull
  static P4kItemValues of(@NotNull GameItem row) {
    return new P4kItemValues(
        row.getP4kName(),
        row.getP4kManufacturer(),
        row.getP4kType(),
        row.getP4kSubType(),
        row.getP4kSize(),
        row.getP4kGradeRaw(),
        row.getP4kItemClass(),
        row.getP4kMass());
  }

  /**
   * Reduces a catalogue name to one the Basetool displays.
   *
   * @param nameEn the catalogue's English name
   * @return the stripped name, or {@code null} when blank, an unresolved key or an engine
   *     placeholder such as {@code <= PLACEHOLDER =>} ({@link P4kDisplayName#isReal})
   */
  @Nullable
  static String usableName(@Nullable String nameEn) {
    String name = StringNormalization.blankToNull(nameEn);
    if (!P4kDisplayName.isReal(name) || name.contains("<") || name.contains(">")) {
      return null;
    }
    return name.strip();
  }

  /**
   * Gives the grade letter these values lead with.
   *
   * @return {@code A} to {@code D} on one of the five mapped component types, else {@code null}
   */
  @Nullable
  String gradeLetter() {
    return P4kGradeMapping.letter(type, gradeRaw);
  }

  /**
   * Writes the values into a row's {@code p4k_*} columns, {@code null}s included.
   *
   * @param row the row
   */
  void storeOn(@NotNull GameItem row) {
    row.setP4kName(name);
    row.setP4kManufacturer(manufacturer);
    row.setP4kType(type);
    row.setP4kSubType(subType);
    row.setP4kSize(size);
    row.setP4kGradeRaw(gradeRaw);
    row.setP4kItemClass(itemClass);
    row.setP4kMass(mass);
  }

  /**
   * Writes every non-null value over a row's displayed column; a {@code null} keeps the column.
   *
   * @param row the row
   */
  void leadOnto(@NotNull GameItem row) {
    if (name != null) {
      row.setName(name);
    }
    if (manufacturer != null) {
      row.setManufacturer(manufacturer);
    }
    if (type != null) {
      row.setWikiType(type);
    }
    if (subType != null) {
      row.setWikiSubType(subType);
    }
    if (size != null) {
      row.setSizeClass(size);
    }
    String letter = gradeLetter();
    if (letter != null) {
      row.setGrade(letter);
    }
    if (itemClass != null) {
      row.setItemClass(itemClass);
    }
    if (mass != null) {
      row.setMass(mass);
    }
  }
}
