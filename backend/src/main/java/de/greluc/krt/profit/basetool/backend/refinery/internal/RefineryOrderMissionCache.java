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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.mapper.RefineryOrderMissionLookup;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferences;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionImplementor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The refinery module's per-transaction memo of the missions its orders are linked to (plan §7.5
 * P3-8b): the first miss loads every mission the orders of the persistence context are linked to in
 * one read, so a page of orders costs one lookup, not one per row (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class RefineryOrderMissionCache implements RefineryOrderMissionLookup {

  private final MissionReferences missions;
  private final EntityManager entityManager;

  @Override
  public @Nullable MissionReferenceDto mission(@Nullable UUID missionId) {
    if (missionId == null) {
      return null;
    }
    Map<UUID, Optional<MissionReferenceDto>> memo = memoObject().references;
    Optional<MissionReferenceDto> known = memo.get(missionId);
    if (known != null) {
      return known.orElse(null);
    }
    Set<UUID> wanted = new LinkedHashSet<>(missionsInContext());
    wanted.add(missionId);
    wanted.removeAll(memo.keySet());
    Map<UUID, MissionReferenceDto> found = missions.references(wanted);
    for (UUID id : wanted) {
      memo.put(id, Optional.ofNullable(found.get(id)));
    }
    return found.get(missionId);
  }

  private Set<UUID> missionsInContext() {
    Set<UUID> ids = new LinkedHashSet<>();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return ids;
    }
    for (Map.Entry<Object, ?> entry :
        entityManager
            .unwrap(SessionImplementor.class)
            .getPersistenceContextInternal()
            .reentrantSafeEntityEntries()) {
      if (entry.getKey() instanceof RefineryOrder order && order.getMissionId() != null) {
        ids.add(order.getMissionId());
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
                RefineryOrderMissionCache.this);
          }
        });
    return memo;
  }

  /** One transaction's memo. */
  private static final class Memo {

    /** The looked-up missions by id, empty for an unknown one. */
    private final Map<UUID, Optional<MissionReferenceDto>> references = new HashMap<>();
  }
}
