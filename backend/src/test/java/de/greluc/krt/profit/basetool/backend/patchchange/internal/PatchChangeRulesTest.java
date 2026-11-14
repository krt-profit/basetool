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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The order rules of the history (REQ-PATCH-001, REQ-PATCH-003): a new live time lies after the
 * previous one and not after now, and labels increase with the live times.
 */
class PatchChangeRulesTest {

  private static final Instant NOW = Instant.parse("2026-11-14T20:00:00Z");

  @Test
  void aMissingLiveTimeIsTheMomentOfTheTrigger() {
    assertThat(PatchChangeRules.requireLiveAt(null, NOW, null)).isEqualTo(NOW);
  }

  @Test
  void theLiveTimeMayBeBackdatedToAfterThePreviousOne() {
    PatchChange previous = recorded("4.3", NOW.minus(Duration.ofDays(90)));
    Instant backdated = NOW.minus(Duration.ofHours(3));

    assertThat(PatchChangeRules.requireLiveAt(backdated, NOW, previous)).isEqualTo(backdated);
  }

  @Test
  void aLiveTimeInTheFutureIsRefused() {
    assertThatThrownBy(() -> PatchChangeRules.requireLiveAt(NOW.plusSeconds(1), NOW, null))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.live_at_future");
  }

  @Test
  void aLiveTimeNotAfterThePreviousOneIsRefused() {
    Instant previousLive = NOW.minus(Duration.ofDays(1));
    PatchChange previous = recorded("4.3", previousLive);

    assertThatThrownBy(() -> PatchChangeRules.requireLiveAt(previousLive, NOW, previous))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.live_at_not_after_previous");
  }

  @Test
  void aLabelMustBeHigherThanThePreviousOne() {
    PatchChange previous = recorded("4.4", NOW.minus(Duration.ofDays(1)));

    assertThatCode(() -> PatchChangeRules.requireLabelAfter(PatchLabel.parse("4.4.1"), previous))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> PatchChangeRules.requireLabelAfter(PatchLabel.parse("4.4.0"), previous))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.label_not_higher");
  }

  @Test
  void aCorrectedLabelMustStayLowerThanTheNextOne() {
    PatchChange next = recorded("4.5", NOW);

    assertThatCode(() -> PatchChangeRules.requireLabelBefore(PatchLabel.parse("4.4.9"), next))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> PatchChangeRules.requireLabelBefore(PatchLabel.parse("4.5"), next))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.label_not_lower");
  }

  @Test
  void theFirstPatchChangeHasNoNeighbours() {
    assertThatCode(
            () -> {
              PatchChangeRules.requireLabelAfter(PatchLabel.parse("1.0"), null);
              PatchChangeRules.requireLabelBefore(PatchLabel.parse("1.0"), null);
            })
        .doesNotThrowAnyException();
  }

  /**
   * Builds a recorded patch change with a label and a live time.
   *
   * @param label the label
   * @param liveAt the live time
   * @return the patch change
   */
  private static PatchChange recorded(String label, Instant liveAt) {
    return new PatchChange(
        UUID.randomUUID(),
        PatchLabel.parse(label),
        PatchKind.REBUILD,
        new PatchChangePlan(liveAt, PatchChangeOptions.presetFor(PatchKind.REBUILD)),
        liveAt,
        null);
  }
}
