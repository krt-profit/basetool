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

import de.greluc.krt.profit.basetool.backend.mapper.MissionOperationLookup;
import de.greluc.krt.profit.basetool.backend.mission.api.OperationSummaryProvider;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionOperationSummary;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionImplementor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    Set<UUID> wanted = new LinkedHashSet<>(operationsInContext());
    wanted.add(operationId);
    wanted.removeAll(memo.keySet());
    Map<UUID, MissionOperationSummary> found = operations.operations(wanted);
    for (UUID id : wanted) {
      memo.put(id, Optional.ofNullable(found.get(id)));
    }
    return found.get(operationId);
  }

  private Set<UUID> operationsInContext() {
    Set<UUID> ids = new LinkedHashSet<>();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return ids;
    }
    for (Map.Entry<Object, ?> entry :
        entityManager
            .unwrap(SessionImplementor.class)
            .getPersistenceContextInternal()
            .reentrantSafeEntityEntries()) {
      if (entry.getKey() instanceof Mission mission && mission.getOperationId() != null) {
        ids.add(mission.getOperationId());
      }
    }
    return ids;
  }

  private Memo memoObject() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return new Memo();
    }
    if (TransactionSynchronizationManager.getResource(this) instanceof Memo bound) {
      return bound;
    }
    Memo memo = new Memo();
    TransactionSynchronizationManager.bindResource(this, memo);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(
                MissionOperationSummaryCache.this);
          }
        });
    return memo;
  }

  /** One transaction's memo. */
  private static final class Memo {

    /** The looked-up operations by id, empty for an unknown one. */
    private final Map<UUID, Optional<MissionOperationSummary>> summaries = new HashMap<>();
  }
}
