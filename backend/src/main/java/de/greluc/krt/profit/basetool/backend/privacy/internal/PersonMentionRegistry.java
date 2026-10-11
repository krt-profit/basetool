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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Collects the columns every module contributes to the admin person search (REQ-SEC-060) and puts
 * them in a stable order.
 *
 * <p>The registry fails at construction when two sources declare the same column, so a duplicate
 * never reaches a running search.
 */
@Component
public class PersonMentionRegistry {

  private final List<Target> targets;

  /**
   * Orders the contributions of all registered sources.
   *
   * @param sources every {@link PersonMentionSource} bean
   * @throws IllegalStateException when two targets name the same column
   */
  public PersonMentionRegistry(@NotNull List<PersonMentionSource> sources) {
    this.targets = ordered(sources.stream().flatMap(s -> s.targets().stream()).toList());
  }

  /**
   * Returns every searched column.
   *
   * @return the targets, ordered by area, table and column
   */
  public @NotNull @Unmodifiable List<Target> targets() {
    return targets;
  }

  /**
   * Orders targets by area, table and column and rejects a column declared twice.
   *
   * @param declared the targets of all sources, in any order
   * @return the targets, ordered
   * @throws IllegalStateException when two targets name the same column
   */
  public static @NotNull @Unmodifiable List<Target> ordered(@NotNull List<Target> declared) {
    Set<String> seen = new HashSet<>();
    for (Target target : declared) {
      if (!seen.add(target.table() + "." + target.column())) {
        throw new IllegalStateException(
            "Two sources search the column " + target.table() + "." + target.column());
      }
    }
    return declared.stream()
        .sorted(
            Comparator.comparing(Target::area)
                .thenComparing(Target::table)
                .thenComparing(Target::column))
        .toList();
  }
}
