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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource.LinkKind;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies how {@link PersonMentionRegistry} orders and checks the contributed targets. */
class PersonMentionRegistryTest {

  private static Target target(String area, String table, String column) {
    return new Target(area, table, column, "id", LinkKind.MEMBER);
  }

  @Test
  void targetsOfAllSourcesComeBackOrderedByAreaTableAndColumn() {
    PersonMentionSource first =
        () -> List.of(target("MISSION", "mission", "name"), target("ANNOUNCEMENT", "z", "c"));
    PersonMentionSource second =
        () ->
            List.of(target("MISSION", "mission", "description"), target("ANNOUNCEMENT", "a", "c"));

    PersonMentionRegistry registry = new PersonMentionRegistry(List.of(first, second));

    assertThat(registry.targets())
        .extracting(t -> t.area() + "." + t.table() + "." + t.column())
        .containsExactly(
            "ANNOUNCEMENT.a.c",
            "ANNOUNCEMENT.z.c",
            "MISSION.mission.description",
            "MISSION.mission.name");
  }

  @Test
  void aColumnTwoSourcesSearchIsRefused() {
    PersonMentionSource first = () -> List.of(target("MISSION", "mission", "name"));
    PersonMentionSource second = () -> List.of(target("OTHER", "mission", "name"));

    assertThatThrownBy(() -> new PersonMentionRegistry(List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mission.name");
  }

  @Test
  void aSourceDeclaresNoExemptionsUnlessItSaysSo() {
    PersonMentionSource source = () -> List.of(target("MISSION", "mission", "name"));

    assertThat(source.exemptColumns()).isEmpty();
    assertThat(source.exemptButMayHoldNames()).isEmpty();
    assertThat(source.exemptTables()).isEmpty();
  }

  @Test
  void everyDeclaredTargetOfTheApplicationIsUniqueAndHasAnId() {
    List<Target> targets = MentionSourceDeclarations.targets();

    assertThat(targets).hasSizeGreaterThanOrEqualTo(79);
    assertThat(targets).extracting(t -> t.table() + "." + t.column()).doesNotHaveDuplicates();
    assertThat(targets).allSatisfy(t -> assertThat(t.idColumn()).isNotBlank());
  }
}
