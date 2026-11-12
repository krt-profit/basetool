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

package de.greluc.krt.profit.basetool.backend.scope.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the calling member's stored choice of the active org unit (REQ-ORG-031): the
 * frontend restores it at the start of a session and stores every switch. A stored unit the caller
 * may no longer select reads as no choice; {@code null} means all of the caller's units.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveOrgUnitChoices {

  /** Attempts of one switch before a persistent concurrent-write race answers 409. */
  private static final int MAX_CHOOSE_ATTEMPTS = 5;

  private final MemberActiveOrgUnitRepository repository;
  private final RequestScopeResolver requestScopeResolver;
  private final AuthHelperService authHelper;
  private final ActiveOrgUnitChoiceMetrics metrics;

  /** This bean's proxy, so every attempt of {@link #choose} runs in a transaction of its own. */
  private final ObjectProvider<ActiveOrgUnitChoices> self;

  /**
   * The stored choice with the row version a client may echo back on its next write.
   *
   * @param orgUnitId the chosen org unit, or {@code null} for all of the caller's org units
   * @param version the row version, 0 while no row exists
   */
  public record Snapshot(@Nullable UUID orgUnitId, long version) {}

  /**
   * Reads the caller's stored choice; a unit the caller may no longer select reads as {@code null}.
   *
   * @return the choice and its version
   * @throws NotFoundException when the caller has no account
   */
  @Transactional(readOnly = true)
  public @NotNull Snapshot currentChoice() {
    UUID userId = callerId();
    return repository
        .findById(userId)
        .map(row -> new Snapshot(selectableOrNull(row.getOrgUnitId()), versionOf(row)))
        .orElseGet(() -> new Snapshot(null, 0L));
  }

  /**
   * Stores the caller's choice; a changed choice is counted, not audited. The switcher sends no
   * version, so the latest choice wins: an attempt that loses a concurrent insert or update of the
   * same row is retried in a fresh transaction, up to {@link #MAX_CHOOSE_ATTEMPTS} times.
   *
   * @param orgUnitId the chosen org unit, or {@code null} for all of the caller's org units
   * @param version the version the caller last read, or {@code null} to skip the check
   * @return the stored choice and its new version
   * @throws BadRequestException when the caller may not select the org unit
   * @throws NotFoundException when the caller has no account
   * @throws ObjectOptimisticLockingFailureException when the version is stale, or every attempt
   *     loses the race
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public @NotNull Snapshot choose(@Nullable UUID orgUnitId, @Nullable Long version) {
    for (int attempt = 1; attempt < MAX_CHOOSE_ATTEMPTS; attempt++) {
      try {
        return self.getObject().chooseWithinTransaction(orgUnitId, version);
      } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException race) {
        log.debug(
            "Concurrent switch of the active org unit (attempt {}/{}), retrying",
            attempt,
            MAX_CHOOSE_ATTEMPTS);
      }
    }
    return self.getObject().chooseWithinTransaction(orgUnitId, version);
  }

  /**
   * Performs one attempt of {@link #choose} in its own {@code REQUIRES_NEW} transaction; called
   * only through {@link #choose}.
   *
   * @param orgUnitId the chosen org unit, or {@code null} for all of the caller's org units
   * @param version the version the caller last read, or {@code null} to skip the check
   * @return the stored choice and its new version
   * @throws BadRequestException when the caller may not select the org unit
   * @throws NotFoundException when the caller has no account
   * @throws DataIntegrityViolationException when a concurrent switch inserted the row first
   * @throws ObjectOptimisticLockingFailureException when the version is stale or a concurrent
   *     switch updated the row first
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public @NotNull Snapshot chooseWithinTransaction(
      @Nullable UUID orgUnitId, @Nullable Long version) {
    UUID userId = callerId();
    if (orgUnitId != null && !requestScopeResolver.isSelectable(orgUnitId)) {
      throw new BadRequestException("The org unit is not selectable for the caller");
    }
    MemberActiveOrgUnit row = repository.findById(userId).orElse(null);
    OptimisticLock.checkOptionalClient(
        row == null ? Long.valueOf(0L) : row.getVersion(),
        version,
        MemberActiveOrgUnit.class,
        userId);
    UUID previous = row == null ? null : row.getOrgUnitId();
    if (Objects.equals(previous, orgUnitId)) {
      return new Snapshot(orgUnitId, row == null ? 0L : versionOf(row));
    }
    if (row == null) {
      row = new MemberActiveOrgUnit();
      row.setId(userId);
    }
    row.setOrgUnitId(orgUnitId);
    MemberActiveOrgUnit saved = repository.saveAndFlush(row);
    metrics.recordWrite(orgUnitId);
    return new Snapshot(saved.getOrgUnitId(), versionOf(saved));
  }

  private @NotNull UUID callerId() {
    return Entities.require(authHelper.currentUserId(), "User not found");
  }

  private @Nullable UUID selectableOrNull(@Nullable UUID orgUnitId) {
    return orgUnitId != null && requestScopeResolver.isSelectable(orgUnitId) ? orgUnitId : null;
  }

  private static long versionOf(@NotNull MemberActiveOrgUnit row) {
    Long version = row.getVersion();
    return version == null ? 0L : version;
  }
}
