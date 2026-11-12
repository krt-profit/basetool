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

import de.greluc.krt.profit.basetool.backend.inventory.api.DonationEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionImplementor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Lager's per-transaction memo of its earmark targets (plan §7.5 P3-5d): the first miss loads
 * every mission or job order the Lager rows of the persistence context are earmarked to in one
 * read, so a page of rows costs one lookup, not one per row (REQ-DATA-003).
 *
 * <p>A mission is memoised twice, as the write and undo paths look it up and as the caller is shown
 * it, so the answer of a write never shows what its own lookup read.
 */
@Component
@RequiredArgsConstructor
public class InventoryEarmarkTargetCache implements InventoryEarmarkLookup {

  private final EarmarkTargets.Missions missions;
  private final EarmarkTargets.JobOrders jobOrders;
  private final EarmarkTargets.Donations donations;
  private final EntityManager entityManager;

  @Override
  public @Nullable MissionEarmark mission(@NotNull UUID missionId) {
    return memoised(
        memoObject().missions, missionId, this::earmarkedMissionsInContext, missions::missions);
  }

  @Override
  public @Nullable MissionEarmark shownMission(@NotNull UUID missionId) {
    return memoised(
        memoObject().shownMissions,
        missionId,
        this::earmarkedMissionsInContext,
        missions::shownMissions);
  }

  @Override
  public @Nullable JobOrderEarmark jobOrder(@NotNull UUID jobOrderId) {
    return memoised(
        memoObject().jobOrders,
        jobOrderId,
        this::earmarkedJobOrdersInContext,
        jobOrders::jobOrders);
  }

  @Override
  public @Nullable DonationEarmark donation(@NotNull UUID donationId) {
    return memoised(
        memoObject().donations,
        donationId,
        this::earmarkedDonationsInContext,
        donations::donations);
  }

  /**
   * Returns one target from the transaction's memo; a miss loads it together with every target of
   * the same kind the persistence context's earmarks name and the memo does not hold yet, in one
   * read.
   *
   * @param memo the transaction's memo of this kind of target, an empty value for an unknown one
   * @param id the target
   * @param earmarked the targets the persistence context's earmarks of this kind name
   * @param load the owning module's read of several targets
   * @param <T> the kind of target
   * @return the target, or {@code null} when it does not exist
   */
  private static <T> @Nullable T memoised(
      @NotNull Map<UUID, Optional<T>> memo,
      @NotNull UUID id,
      @NotNull Supplier<Set<UUID>> earmarked,
      @NotNull Function<Set<UUID>, Map<UUID, T>> load) {
    if (!memo.containsKey(id)) {
      Set<UUID> wanted = new LinkedHashSet<>(earmarked.get());
      wanted.add(id);
      wanted.removeAll(memo.keySet());
      Map<UUID, T> found = load.apply(wanted);
      for (UUID loaded : wanted) {
        memo.put(loaded, Optional.ofNullable(found.get(loaded)));
      }
    }
    return Objects.requireNonNull(memo.get(id)).orElse(null);
  }

  private Set<UUID> earmarkedDonationsInContext() {
    Set<UUID> ids = new LinkedHashSet<>();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return ids;
    }
    for (Map.Entry<Object, ?> entry :
        entityManager
            .unwrap(SessionImplementor.class)
            .getPersistenceContextInternal()
            .reentrantSafeEntityEntries()) {
      if (entry.getKey() instanceof InventoryDonationAllocation allocation
          && allocation.getDonationId() != null) {
        ids.add(allocation.getDonationId());
      }
    }
    return ids;
  }

  private Set<UUID> earmarkedJobOrdersInContext() {
    Set<UUID> ids = new LinkedHashSet<>();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return ids;
    }
    for (Map.Entry<Object, ?> entry :
        entityManager
            .unwrap(SessionImplementor.class)
            .getPersistenceContextInternal()
            .reentrantSafeEntityEntries()) {
      if (entry.getKey() instanceof InventoryJobOrderAllocation allocation
          && allocation.getJobOrderId() != null) {
        ids.add(allocation.getJobOrderId());
      }
    }
    return ids;
  }

  private Set<UUID> earmarkedMissionsInContext() {
    Set<UUID> ids = new LinkedHashSet<>();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return ids;
    }
    for (Map.Entry<Object, ?> entry :
        entityManager
            .unwrap(SessionImplementor.class)
            .getPersistenceContextInternal()
            .reentrantSafeEntityEntries()) {
      if (entry.getKey() instanceof InventoryMissionAllocation allocation
          && allocation.getMissionId() != null) {
        ids.add(allocation.getMissionId());
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
          public void suspend() {
            TransactionSynchronizationManager.unbindResource(InventoryEarmarkTargetCache.this);
          }

          @Override
          public void resume() {
            TransactionSynchronizationManager.bindResource(InventoryEarmarkTargetCache.this, memo);
          }

          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(
                InventoryEarmarkTargetCache.this);
          }
        });
    return memo;
  }

  /** One transaction's memo. */
  private static final class Memo {

    /** The looked-up missions by id, empty for an unknown one. */
    private final Map<UUID, Optional<MissionEarmark>> missions = new HashMap<>();

    /** The missions as the caller is shown them, by id, empty for an unknown one. */
    private final Map<UUID, Optional<MissionEarmark>> shownMissions = new HashMap<>();

    /** The looked-up job orders by id, empty for an unknown one. */
    private final Map<UUID, Optional<JobOrderEarmark>> jobOrders = new HashMap<>();

    /** The looked-up donations by id, empty for an unknown one. */
    private final Map<UUID, Optional<DonationEarmark>> donations = new HashMap<>();
  }
}
