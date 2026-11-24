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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins that the MATERIAL and the ITEM order form hand their bound versions on as one pair, each in
 * its own slot, and take an observed pair back the same way.
 */
class JobOrderVersionEchoTest {

  /**
   * Both job-order forms, freshly created.
   *
   * @return a MATERIAL and an ITEM order form
   */
  static Stream<JobOrderVersionEcho> forms() {
    return Stream.of(new JobOrderForm(), new JobOrderItemForm());
  }

  @ParameterizedTest
  @MethodSource("forms")
  void theBoundVersionsLeaveAsOnePairInTheirOwnSlots(JobOrderVersionEcho form) {
    form.setVersion(4L);
    form.setCommentVersion(9L);

    assertThat(form.observedVersions()).isEqualTo(new JobOrderObservedVersions(4L, 9L));
  }

  @ParameterizedTest
  @MethodSource("forms")
  void anObservedPairPrefillsEachBoundVersion(JobOrderVersionEcho form) {
    form.observe(new JobOrderObservedVersions(4L, 9L));

    assertThat(form.getVersion()).isEqualTo(4L);
    assertThat(form.getCommentVersion()).isEqualTo(9L);
  }

  @ParameterizedTest
  @MethodSource("forms")
  void aCreateFormSendsAnEmptyPair(JobOrderVersionEcho form) {
    assertThat(form.observedVersions()).isEqualTo(new JobOrderObservedVersions(null, null));
  }
}
