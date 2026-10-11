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

package de.greluc.krt.profit.basetool.backend.platform.api;

import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant.Moved;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the set-based statements of an account merge for the {@link OwnedRows} a module declares:
 * deletes the source rows the target already holds, then re-points the rest.
 *
 * <p>The only class that runs SQL naming another module's tables for the merge; the table and
 * column names come from the participants' own literals, never from a request.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OwnedRowsMover {

  private final EntityManager entityManager;

  /**
   * Moves each declared table from the source account to the target.
   *
   * <p>{@code IS NOT DISTINCT FROM} rather than {@code =} compares the conflict keys, so a nullable
   * key column (an org-chart position with no org unit) matches the way the partial unique index
   * does.
   *
   * @param rows the tables to move, in the order they are moved
   * @param source the account being emptied
   * @param target the account being kept
   * @return one entry per table that had a row to move or to drop, in the order given
   */
  public @NotNull List<Moved> move(
      @NotNull List<OwnedRows> rows, @NotNull UUID source, @NotNull UUID target) {
    List<Moved> moved = new ArrayList<>();
    for (OwnedRows owned : rows) {
      int dropped = dropRowsTheTargetAlreadyHas(owned, source, target);
      int repointed = repoint(owned, source, target);
      if (repointed > 0 || dropped > 0) {
        moved.add(new Moved(owned.qualified(), repointed));
      }
    }
    return moved;
  }

  /**
   * Deletes the source rows whose unique key the target already carries; a no-op for a table
   * without such a constraint.
   *
   * @param owned the table being moved
   * @param source the account being emptied
   * @param target the account being kept
   * @return the number of duplicate source rows removed
   */
  private int dropRowsTheTargetAlreadyHas(
      @NotNull OwnedRows owned, @NotNull UUID source, @NotNull UUID target) {
    if (owned.conflictKeys().isEmpty()) {
      return 0;
    }
    StringBuilder keyMatch = new StringBuilder();
    for (String key : owned.conflictKeys()) {
      keyMatch.append(" AND tgt.").append(key).append(" IS NOT DISTINCT FROM src.").append(key);
    }
    String sql =
        "DELETE FROM %1$s src WHERE src.%2$s = :source AND EXISTS (SELECT 1 FROM %1$s tgt WHERE"
                .formatted(owned.table(), owned.column())
            + " tgt.%s = :target%s)".formatted(owned.column(), keyMatch);
    return entityManager
        .createNativeQuery(sql)
        .setParameter("source", source)
        .setParameter("target", target)
        .executeUpdate();
  }

  /**
   * Re-points one table's user column from the source account to the target.
   *
   * @param owned the table being moved
   * @param source the account being emptied
   * @param target the account being kept
   * @return the number of rows moved
   */
  private int repoint(@NotNull OwnedRows owned, @NotNull UUID source, @NotNull UUID target) {
    String sql =
        "UPDATE %s SET %s = :target WHERE %s = :source"
            .formatted(owned.table(), owned.column(), owned.column());
    return entityManager
        .createNativeQuery(sql)
        .setParameter("source", source)
        .setParameter("target", target)
        .executeUpdate();
  }
}
