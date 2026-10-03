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

import static com.tngtech.archunit.library.modules.syntax.ModuleRuleDefinition.modules;

import com.tngtech.archunit.base.DescribedFunction;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.modules.ArchModule;
import com.tngtech.archunit.library.modules.ModuleDependency;
import com.tngtech.archunit.library.modules.syntax.DescriptorFunction;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;

/**
 * The ArchUnit {@code modules()} rule over the domain map: a module depends only on modules of
 * lower rank and on the same-rank modules its {@code allow} rows name (REQ-MOD-003).
 *
 * <p>Each violation is one class edge, written as {@code from -> to: FromClass -> ToClass} with
 * simple names, so a frozen line survives a package move and line-number churn.
 */
public final class ModuleDependencyRule {

  /** Description of the backend's rule; it keys the frozen baseline. */
  public static final String DESCRIPTION = "backend modules respect the ranks of the domain map";

  private ModuleDependencyRule() {}

  /**
   * Builds the rule for a domain map over a given class set.
   *
   * @param map the domain map that assigns modules and ranks
   * @param classes the imported classes the rule will be evaluated on, used to fold nested and
   *     generated classes onto their source type
   * @param description the rule description, which keys a frozen baseline
   * @return the unfrozen rule
   */
  public static @NotNull ArchRule rule(
      @NotNull DomainMap map, @NotNull JavaClasses classes, @NotNull String description) {
    DescribedFunction<JavaClass, ArchModule.Identifier> identifier =
        DescribedFunction.describe(
            "the domain map",
            javaClass ->
                map.ruleFor(ModuleSubjects.subjectOf(javaClass, classes).getName())
                    .map(rule -> ArchModule.Identifier.from(rule.module()))
                    .orElseGet(ArchModule.Identifier::ignore));
    return modules()
        .definedBy(identifier)
        .derivingModule(
            DescriptorFunction.describe(
                "named after the module",
                (id, contained) -> ArchModule.Descriptor.create(id.getPart(1))))
        .should(respectRanks(map, classes))
        .as(description);
  }

  private static ArchCondition<ArchModule<ArchModule.Descriptor>> respectRanks(
      DomainMap map, JavaClasses classes) {
    return new ArchCondition<>("depend only on lower-ranked or explicitly allowed modules") {
      @Override
      public void check(ArchModule<ArchModule.Descriptor> module, ConditionEvents events) {
        for (ModuleDependency<ArchModule.Descriptor> dependency :
            module.getModuleDependenciesFromSelf()) {
          String from = dependency.getOrigin().getName();
          String to = dependency.getTarget().getName();
          if (map.mayDependOn(from, to)) {
            continue;
          }
          TreeSet<String> edges = new TreeSet<>();
          for (Dependency classDependency : dependency.toClassDependencies()) {
            edges.add(
                ModuleSubjects.subjectOf(classDependency.getOriginClass(), classes).getSimpleName()
                    + " -> "
                    + ModuleSubjects.subjectOf(
                            classDependency.getTargetClass().getBaseComponentType(), classes)
                        .getSimpleName());
          }
          for (String edge : edges) {
            events.add(SimpleConditionEvent.violated(dependency, from + " -> " + to + ": " + edge));
          }
        }
      }
    };
  }
}
