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

package de.greluc.krt.profit.basetool.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The checks of guard G-09 over a {@link DomainMap} and a set of classes (REQ-MOD-002).
 *
 * <p>Each check returns its findings instead of failing, so the guard's own tests can show it
 * reports a planted violation.
 */
public final class DomainMapChecks {

  private DomainMapChecks() {}

  /**
   * Lists the classes no rule of the map assigns.
   *
   * @param map the domain map
   * @param subjects the source types to assign
   * @return one line per unassigned class, sorted, naming where to add a rule
   */
  public static @NotNull @Unmodifiable List<String> unassigned(
      @NotNull DomainMap map, @NotNull Collection<JavaClass> subjects) {
    return subjects.stream()
        .filter(subject -> map.ruleFor(subject.getName()).isEmpty())
        .map(
            subject ->
                subject.getName()
                    + " is in no module: add 'class "
                    + subject.getSimpleName()
                    + " <module> <reason>' or a matching name rule to "
                    + DomainMap.SOURCE_PATH)
        .sorted()
        .toList();
  }

  /**
   * Lists the rules that assign no class, because nothing matches them or an earlier rule takes
   * every class they match.
   *
   * @param map the domain map
   * @param subjects the source types to assign
   * @return the dead rules in map order
   */
  public static @NotNull @Unmodifiable List<DomainMap.Rule> deadRules(
      @NotNull DomainMap map, @NotNull Collection<JavaClass> subjects) {
    Set<DomainMap.Rule> used = new HashSet<>();
    for (JavaClass subject : subjects) {
      map.ruleFor(subject.getName()).ifPresent(used::add);
    }
    return map.rules().stream().filter(rule -> !used.contains(rule)).toList();
  }

  /**
   * Lists the declared modules no class is assigned to.
   *
   * @param map the domain map
   * @param subjects the source types to assign
   * @return the empty modules in declaration order
   */
  public static @NotNull @Unmodifiable List<String> emptyModules(
      @NotNull DomainMap map, @NotNull Collection<JavaClass> subjects) {
    Set<String> populated = new HashSet<>();
    for (JavaClass subject : subjects) {
      map.ruleFor(subject.getName()).ifPresent(rule -> populated.add(rule.module()));
    }
    return map.modules().stream().filter(module -> !populated.contains(module)).toList();
  }

  /**
   * Lists the simple names carried by more than one class.
   *
   * @param topLevelClasses the top-level classes to compare
   * @return each repeated simple name with the fully qualified names that share it, sorted
   */
  public static @NotNull Map<String, List<String>> duplicateSimpleNames(
      @NotNull Collection<JavaClass> topLevelClasses) {
    Map<String, List<String>> byName = new TreeMap<>();
    for (JavaClass javaClass : topLevelClasses) {
      byName
          .computeIfAbsent(javaClass.getSimpleName(), key -> new ArrayList<>())
          .add(javaClass.getName());
    }
    byName.values().removeIf(names -> names.size() < 2);
    byName.values().forEach(names -> names.sort(String::compareTo));
    return byName;
  }
}
