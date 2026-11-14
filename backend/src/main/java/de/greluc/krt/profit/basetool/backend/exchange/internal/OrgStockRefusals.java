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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeSetAdapter.Outcome;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.EntryRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.OrgStockPlan.PoolWrite;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangePatchChangeRefDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns the Lager's refusal of an org-stock op into the op's outcome, naming the patch change when
 * the entry's own last change in its pool was a patch change's (REQ-PATCH-057).
 */
@Component
@RequiredArgsConstructor
class OrgStockRefusals {

  private final ExchangePatchRemovals patchRemovals;
  private final StockReads stockReads;

  /**
   * Decides the outcome of a refused op: a {@code VERSION_CONFLICT} names the patch change behind
   * the entry's last change, and a {@code NOT_PERMITTED} on an entry a patch change removed becomes
   * {@code REMOVED_ELSEWHERE}; every other refusal keeps its reason.
   *
   * @param write the refused op
   * @param refusal the Lager's refusal
   * @return the op's outcome
   */
  @NotNull
  Outcome outcome(@NotNull PoolWrite write, @NotNull PoolRefusal refusal) {
    EntryRef entry = write.entry();
    if (entry != null && refusal == PoolRefusal.VERSION_CONFLICT) {
      return Outcome.versionConflict(lastPatchChange(entry));
    }
    if (entry != null && refusal == PoolRefusal.NOT_PERMITTED) {
      ExchangePatchChangeRefDto patchChange = lastPatchChange(entry);
      if (patchChange != null && stockReads.findRow(entry.entryId()).isEmpty()) {
        return Outcome.removedByPatch(patchChange);
      }
    }
    return Outcome.rejected(refusal.name());
  }

  /**
   * Names the patch change behind the entry's last change in the pool the op names.
   *
   * @param entry the pool and entry
   * @return the patch change, or {@code null} when that change came from another channel
   */
  private @Nullable ExchangePatchChangeRefDto lastPatchChange(@NotNull EntryRef entry) {
    return patchRemovals.latestPatchChangeOfOrgEntry(entry.poolUnitId(), entry.entryId());
  }
}
