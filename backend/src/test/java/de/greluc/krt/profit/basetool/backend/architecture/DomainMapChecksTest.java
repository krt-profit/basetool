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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the G-09 checks and the domain-map parser report what they guard against, using the
 * planted classes under {@code architecturefixture.domainmap}.
 */
class DomainMapChecksTest {

  /** Root package of the planted fixture classes. */
  static final String FIXTURE_PACKAGE =
      "de.greluc.krt.profit.basetool.architecturefixture.domainmap";

  /** A fixture map that leaves {@code Orphan} unassigned and holds one dead rule. */
  static final String FIXTURE_MAP =
      String.join(
          "\n",
          "base " + FIXTURE_PACKAGE,
          "module low 0",
          "module high 1",
          "module spare 1",
          "class NeverPresent spare a rule that matches nothing",
          "layer low low",
          "layer high high",
          "layer one low",
          "layer two low");

  /** The planted fixture classes. */
  static final JavaClasses FIXTURE_CLASSES =
      new ClassFileImporter().importPackages(FIXTURE_PACKAGE);

  private static final DomainMap MAP = DomainMap.parse("fixture", FIXTURE_MAP);

  @Test
  void reportsAClassNoRuleAssigns() {
    List<String> unassigned =
        DomainMapChecks.unassigned(MAP, ModuleSubjects.subjects(FIXTURE_CLASSES));

    assertThat(unassigned)
        .singleElement()
        .asString()
        .startsWith(FIXTURE_PACKAGE + ".orphan.Orphan is in no module")
        .contains(DomainMap.SOURCE_PATH);
  }

  @Test
  void reportsARuleThatAssignsNothing() {
    assertThat(DomainMapChecks.deadRules(MAP, ModuleSubjects.subjects(FIXTURE_CLASSES)))
        .extracting(DomainMap.Rule::pattern)
        .containsExactly("NeverPresent");
  }

  @Test
  void reportsAModuleWithoutClasses() {
    assertThat(DomainMapChecks.emptyModules(MAP, ModuleSubjects.subjects(FIXTURE_CLASSES)))
        .containsExactly("spare");
  }

  @Test
  void reportsTwoClassesSharingASimpleName() {
    assertThat(
            DomainMapChecks.duplicateSimpleNames(
                ModuleSubjects.topLevelClasses(FIXTURE_CLASSES).toList()))
        .containsOnlyKeys("Duplicate")
        .containsEntry(
            "Duplicate",
            List.of(FIXTURE_PACKAGE + ".one.Duplicate", FIXTURE_PACKAGE + ".two.Duplicate"));
  }

  @Test
  void theFirstMatchingRuleWins() {
    DomainMap map =
        DomainMap.parse(
            "first-match",
            String.join(
                "\n",
                "base " + FIXTURE_PACKAGE,
                "module low 0",
                "module high 1",
                "class LowService high explicit entry",
                "name ^Low low"));

    assertThat(map.ruleFor(FIXTURE_PACKAGE + ".low.LowService"))
        .hasValueSatisfying(rule -> assertThat(rule.module()).isEqualTo("high"));
    assertThat(map.ruleFor(FIXTURE_PACKAGE + ".low.LowServiceImpl"))
        .hasValueSatisfying(rule -> assertThat(rule.module()).isEqualTo("low"));
    assertThat(map.ruleFor("org.example.LowService")).isEmpty();
  }

  @Test
  void ranksAndAllowRowsDecideWhichDependencyIsAllowed() {
    DomainMap map =
        DomainMap.parse(
            "ranks",
            String.join(
                "\n",
                "base " + FIXTURE_PACKAGE,
                "module a 0",
                "module b 1",
                "module c 1",
                "allow b c",
                "layer x a"));

    assertThat(map.mayDependOn("b", "a")).isTrue();
    assertThat(map.mayDependOn("a", "b")).isFalse();
    assertThat(map.mayDependOn("b", "c")).isTrue();
    assertThat(map.mayDependOn("c", "b")).isFalse();
    assertThat(map.mayDependOn("a", "a")).isTrue();
  }

  @Test
  void rejectsAMalformedMap() {
    String base = "base " + FIXTURE_PACKAGE + "\nmodule a 0\nmodule b 1\nmodule c 1\n";

    assertThatThrownBy(() -> DomainMap.parse("m", base + "frobnicate a b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("known directive");
    assertThatThrownBy(() -> DomainMap.parse("m", base + "layer x unknown"))
        .hasMessageContaining("declared module");
    assertThatThrownBy(() -> DomainMap.parse("m", base + "class X a\nclass X b"))
        .hasMessageContaining("one class rule per class");
    assertThatThrownBy(() -> DomainMap.parse("m", base + "allow a b"))
        .hasMessageContaining("same rank");
    assertThatThrownBy(() -> DomainMap.parse("m", base + "allow b c\nallow c b"))
        .hasMessageContaining("both directions");
    assertThatThrownBy(() -> DomainMap.parse("m", base + "module a 2"))
        .hasMessageContaining("declared once");
    assertThatThrownBy(() -> DomainMap.parse("m", base + "name ^( a"))
        .hasMessageContaining("invalid pattern");
    assertThatThrownBy(() -> DomainMap.parse("m", "module a 0"))
        .hasMessageContaining("'base <package>'");
  }
}
