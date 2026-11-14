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

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationStreamService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The home-location effect of a patch change (REQ-PATCH-021, REQ-PATCH-029): when its option is on,
 * the trigger opens the patch change's phase, which asks every active member whose account is older
 * than the live time. Nothing moves at the trigger; each member relocates after the choice. Once
 * the trigger has committed, the asked members' open notification streams close, so their open
 * pages reconnect through the gate.
 */
@Component
@RequiredArgsConstructor
class HomeLocationPromptEffect implements PatchEffect {

  private final HomeLocationPhases phases;
  private final NotificationStreamService streams;

  @Override
  public @NotNull PatchCounts preview(@NotNull PatchChangePlan plan) {
    if (!plan.options().homeLocationPrompt()) {
      return PatchCounts.none();
    }
    return PatchCounts.of(PatchCount.MEMBERS_ASKED, phases.countAsked(plan.liveAt()));
  }

  @Override
  public @NotNull PatchCounts apply(@NotNull PatchChangeRun run) {
    if (!run.plan().options().homeLocationPrompt()) {
      return PatchCounts.none();
    }
    List<UUID> asked =
        phases.allPending(
            new HomeLocationPhase(run.patchChangeId(), run.label(), run.plan().liveAt()));
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            streams.reconnect(asked);
          }
        });
    return PatchCounts.of(PatchCount.MEMBERS_ASKED, asked.size());
  }
}
