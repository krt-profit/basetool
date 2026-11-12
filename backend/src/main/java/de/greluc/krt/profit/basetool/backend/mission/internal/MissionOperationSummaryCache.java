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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.kernel.TransactionMemo;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionOperationLookup;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionOperationSummary;
import de.greluc.krt.profit.basetool.backend.mission.api.OperationSummaryProvider;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The mission module's per-transaction memo of the operations its missions belong to (plan §7.5
 * P3-8a): the first miss loads every operation the missions of the persistence context belong to in
 * one read, so a page of missions costs one lookup, not one per row (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class MissionOperationSummaryCache implements MissionOperationLookup {

  private final OperationSummaryProvider operations;
  private final EntityManager entityManager;

  @Override
  public @Nullable MissionOperationSummary operation(@NotNull UUID operationId) {
    Map<UUID, Optional<MissionOperationSummary>> memo = memoObject().summaries;
    Optional<MissionOperationSummary> known = memo.get(operationId);
    if (known != null) {
      return known.orElse(null);
    }
    Set<UUID> wanted =
        TransactionMemo.idsInContext(entityManager, Mission.class, Mission::getOperationId);
    wanted.add(operationId);
    wanted.removeAll(memo.keySet());
    Map<UUID, MissionOperationSummary> found = operations.operations(wanted);
    for (UUID id : wanted) {
      memo.put(id, Optional.ofNullable(found.get(id)));
    }
    return found.get(operationId);
  }

  private Memo memoObject() {
    return TransactionMemo.bound(this, Memo.class, Memo::new);
  }

  /** One transaction's memo. */
  private static final class Memo {

    /** The looked-up operations by id, empty for an unknown one. */
    private final Map<UUID, Optional<MissionOperationSummary>> summaries = new HashMap<>();
  }
}
