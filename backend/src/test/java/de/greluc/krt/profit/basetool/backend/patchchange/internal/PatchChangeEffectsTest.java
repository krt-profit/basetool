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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * The trigger's effect seam (REQ-PATCH-005): every effect bean runs, their counts merge, and the
 * first refusal stops the rest so the whole patch change rolls back.
 */
class PatchChangeEffectsTest {

  private static final PatchChangePlan PLAN =
      new PatchChangePlan(
          Instant.parse("2026-11-14T20:00:00Z"), PatchChangeOptions.presetFor(PatchKind.REBUILD));

  private static final PatchChangeRun RUN = new PatchChangeRun(UUID.randomUUID(), "4.4", PLAN);

  @Test
  void thePreviewMergesWhatEveryEffectCounts() {
    PatchChangeEffects effects =
        new PatchChangeEffects(
            List.of(
                new Fixed(PatchCounts.of(PatchCount.REFINERY_ORDERS_CANCELED, 2)),
                new Fixed(PatchCounts.none()),
                new Fixed(PatchCounts.of(PatchCount.REFINERY_ORDERS_CANCELED, 3))));

    assertThat(effects.preview(PLAN))
        .isEqualTo(PatchCounts.of(PatchCount.REFINERY_ORDERS_CANCELED, 5));
  }

  @Test
  void theTriggerRunsEveryEffectWithThePatchChange() {
    List<PatchChangeRun> seen = new ArrayList<>();
    PatchChangeEffects effects =
        new PatchChangeEffects(List.of(new Recording(seen), new Recording(seen)));

    effects.apply(RUN);

    assertThat(seen).containsExactly(RUN, RUN);
  }

  @Test
  void aRefusingEffectStopsTheRest() {
    List<PatchChangeRun> seen = new ArrayList<>();
    PatchChangeEffects effects =
        new PatchChangeEffects(List.of(new Refusing(), new Recording(seen)));

    assertThatThrownBy(() -> effects.apply(RUN)).isInstanceOf(BusinessConflictException.class);
    assertThat(seen).isEmpty();
  }

  @Test
  void withoutEffectsNothingIsCounted() {
    assertThat(new PatchChangeEffects(List.of()).apply(RUN)).isEqualTo(PatchCounts.none());
  }

  /** An effect that reports fixed counts. */
  private record Fixed(@NotNull PatchCounts counts) implements PatchEffect {

    @Override
    public @NotNull PatchCounts preview(@NotNull PatchChangePlan plan) {
      return counts;
    }

    @Override
    public @NotNull PatchCounts apply(@NotNull PatchChangeRun run) {
      return counts;
    }
  }

  /** An effect that records the runs it saw. */
  private record Recording(@NotNull List<PatchChangeRun> seen) implements PatchEffect {

    @Override
    public @NotNull PatchCounts preview(@NotNull PatchChangePlan plan) {
      return PatchCounts.none();
    }

    @Override
    public @NotNull PatchCounts apply(@NotNull PatchChangeRun run) {
      seen.add(run);
      return PatchCounts.none();
    }
  }

  /** An effect that refuses the patch change. */
  private static final class Refusing implements PatchEffect {

    @Override
    public @NotNull PatchCounts preview(@NotNull PatchChangePlan plan) {
      return PatchCounts.none();
    }

    @Override
    public @NotNull PatchCounts apply(@NotNull PatchChangeRun run) {
      throw new BusinessConflictException("error.patch_change.refused");
    }
  }
}
