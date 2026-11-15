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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocationLock;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocationLock;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeWrites;
import de.greluc.krt.profit.basetool.backend.patchchange.api.RelocationPendingException;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The relocation lock (REQ-PATCH-023, ADR-0383): until a member relocates in the open home-location
 * phase, their stock rows and ships from before its live time change only for the member and admins
 * on the web. An exchange client is refused always, the member's own included; a patch change's own
 * writes and work without a caller are never refused. A write is judged by the phase read fresh
 * under the history's lock the trigger shares (REQ-PATCH-064).
 */
@Component
@RequiredArgsConstructor
class RelocationLocks implements StockRelocationLock, ShipRelocationLock {

  private final HomeLocationPhases phases;
  private final AuthHelperService authHelper;

  @Override
  public @NotNull Optional<Instant> openPhaseLiveAt() {
    return phases.open().map(HomeLocationPhase::liveAt);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Optional<Instant> writeCutoff() {
    if (judgedCaller().isEmpty()) {
      return Optional.empty();
    }
    return phases.openForWrite().map(HomeLocationPhase::liveAt);
  }

  @Override
  public @NotNull @Unmodifiable Set<UUID> pendingAmong(@NotNull Collection<UUID> holders) {
    return phases.open().map(phase -> phases.pendingAmong(phase, holders)).orElse(Set.of());
  }

  @Override
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable Set<UUID> refusedAmong(@NotNull Collection<UUID> holders) {
    if (holders.isEmpty()) {
      return Set.of();
    }
    return judgedCaller()
        .map(caller -> refused(caller, holders, phases.open().orElse(null)))
        .orElse(Set.of());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireWritable(@NotNull Collection<UUID> holders) {
    if (holders.isEmpty()) {
      return;
    }
    Optional<Caller> caller = judgedCaller();
    if (caller.isPresent()
        && !refused(caller.get(), holders, phases.openForWrite().orElse(null)).isEmpty()) {
      throw new RelocationPendingException();
    }
  }

  /**
   * Returns the holders a caller is refused for in a phase.
   *
   * @param caller the caller the lock judges
   * @param holders the holders to look at
   * @param phase the open phase, or {@code null} while none is open
   * @return the pending holders, the caller itself left out on the web
   */
  private @NotNull @Unmodifiable Set<UUID> refused(
      @NotNull Caller caller,
      @NotNull Collection<UUID> holders,
      @Nullable HomeLocationPhase phase) {
    if (phase == null) {
      return Set.of();
    }
    Set<UUID> refused = new HashSet<>(phases.pendingAmong(phase, holders));
    if (caller.self() != null) {
      refused.remove(caller.self());
    }
    return Set.copyOf(refused);
  }

  /**
   * Describes the current caller as the lock judges it.
   *
   * @return the caller, or empty when the lock never refuses it: a patch change's own write, work
   *     without a caller, an admin on the web
   */
  private @NotNull Optional<Caller> judgedCaller() {
    if (PatchChangeWrites.current().isPresent()) {
      return Optional.empty();
    }
    Optional<Authentication> authentication = authHelper.currentAuthentication();
    if (authentication.isEmpty()) {
      return Optional.empty();
    }
    boolean client =
        authentication.get() instanceof SubjectAuthentication subject
            && subject.externalClient() != null;
    if (client) {
      return Optional.of(new Caller(null));
    }
    if (authHelper.isAdmin()) {
      return Optional.empty();
    }
    return Optional.of(new Caller(authHelper.currentUserId().orElse(null)));
  }

  /**
   * A caller the lock judges.
   *
   * @param self the member whose own rows the lock leaves to them, or {@code null} for an exchange
   *     client, which the lock refuses on every pending holder's rows
   */
  private record Caller(@Nullable UUID self) {}
}
