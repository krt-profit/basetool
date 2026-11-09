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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resolves current Profit hierarchy eligibility and coordinates it with transactional writes. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfitHierarchyService {

  /** Shared/exclusive transaction lock namespace for Profit hierarchy authority. */
  private static final long ELIGIBILITY_LOCK = 16842769L;

  private final OrgUnitRepository orgUnitRepository;
  private final EntityManager entityManager;

  /**
   * Returns the unique active designated Profit area, refusing contradictory or missing roots.
   *
   * @return the active Profit identity, or empty when no unique active designation exists
   */
  public Optional<UUID> activeProfitRootId() {
    List<UUID> roots = orgUnitRepository.findActiveProfitRootIds();
    return roots.size() == 1 ? Optional.of(roots.getFirst()) : Optional.empty();
  }

  /**
   * Resolves one current hierarchy snapshot for a bounded batch authority decision.
   *
   * @return the actual root identity and active eligible units
   */
  public ProfitHierarchySnapshot snapshot() {
    EligibilityCoordination current = coordination();
    if (current == null || current.exclusive) {
      return loadSnapshot();
    }
    ProfitHierarchySnapshot snapshot = current.snapshot;
    if (snapshot == null) {
      snapshot = loadSnapshot();
      current.snapshot = snapshot;
    }
    return snapshot;
  }

  private ProfitHierarchySnapshot loadSnapshot() {
    UUID root = activeProfitRootId().orElse(null);
    return new ProfitHierarchySnapshot(
        root,
        root == null ? Set.of() : Set.copyOf(orgUnitRepository.findActiveProfitUnitIds(root)));
  }

  /**
   * Returns the active eligible unit set for bounded batch authority checks.
   *
   * @return active identities under the unique active Profit root
   */
  public Set<UUID> eligibleUnitIds() {
    return snapshot().eligibleUnitIds();
  }

  /**
   * Checks the active designated area and its active direct Staffel/SK descendants.
   *
   * @param orgUnitId the proposed responsible unit
   * @return whether the unit belongs to the actual active Profit hierarchy
   */
  public boolean isEligible(@NotNull UUID orgUnitId) {
    return snapshot().eligibleUnitIds().contains(orgUnitId);
  }

  /**
   * Resolves the active Profit identity for a currently eligible unit.
   *
   * @param orgUnitId the responsible unit
   * @return the unique active Profit root
   * @throws BadRequestException when the hierarchy or unit is ineligible
   */
  public UUID requireEligible(@NotNull UUID orgUnitId) {
    ProfitHierarchySnapshot snapshot = snapshot();
    UUID root = snapshot.profitRootId();
    if (root == null) {
      throw new BadRequestException("orders.profitHierarchy.required");
    }
    if (!snapshot.eligibleUnitIds().contains(orgUnitId)) {
      throw new BadRequestException("orders.responsibility.target.invalid");
    }
    return root;
  }

  /**
   * Keeps eligibility stable until the caller's order transaction commits.
   *
   * <p>Order writes share the lock; hierarchy changes take its exclusive counterpart first.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockForOrderWrite() {
    if (coordination() != null) {
      return;
    }
    entityManager
        .createNativeQuery("SELECT pg_advisory_xact_lock_shared(:key)")
        .setParameter("key", ELIGIBILITY_LOCK)
        .getSingleResult();
    TransactionSynchronizationManager.registerSynchronization(
        new EligibilityCoordination(false, null));
  }

  /** Keeps all affected eligibility stable while an organisational change is applied. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockForHierarchyChange() {
    EligibilityCoordination current = coordination();
    if (current != null && current.exclusive) {
      return;
    }
    entityManager
        .createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
        .setParameter("key", ELIGIBILITY_LOCK)
        .getSingleResult();
    if (current == null) {
      TransactionSynchronizationManager.registerSynchronization(
          new EligibilityCoordination(true, null));
    } else {
      current.exclusive = true;
      current.snapshot = null;
    }
  }

  private static @Nullable EligibilityCoordination coordination() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return null;
    }
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof EligibilityCoordination current) {
        return current;
      }
    }
    return null;
  }

  /** The coordination lock and stable snapshot owned by the current transaction only. */
  @AllArgsConstructor
  private static final class EligibilityCoordination implements TransactionSynchronization {
    private boolean exclusive;
    private @Nullable ProfitHierarchySnapshot snapshot;
  }
}
