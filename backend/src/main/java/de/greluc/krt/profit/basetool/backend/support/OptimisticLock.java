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

package de.greluc.krt.profit.basetool.backend.support;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Optimistic-lock version checks for the hand-rolled {@code @Version} mismatch guard, one method
 * per null/equality semantic.
 *
 * <p>Each throws {@link ObjectOptimisticLockingFailureException} (HTTP 409, code {@code
 * OPTIMISTIC_LOCK}) whose message carries the compared version pair. Not for {@code Mission}'s
 * per-section counters, which use {@link MissionSectionVersions} (REQ-ORG-018).
 */
public final class OptimisticLock {

  /** Non-instantiable static-helper holder. */
  private OptimisticLock() {}

  /**
   * Passes when the persisted version is absent, otherwise raises 409 unless the client version
   * equals it. A {@code null} {@code clientVersion} is a mismatch; see {@link
   * #checkOptionalClient}.
   *
   * @param persistedVersion the entity's current {@code @Version}, or {@code null} if unversioned
   * @param clientVersion the version the caller last saw
   * @param entityType the entity class, for the exception
   * @param identifier the entity id or composite key, for the exception; may be {@code null}
   * @throws ObjectOptimisticLockingFailureException if the persisted version is present and differs
   */
  public static void check(
      @Nullable Long persistedVersion,
      @Nullable Long clientVersion,
      @NotNull Class<?> entityType,
      @Nullable Object identifier) {
    if (persistedVersion != null && !persistedVersion.equals(clientVersion)) {
      throw conflict(entityType, identifier, persistedVersion, clientVersion);
    }
  }

  /**
   * Passes when the client omits the version or the entity is unversioned, otherwise raises 409
   * unless they match. For flows that let a privileged caller force-save.
   *
   * @param persistedVersion the entity's current {@code @Version}, or {@code null} if unversioned
   * @param clientVersion the caller's last-seen version, or {@code null} to skip the check
   * @param entityType the entity class, for the exception
   * @param identifier the entity id or composite key, for the exception; may be {@code null}
   * @throws ObjectOptimisticLockingFailureException if both versions are present and differ
   */
  public static void checkOptionalClient(
      @Nullable Long persistedVersion,
      @Nullable Long clientVersion,
      @NotNull Class<?> entityType,
      @Nullable Object identifier) {
    if (clientVersion != null
        && persistedVersion != null
        && !persistedVersion.equals(clientVersion)) {
      throw conflict(entityType, identifier, persistedVersion, clientVersion);
    }
  }

  /**
   * Raises 409 when the persisted version is absent or does not equal {@code clientVersion}.
   *
   * @param persistedVersion the entity's current {@code @Version}; {@code null} is a conflict
   * @param clientVersion the caller's last-seen version
   * @param entityType the entity class, for the exception
   * @param identifier the entity id or composite key, for the exception; may be {@code null}
   * @throws ObjectOptimisticLockingFailureException if the persisted version is absent or differs
   */
  public static void checkRequired(
      @Nullable Long persistedVersion,
      long clientVersion,
      @NotNull Class<?> entityType,
      @Nullable Object identifier) {
    if (persistedVersion == null || persistedVersion != clientVersion) {
      throw conflict(entityType, identifier, persistedVersion, clientVersion);
    }
  }

  /**
   * Builds the 409 exception with the entity type, the identifier and the message {@code
   * expected=<client> persisted=<persisted>} ({@code null} renders literally).
   *
   * @param entityType the entity class
   * @param identifier the entity id or composite key; may be {@code null}
   * @param persistedVersion the entity's current {@code @Version} as loaded
   * @param clientVersion the version the caller echoed back
   * @return the conflict exception to throw
   */
  @NotNull
  private static ObjectOptimisticLockingFailureException conflict(
      @NotNull Class<?> entityType,
      @Nullable Object identifier,
      @Nullable Long persistedVersion,
      @Nullable Long clientVersion) {
    return new ObjectOptimisticLockingFailureException(
        entityType,
        identifier,
        "expected=" + clientVersion + " persisted=" + persistedVersion,
        null);
  }
}
