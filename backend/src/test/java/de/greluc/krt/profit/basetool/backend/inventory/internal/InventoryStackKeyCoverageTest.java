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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Holds every stack-key query of {@link InventoryItemRepository} to the full stock identity
 * (REQ-INV-053): a query that groups or matches by the {@code personal} flag must treat the
 * „gestohlen" marker the same way, or stolen stock would be laundered into a legitimate stack.
 */
class InventoryStackKeyCoverageTest {

  /**
   * Returns the JPQL of every {@link Query}-annotated method of the repository with its name.
   *
   * @return pairs of method name and query text
   */
  private static List<String[]> queries() {
    return Arrays.stream(InventoryItemRepository.class.getDeclaredMethods())
        .filter(m -> m.isAnnotationPresent(Query.class))
        .map(m -> new String[] {m.getName(), m.getAnnotation(Query.class).value()})
        .toList();
  }

  /**
   * Returns the GROUP BY clause of a query, or an empty string when it has none.
   *
   * @param jpql the query text
   * @return the text after {@code GROUP BY}
   */
  private static String groupByClause(String jpql) {
    int k = jpql.indexOf("GROUP BY");
    return k < 0 ? "" : jpql.substring(k);
  }

  @Test
  void everyStackGroupingCarriesTheMarker() {
    List<String[]> groupings =
        queries().stream().filter(q -> groupByClause(q[1]).contains("i.personal")).toList();

    assertThat(groupings).as("the sweep found the stack groupings").hasSizeGreaterThanOrEqualTo(4);
    assertThat(groupings)
        .allSatisfy(
            q ->
                assertThat(groupByClause(q[1]))
                    .as(q[0] + " groups by personal and must group by stolen too")
                    .contains("i.stolen"));
  }

  @Test
  void everyStackKeyMatchCarriesTheMarker() {
    List<String[]> matches =
        queries().stream()
            .filter(
                q ->
                    q[1].contains("i.personal = :personal")
                        || (q[1].contains("ORDER BY i.createdAt")
                            && q[1].contains("i.location.id = :locationId")))
            .toList();

    assertThat(matches)
        .as("the sweep found the entries and merge-group lookups")
        .hasSizeGreaterThanOrEqualTo(5);
    assertThat(matches)
        .allSatisfy(
            q ->
                assertThat(q[1])
                    .as(q[0] + " selects one stack and must match the marker")
                    .contains("i.stolen = :stolen"));
  }

  @Test
  void theSweepSeesEveryQueryMethod() {
    long annotated =
        Arrays.stream(InventoryItemRepository.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Query.class))
            .map(Method::getName)
            .count();

    assertThat(annotated).as("a sweep over no queries would pass vacuously").isGreaterThan(20);
  }
}
