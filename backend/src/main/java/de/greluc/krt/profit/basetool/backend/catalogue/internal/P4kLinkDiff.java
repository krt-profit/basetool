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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The change of one owner's links in a link table only the import writes (a paint's ship types,
 * REQ-P4K-041; a shop's items, REQ-P4K-044): the targets to add and the targets to remove, so the
 * links that stay are never rewritten.
 *
 * @param added the wanted targets without a stored link
 * @param removed the stored targets no longer wanted
 * @param <T> the target type
 */
record P4kLinkDiff<T>(@NotNull @Unmodifiable Set<T> added, @NotNull @Unmodifiable Set<T> removed) {

  /** The most links one delete statement names. */
  static final int DELETE_CHUNK = 500;

  /**
   * Diffs the wanted targets against the stored ones.
   *
   * @param wanted the targets the catalogue names
   * @param stored the targets the table holds
   * @param <T> the target type
   * @return the targets to add and to remove
   */
  @NotNull
  static <T> P4kLinkDiff<T> of(@NotNull Set<T> wanted, @NotNull Set<T> stored) {
    Set<T> added = new HashSet<>(wanted);
    added.removeAll(stored);
    Set<T> removed = new HashSet<>(stored);
    removed.removeAll(wanted);
    return new P4kLinkDiff<>(Set.copyOf(added), Set.copyOf(removed));
  }

  /**
   * Deletes links in bulk statements of at most {@value #DELETE_CHUNK} links each, after every
   * owner was diffed.
   *
   * @param links the links to delete
   * @param delete deletes one chunk in one statement
   * @param <L> the link type
   */
  static <L> void deleteInChunks(@NotNull List<L> links, @NotNull Consumer<List<L>> delete) {
    for (int from = 0; from < links.size(); from += DELETE_CHUNK) {
      delete.accept(links.subList(from, Math.min(links.size(), from + DELETE_CHUNK)));
    }
  }
}
