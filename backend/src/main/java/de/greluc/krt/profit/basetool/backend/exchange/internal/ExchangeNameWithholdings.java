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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a member's choice to withhold their name from connected applications
 * (REQ-XCH-044). A member without a row shares their name and is read at version 0, the version the
 * first written row starts at.
 */
@Service
@RequiredArgsConstructor
public class ExchangeNameWithholdings {

  private final ExchangeNameWithholdingRepository repository;
  private final UserRepository userRepository;
  private final AuditRecorder auditRecorder;

  /**
   * The stored choice with the row version a client echoes back on its next write.
   *
   * @param withholdName whether the member withholds their name
   * @param version the row version, 0 while no row exists
   */
  public record Snapshot(boolean withholdName, long version) {}

  /**
   * Reads a member's choice.
   *
   * @param userId the member; never {@code null}
   * @return the stored choice and the version to echo back
   */
  @Transactional(readOnly = true)
  public @NotNull Snapshot snapshotOf(@NotNull UUID userId) {
    return repository
        .findById(userId)
        .map(s -> new Snapshot(s.isWithholdName(), versionOf(s)))
        .orElseGet(() -> new Snapshot(false, 0L));
  }

  /**
   * Returns which of the given members withhold their name.
   *
   * @param userIds the members
   * @return those among them whose stored choice is to withhold their name
   */
  @Transactional(readOnly = true)
  public @NotNull Set<UUID> withholding(@NotNull Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    return repository.findAllById(userIds).stream()
        .filter(ExchangeNameWithholding::isWithholdName)
        .map(ExchangeNameWithholding::getId)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Sets a member's choice and records {@link AuditEventType#EXCHANGE_NAME_WITHHOLDING_CHANGED}
   * when the value changed.
   *
   * @param userId the member; never {@code null}
   * @param withholdName the new choice
   * @param version the version the caller last read; {@code null} skips the check, 0 matches a
   *     member without a row
   * @return the persisted choice and the new version
   * @throws NotFoundException when the member is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public @NotNull Snapshot update(
      @NotNull UUID userId, boolean withholdName, @Nullable Long version) {
    if (!userRepository.existsById(userId)) {
      throw new NotFoundException("User not found");
    }
    ExchangeNameWithholding row = repository.findById(userId).orElse(null);
    OptimisticLock.checkOptionalClient(
        row == null ? Long.valueOf(0L) : row.getVersion(),
        version,
        ExchangeNameWithholding.class,
        userId);
    boolean changed = (row != null && row.isWithholdName()) != withholdName;
    if (row == null) {
      row = new ExchangeNameWithholding();
      row.setId(userId);
    }
    row.setWithholdName(withholdName);
    ExchangeNameWithholding saved = repository.saveAndFlush(row);
    if (changed) {
      auditRecorder.record(
          AuditEventType.EXCHANGE_NAME_WITHHOLDING_CHANGED,
          null,
          null,
          userId,
          AuditDetails.of("withholdName", withholdName));
    }
    return new Snapshot(saved.isWithholdName(), versionOf(saved));
  }

  private static long versionOf(@NotNull ExchangeNameWithholding row) {
    Long version = row.getVersion();
    return version == null ? 0L : version;
  }
}
