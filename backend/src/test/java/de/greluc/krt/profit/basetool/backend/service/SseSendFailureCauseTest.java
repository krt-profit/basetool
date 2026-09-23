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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Pins the one {@code cause} tag mapping both SSE streams share (BE-MOD-06): the three-value
 * vocabulary is chosen by exception type, subclasses included, and nothing else reaches a label.
 */
class SseSendFailureCauseTest {

  @Test
  void anIoExceptionAndItsSubclassesAreTheBenignHangUp() {
    assertThat(SseSendFailureCause.tagOf(new IOException("broken pipe")))
        .isEqualTo(MetricNames.CAUSE_IO);
    assertThat(SseSendFailureCause.tagOf(new EOFException())).isEqualTo(MetricNames.CAUSE_IO);
  }

  @Test
  void anIllegalStateExceptionIsTheLifecycleRace() {
    assertThat(SseSendFailureCause.tagOf(new IllegalStateException("already completed")))
        .isEqualTo(MetricNames.CAUSE_ILLEGAL_STATE);
  }

  @Test
  void everythingElseIsOther_evenAnUncheckedWrapperOfAnIoException() {
    // The mapping reads the thrown type only; it does not unwrap causes.
    assertThat(SseSendFailureCause.tagOf(new UncheckedIOException(new IOException())))
        .isEqualTo(MetricNames.CAUSE_OTHER);
    assertThat(SseSendFailureCause.tagOf(new RuntimeException()))
        .isEqualTo(MetricNames.CAUSE_OTHER);
  }
}
