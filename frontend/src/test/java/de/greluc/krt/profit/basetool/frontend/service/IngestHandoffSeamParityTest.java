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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.exchange.web.ConnectedAppsConfirmRelayController;
import de.greluc.krt.profit.basetool.frontend.model.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.model.StagedHandoff;
import de.greluc.krt.profit.basetool.testsupport.exchange.ExchangeSeam;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Pins the frontend's reading of the handoffs the ingest gateway stages to {@link ExchangeSeam}
 * (REQ-XCH-037): the Redis key prefix, the staged value's members, the handoff kinds and the
 * members of a staged mass change. The gateway pins its writing to the same class.
 */
class IngestHandoffSeamParityTest {

  @Test
  void theHandoffKeyPrefixIsTheSeams() throws Exception {
    Field prefix = IngestHandoffService.class.getDeclaredField("KEY_PREFIX");
    prefix.setAccessible(true);
    assertThat(prefix.get(null)).isEqualTo(ExchangeSeam.HANDOFF_PREFIX);
  }

  @Test
  void theStagedValueAndKindsAreTheSeams() {
    assertThat(
            Arrays.stream(StagedHandoff.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactlyElementsOf(ExchangeSeam.HANDOFF_FIELDS);
    assertThat(Arrays.stream(HandoffKind.values()).map(Enum::name))
        .containsExactlyElementsOf(ExchangeSeam.HANDOFF_KINDS);
  }

  @Test
  void theConfirmationPageReadsTheSeamsMassChangeMembers() {
    assertMassChangeMembers(ExchangeSeam.MASS_CHANGE_FIELDS);
    Set<String> renamed = new HashSet<>(ExchangeSeam.MASS_CHANGE_FIELDS);
    renamed.remove("installationKey");
    renamed.add("installation");
    assertThatThrownBy(() -> assertMassChangeMembers(renamed)).isInstanceOf(AssertionError.class);
  }

  /**
   * Asserts that the confirmation page reads exactly the given members of a staged mass change.
   *
   * @param expected the members
   */
  private static void assertMassChangeMembers(@NotNull Set<String> expected) {
    assertThat(
            Arrays.stream(
                    ConnectedAppsConfirmRelayController.StagedMassChange.class
                        .getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet()))
        .hasSize(5)
        .isEqualTo(expected);
  }
}
