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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource.Target;
import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * The union of what every {@link PersonMentionSource} on the classpath declares, for the tests that
 * hold the person search and its neighbours against the schema and the other registries.
 *
 * <p>The sources are found by scanning, not listed, so a module that adds one is covered without
 * touching a test. Sources are pure declarations and are created through their default constructor.
 */
public final class MentionSourceDeclarations {

  private static final String BASE_PACKAGE = "de.greluc.krt.profit.basetool.backend";

  private static final List<PersonMentionSource> SOURCES = scan();

  /** Not instantiable: static views over the scanned sources. */
  private MentionSourceDeclarations() {}

  /**
   * Returns every source on the classpath.
   *
   * @return the sources, ordered by class name
   */
  public static @NotNull @Unmodifiable List<PersonMentionSource> sources() {
    return SOURCES;
  }

  /**
   * Returns the searched columns of all sources, ordered as the search orders them.
   *
   * @return the targets
   * @throws IllegalStateException when two sources search the same column
   */
  public static @NotNull @Unmodifiable List<Target> targets() {
    return PersonMentionRegistry.ordered(
        SOURCES.stream().flatMap(s -> s.targets().stream()).toList());
  }

  /**
   * Returns the text columns no source searches on purpose, as {@code table.column}.
   *
   * @return the exempt columns of all sources
   */
  public static @NotNull @Unmodifiable Set<String> exemptColumns() {
    return union(PersonMentionSource::exemptColumns);
  }

  /**
   * Returns the exempt columns that can still hold a name, as {@code table.column}.
   *
   * @return the name-bearing exemptions of all sources
   */
  public static @NotNull @Unmodifiable Set<String> exemptButMayHoldNames() {
    return union(PersonMentionSource::exemptButMayHoldNames);
  }

  /**
   * Returns the tables whose every text column is exempt.
   *
   * @return the exempt tables of all sources
   */
  public static @NotNull @Unmodifiable Set<String> exemptTables() {
    return union(PersonMentionSource::exemptTables);
  }

  private static Set<String> union(Function<PersonMentionSource, Set<String>> declared) {
    Set<String> out = new TreeSet<>();
    SOURCES.forEach(source -> out.addAll(declared.apply(source)));
    return Set.copyOf(out);
  }

  private static List<PersonMentionSource> scan() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(PersonMentionSource.class));
    return scanner.findCandidateComponents(BASE_PACKAGE).stream()
        .map(BeanDefinition::getBeanClassName)
        .sorted(Comparator.naturalOrder())
        .map(MentionSourceDeclarations::instantiate)
        .toList();
  }

  private static PersonMentionSource instantiate(String className) {
    try {
      Constructor<?> constructor = Class.forName(className).getDeclaredConstructor();
      constructor.setAccessible(true);
      return (PersonMentionSource) constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot create the person-mention source " + className, e);
    }
  }
}
