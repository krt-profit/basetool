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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Collects the sections every module contributes to the Art. 15 / Art. 20 export (REQ-SEC-058) and
 * puts them in reading order.
 *
 * <p>The registry fails at construction when two sections share a key or a position, so a
 * contribution that would shadow another never reaches a running export.
 */
@Component
public class PersonalDataExportRegistry {

  private final List<Section> sections;

  /**
   * Orders the contributions of all registered participants.
   *
   * @param participants every {@link PersonalDataExportSection} bean
   * @throws IllegalStateException when two sections share a key or a position
   */
  public PersonalDataExportRegistry(@NotNull List<PersonalDataExportSection> participants) {
    this.sections =
        inReadingOrder(participants.stream().flatMap(p -> p.sections().stream()).toList());
  }

  /**
   * Returns every section in the order the export lists them.
   *
   * @return the sections, ascending by position
   */
  public @NotNull @Unmodifiable List<Section> sections() {
    return sections;
  }

  /**
   * Orders sections by position and rejects duplicates.
   *
   * @param declared the sections of all participants, in any order
   * @return the sections, ascending by position
   * @throws IllegalStateException when two sections share a key or a position
   */
  public static @NotNull @Unmodifiable List<Section> inReadingOrder(
      @NotNull List<Section> declared) {
    Set<String> keys = new HashSet<>();
    Set<Integer> positions = new HashSet<>();
    for (Section section : declared) {
      if (!keys.add(section.key())) {
        throw new IllegalStateException("Two export sections share the key " + section.key());
      }
      if (!positions.add(section.position())) {
        throw new IllegalStateException(
            "Two export sections share the position "
                + section.position()
                + ", one of them "
                + section.key());
      }
    }
    return declared.stream().sorted(Comparator.comparingInt(Section::position)).toList();
  }
}
