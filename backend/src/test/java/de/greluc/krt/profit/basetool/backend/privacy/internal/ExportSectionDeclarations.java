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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import de.greluc.krt.profit.basetool.backend.privacy.api.PersonalDataExportRegistry;
import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * The union of what every {@link PersonalDataExportSection} on the classpath declares, for the
 * tests that hold the export against the schema and the other registries.
 *
 * <p>The participants are found by scanning, not listed, so a module that adds one is covered
 * without touching a test. Participants are pure declarations and are created through their default
 * constructor.
 */
public final class ExportSectionDeclarations {

  private static final String BASE_PACKAGE = "de.greluc.krt.profit.basetool.backend";

  private static final List<PersonalDataExportSection> PARTICIPANTS = scan();

  /** Not instantiable: static views over the scanned participants. */
  private ExportSectionDeclarations() {}

  /**
   * Returns every participant on the classpath.
   *
   * @return the participants, ordered by class name
   */
  public static @NotNull @Unmodifiable List<PersonalDataExportSection> participants() {
    return PARTICIPANTS;
  }

  /**
   * Returns the sections of all participants in the order the export lists them.
   *
   * @return the sections, ascending by position
   */
  public static @NotNull @Unmodifiable List<Section> inReadingOrder() {
    return inReadingOrder(PARTICIPANTS);
  }

  /**
   * Returns the sections of the given participants in the order the export lists them.
   *
   * @param participants the participants to union
   * @return the sections, ascending by position
   * @throws IllegalStateException when two sections share a key or a position
   */
  public static @NotNull @Unmodifiable List<Section> inReadingOrder(
      @NotNull List<PersonalDataExportSection> participants) {
    return PersonalDataExportRegistry.inReadingOrder(
        participants.stream().flatMap(p -> p.sections().stream()).toList());
  }

  /**
   * Looks one section up by its key.
   *
   * @param key the section key
   * @return the section
   * @throws java.util.NoSuchElementException when no participant declares the key
   */
  public static @NotNull Section section(@NotNull String key) {
    return inReadingOrder().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
  }

  private static List<PersonalDataExportSection> scan() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(PersonalDataExportSection.class));
    return scanner.findCandidateComponents(BASE_PACKAGE).stream()
        .map(BeanDefinition::getBeanClassName)
        .sorted(Comparator.naturalOrder())
        .map(ExportSectionDeclarations::instantiate)
        .toList();
  }

  private static PersonalDataExportSection instantiate(String className) {
    try {
      Constructor<?> constructor = Class.forName(className).getDeclaredConstructor();
      constructor.setAccessible(true);
      return (PersonalDataExportSection) constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot create the export participant " + className, e);
    }
  }
}
