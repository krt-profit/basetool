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

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.freeze.ViolationStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guard G-09's frozen module baseline: module dependencies against the ranks of the domain map,
 * with today's violations recorded and allowed only to shrink (REQ-MOD-003, REQ-MOD-004).
 *
 * <p>The baseline freezes module coupling only. No security rule is ever frozen.
 */
class ModuleBaselineTest {

  private static final JavaClasses CLASSES = ModuleSubjects.importBackendMainClasses();

  private static final DomainMap MAP = DomainMap.load();

  private static final String FIXTURE_DESCRIPTION = "fixture modules respect the ranks";

  private static final String FIXTURE_VIOLATION = "low -> high: LowService -> HighService";

  private static final String STALE_VIOLATION = "low -> high: GoneService -> HighService";

  @Test
  void backendModuleDependenciesStayWithinTheFrozenBaseline() throws IOException {
    ArchRule rule = ModuleDependencyRule.rule(MAP, CLASSES, ModuleDependencyRule.DESCRIPTION);
    ModuleBaselineStore store = new ModuleBaselineStore();

    freeze(rule, store).check(CLASSES);

    List<String> actual = sortedDetails(rule.evaluate(CLASSES));
    assertThat(actual).isNotEmpty();
    assertThat(Files.readAllLines(store.fileFor(rule), StandardCharsets.UTF_8))
        .as("the frozen baseline lists exactly today's violations")
        .isEqualTo(actual);
  }

  @Test
  void archUnitConfigurationNeverCreatesOrRefreezesABaseline() {
    ArchConfiguration configuration = ArchConfiguration.get();
    Properties store = configuration.getSubProperties("freeze.store");

    assertThat(configuration.getProperty("freeze.store"))
        .isEqualTo(ModuleBaselineStore.class.getName());
    assertThat(store.getProperty("default.path"))
        .isEqualTo("src/test/resources/architecture/module-baseline");
    assertThat(store.getProperty("default.allowStoreCreation")).isEqualTo("false");
    assertThat(configuration.getPropertyOrDefault("freeze.refreeze", "true")).isEqualTo("false");
  }

  @Test
  void readsTheStoreKeysOfTheArchUnitConfiguration(@TempDir Path folder) throws IOException {
    ModuleBaselineStore store = new ModuleBaselineStore();
    Properties properties = new Properties();
    properties.setProperty("default.path", folder.toString());
    properties.setProperty("default.allowStoreCreation", "true");
    properties.setProperty("default.allowStoreUpdate", "false");
    store.initialize(properties);
    ArchRule rule = fixtureRule();

    assertThat(store.contains(rule)).isFalse();
    store.save(rule, List.of(FIXTURE_VIOLATION, STALE_VIOLATION));
    assertThat(Files.readString(folder.resolve(ModuleBaselineStore.fileName(FIXTURE_DESCRIPTION))))
        .isEqualTo(STALE_VIOLATION + "\n" + FIXTURE_VIOLATION + "\n");
    assertThat(store.getViolations(rule)).containsExactly(STALE_VIOLATION, FIXTURE_VIOLATION);
    assertThatThrownBy(() -> store.save(rule, List.of(FIXTURE_VIOLATION)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer occur");
  }

  @Test
  void reportsAnUpwardDependencyOfThePlantedFixture() {
    EvaluationResult result = fixtureRule().evaluate(DomainMapChecksTest.FIXTURE_CLASSES);

    assertThat(result.getFailureReport().getDetails()).containsExactly(FIXTURE_VIOLATION);
  }

  @Test
  void failsOnAViolationThatIsNotFrozen(@TempDir Path folder) throws IOException {
    ModuleBaselineStore store = new ModuleBaselineStore(folder, false, true);
    Files.writeString(store.fileFor(fixtureRule()), "", StandardCharsets.UTF_8);

    assertThatThrownBy(
            () -> freeze(fixtureRule(), store).check(DomainMapChecksTest.FIXTURE_CLASSES))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(FIXTURE_VIOLATION);
  }

  @Test
  void acceptsAFrozenViolation(@TempDir Path folder) throws IOException {
    ModuleBaselineStore store = new ModuleBaselineStore(folder, false, false);
    Files.writeString(
        store.fileFor(fixtureRule()), FIXTURE_VIOLATION + "\n", StandardCharsets.UTF_8);

    freeze(fixtureRule(), store).check(DomainMapChecksTest.FIXTURE_CLASSES);

    assertThat(Files.readString(store.fileFor(fixtureRule()))).isEqualTo(FIXTURE_VIOLATION + "\n");
  }

  @Test
  void shrinksTheBaselineWhenAViolationIsFixed(@TempDir Path folder) throws IOException {
    ModuleBaselineStore store = new ModuleBaselineStore(folder, false, true);
    Files.writeString(
        store.fileFor(fixtureRule()),
        STALE_VIOLATION + "\n" + FIXTURE_VIOLATION + "\n",
        StandardCharsets.UTF_8);

    freeze(fixtureRule(), store).check(DomainMapChecksTest.FIXTURE_CLASSES);

    assertThat(Files.readString(store.fileFor(fixtureRule()))).isEqualTo(FIXTURE_VIOLATION + "\n");
  }

  @Test
  void refusesToShrinkTheBaselineWhenUpdatesAreDisabled(@TempDir Path folder) throws IOException {
    ModuleBaselineStore store = new ModuleBaselineStore(folder, false, false);
    Files.writeString(
        store.fileFor(fixtureRule()),
        STALE_VIOLATION + "\n" + FIXTURE_VIOLATION + "\n",
        StandardCharsets.UTF_8);

    assertThatThrownBy(
            () -> freeze(fixtureRule(), store).check(DomainMapChecksTest.FIXTURE_CLASSES))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("1 violation(s) frozen in")
        .hasMessageContaining("no longer occur");
  }

  @Test
  void refusesToCreateAMissingBaselineWhenCreationIsDisabled(@TempDir Path folder) {
    ModuleBaselineStore store = new ModuleBaselineStore(folder, false, true);

    assertThatThrownBy(
            () -> freeze(fixtureRule(), store).check(DomainMapChecksTest.FIXTURE_CLASSES))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("creating one is disabled");
  }

  @Test
  void writesASortedLfBaselineWhenCreationIsAllowed(@TempDir Path folder) throws IOException {
    ModuleBaselineStore store = new ModuleBaselineStore(folder, true, true);

    freeze(fixtureRule(), store).check(DomainMapChecksTest.FIXTURE_CLASSES);

    Path file = store.fileFor(fixtureRule());
    assertThat(file.getFileName()).hasToString("fixture-modules-respect-the-ranks.txt");
    assertThat(Files.readString(file)).isEqualTo(FIXTURE_VIOLATION + "\n");
  }

  private static ArchRule fixtureRule() {
    return ModuleDependencyRule.rule(
        DomainMap.parse("fixture", DomainMapChecksTest.FIXTURE_MAP),
        DomainMapChecksTest.FIXTURE_CLASSES,
        FIXTURE_DESCRIPTION);
  }

  private static ArchRule freeze(ArchRule rule, ViolationStore store) {
    return FreezingArchRule.freeze(rule)
        .persistIn(store)
        .associateViolationLinesVia(String::equals);
  }

  private static List<String> sortedDetails(EvaluationResult result) {
    return result.getFailureReport().getDetails().stream().sorted().toList();
  }
}
