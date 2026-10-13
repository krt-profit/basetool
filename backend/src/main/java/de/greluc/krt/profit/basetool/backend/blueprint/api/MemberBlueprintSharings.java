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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a member's opt-in to global blueprint sharing (REQ-INV-018), which counts their
 * personal blueprints in the availability views of every org unit. A member without a row has not
 * opted in and is read at version 0, the version the first written row starts at.
 */
@Service
@RequiredArgsConstructor
public class MemberBlueprintSharings {

  private final MemberBlueprintSharingRepository repository;
  private final UserRepository userRepository;
  private final AuditRecorder auditRecorder;

  /**
   * The stored opt-in with the row version a client echoes back on its next write.
   *
   * @param shareGlobally whether the member shares globally
   * @param version the row version, 0 while no row exists
   */
  public record Snapshot(boolean shareGlobally, long version) {}

  /**
   * Reads a member's opt-in.
   *
   * @param userId the member; never {@code null}
   * @return the stored opt-in and the version to echo back
   */
  @Transactional(readOnly = true)
  public @NotNull Snapshot snapshotOf(@NotNull UUID userId) {
    return repository
        .findById(userId)
        .map(s -> new Snapshot(s.isShareGlobally(), versionOf(s)))
        .orElseGet(() -> new Snapshot(false, 0L));
  }

  /**
   * Returns the ids of every member who shares their blueprints globally.
   *
   * @return the member ids; never {@code null}, possibly empty
   */
  @Transactional(readOnly = true)
  public @NotNull Set<UUID> userIdsSharingGlobally() {
    return repository.findIdsSharingGlobally();
  }

  /**
   * Sets a member's opt-in and records {@link AuditEventType#BLUEPRINT_SHARING_CHANGED} when the
   * value changed.
   *
   * @param userId the member; never {@code null}
   * @param shareGlobally the new opt-in value
   * @param version the version the caller last read; {@code null} skips the check, 0 matches a
   *     member without a row
   * @return the persisted opt-in and the new version
   * @throws NotFoundException when the member is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public @NotNull Snapshot update(
      @NotNull UUID userId, boolean shareGlobally, @Nullable Long version) {
    if (!userRepository.existsById(userId)) {
      throw new NotFoundException("User not found");
    }
    MemberBlueprintSharing sharing = repository.findById(userId).orElse(null);
    OptimisticLock.checkOptionalClient(
        sharing == null ? Long.valueOf(0L) : sharing.getVersion(),
        version,
        MemberBlueprintSharing.class,
        userId);
    boolean changed = (sharing != null && sharing.isShareGlobally()) != shareGlobally;
    if (sharing == null) {
      sharing = new MemberBlueprintSharing();
      sharing.setId(userId);
    }
    sharing.setShareGlobally(shareGlobally);
    MemberBlueprintSharing saved = repository.saveAndFlush(sharing);
    if (changed) {
      auditRecorder.record(
          AuditEventType.BLUEPRINT_SHARING_CHANGED,
          null,
          null,
          userId,
          AuditDetails.of("enabled", shareGlobally));
    }
    return new Snapshot(saved.isShareGlobally(), versionOf(saved));
  }

  private static long versionOf(@NotNull MemberBlueprintSharing sharing) {
    Long version = sharing.getVersion();
    return version == null ? 0L : version;
  }
}
