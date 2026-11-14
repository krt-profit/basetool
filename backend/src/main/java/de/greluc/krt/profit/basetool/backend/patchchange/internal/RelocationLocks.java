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
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The relocation lock (REQ-PATCH-023, ADR-0383): until a member relocates in the open home-location
 * phase, their stock rows and ships from before its live time change only for the member and admins
 * on the web. An exchange client is refused always, the member's own included; a patch change's own
 * writes and work without a caller are never refused.
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
  public @NotNull @Unmodifiable Set<UUID> pendingAmong(@NotNull Collection<UUID> holders) {
    return phases.open().map(phase -> phases.pendingAmong(phase, holders)).orElse(Set.of());
  }

  @Override
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable Set<UUID> refusedAmong(@NotNull Collection<UUID> holders) {
    if (holders.isEmpty() || PatchChangeWrites.current().isPresent()) {
      return Set.of();
    }
    Optional<Authentication> caller = authHelper.currentAuthentication();
    if (caller.isEmpty()) {
      return Set.of();
    }
    boolean client =
        caller.get() instanceof SubjectAuthentication subject && subject.externalClient() != null;
    if (!client && authHelper.isAdmin()) {
      return Set.of();
    }
    Set<UUID> refused = new HashSet<>(pendingAmong(holders));
    if (!client) {
      authHelper.currentUserId().ifPresent(refused::remove);
    }
    return Set.copyOf(refused);
  }

  @Override
  @Transactional(readOnly = true)
  public void requireWritable(@NotNull Collection<UUID> holders) {
    if (!refusedAmong(holders).isEmpty()) {
      throw new RelocationPendingException();
    }
  }
}
