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

package de.greluc.krt.profit.basetool.backend.testcontext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/** Walks a bean dependency graph to find which beans a set of root beans reaches. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BeanReach {

  /**
   * Reports every target bean that a root reaches through its dependencies, a root itself included.
   *
   * @param dependencies the beans one bean depends on, by bean name
   * @param roots the beans whose reach is searched
   * @param targets the beans that must stay out of reach
   * @return one {@code target <- … <- root} chain per reached target, sorted
   */
  static @NotNull @Unmodifiable List<String> reachedTargets(
      @NotNull Function<String, Collection<String>> dependencies,
      @NotNull Collection<String> roots,
      @NotNull Collection<String> targets) {
    Map<String, String> parent = new HashMap<>();
    Deque<String> queue = new ArrayDeque<>();
    for (String root : new TreeSet<>(roots)) {
      if (!parent.containsKey(root)) {
        parent.put(root, null);
        queue.add(root);
      }
    }
    while (!queue.isEmpty()) {
      String bean = queue.poll();
      for (String dependency : new TreeSet<>(dependencies.apply(bean))) {
        if (!parent.containsKey(dependency)) {
          parent.put(dependency, bean);
          queue.add(dependency);
        }
      }
    }
    List<String> chains = new ArrayList<>();
    for (String target : Set.copyOf(targets)) {
      if (parent.containsKey(target)) {
        List<String> chain = new ArrayList<>();
        for (String step = target; step != null; step = parent.get(step)) {
          chain.add(step);
        }
        chains.add(String.join(" <- ", chain));
      }
    }
    chains.sort(String::compareTo);
    return List.copyOf(chains);
  }
}
