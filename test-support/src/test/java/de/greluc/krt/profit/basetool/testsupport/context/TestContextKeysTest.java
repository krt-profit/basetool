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

package de.greluc.krt.profit.basetool.testsupport.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Proves that {@link TestContextKeys} tells apart exactly the contexts Spring would split. */
class TestContextKeysTest {

  @Test
  void classesWithTheSameMockFieldsShareOneKey() {
    assertThat(TestContextKeys.group(List.of(MockAlpha.class, MockAlphaAgain.class))).hasSize(1);
  }

  @Test
  void aDifferentMockSetSplitsTheKey() {
    Map<MergedContextConfiguration, List<Class<?>>> groups =
        TestContextKeys.group(
            List.of(MockAlpha.class, MockAlphaAgain.class, MockBeta.class, Plain.class));

    assertThat(groups).hasSize(3);
    assertThat(groups.values().iterator().next())
        .containsExactly(MockAlpha.class, MockAlphaAgain.class);
    assertThat(TestContextKeys.report(groups))
        .contains("#1  2 class(es)", "MockitoBean Runnable task", "MockitoBean Callable task");
  }

  @Test
  void theSameMockUnderAnotherFieldNameSplitsTheKey() {
    assertThat(TestContextKeys.group(List.of(MockAlpha.class, MockAlphaRenamed.class))).hasSize(2);
  }

  @Test
  void typeLevelMocksShareOneKeyWhateverTheClassDeclares() {
    Map<MergedContextConfiguration, List<Class<?>>> groups =
        TestContextKeys.group(List.of(TypeLevelA.class, TypeLevelB.class));

    assertThat(groups).hasSize(1);
    assertThat(TestContextKeys.report(groups)).contains("MockitoBean Runnable -");
  }

  @Test
  void anInlinedPropertySplitsTheKey() {
    assertThat(TestContextKeys.group(List.of(Plain.class, WithProperty.class))).hasSize(2);
  }

  @Test
  void findsTopLevelAndNestedSpringTestClassesOnly() {
    List<Class<?>> classes =
        TestContextKeys.springTestClasses(
            Path.of("build/classes/java/test"), getClass().getClassLoader());

    assertThat(classes)
        .contains(SpringFixture.class, SpringFixture.Inner.class)
        .doesNotContain(
            TestContextKeysTest.class, MockAlpha.class, SpringFixture.StaticHelper.class);
    assertThat(
            TestContextKeys.springTestClasses(
                Path.of("build/missing"), getClass().getClassLoader()))
        .isEmpty();
  }

  /** The configuration class the fixtures name; never loaded. */
  static class FixtureConfig {}

  /** Mocks a {@link Runnable} as {@code task}. */
  @ExtendWith(SpringExtension.class)
  @ContextConfiguration(classes = FixtureConfig.class)
  static class MockAlpha {
    @MockitoBean Runnable task;
  }

  /** The same mock under the same field name as {@link MockAlpha}. */
  @ExtendWith(SpringExtension.class)
  @ContextConfiguration(classes = FixtureConfig.class)
  static class MockAlphaAgain {
    @MockitoBean Runnable task;
  }

  /** The same mock type as {@link MockAlpha} under another field name. */
  @ExtendWith(SpringExtension.class)
  @ContextConfiguration(classes = FixtureConfig.class)
  static class MockAlphaRenamed {
    @MockitoBean Runnable job;
  }

  /** Mocks another type. */
  @ExtendWith(SpringExtension.class)
  @ContextConfiguration(classes = FixtureConfig.class)
  static class MockBeta {
    @MockitoBean Callable<?> task;
  }

  /** Mocks nothing. */
  @ExtendWith(SpringExtension.class)
  @ContextConfiguration(classes = FixtureConfig.class)
  static class Plain {}

  /** Mocks nothing but inlines a property. */
  @ExtendWith(SpringExtension.class)
  @ContextConfiguration(classes = FixtureConfig.class)
  @TestPropertySource(properties = "fixture.flag=true")
  static class WithProperty {}

  /** Declares its mock on the type. */
  @ExtendWith(SpringExtension.class)
  @ContextConfiguration(classes = FixtureConfig.class)
  @MockitoBean(types = Runnable.class)
  static class TypeLevelA {}

  /** Declares the same type-level mock as {@link TypeLevelA}. */
  @ExtendWith(SpringExtension.class)
  @ContextConfiguration(classes = FixtureConfig.class)
  @MockitoBean(types = Runnable.class)
  static class TypeLevelB {}
}
