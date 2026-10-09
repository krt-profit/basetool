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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

class ExchangeRegistrySnapshotTest {

  @Test
  void aSuspensionIsARestrictionAndTheMergeCarriesIt() {
    ExchangeRegistrySnapshot before = snapshot(true, client(ExchangeClientStatus.ACTIVE, "a", "b"));
    ExchangeRegistrySnapshot after =
        snapshot(true, client(ExchangeClientStatus.SUSPENDED, "a", "b"));

    assertThat(ExchangeRegistrySnapshot.restricts(before, after)).isTrue();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(before, after)).isEqualTo(after);
  }

  @Test
  void aRemovedCapabilityIsARestrictionAndAnAddedOneIsNot() {
    ExchangeRegistrySnapshot before = snapshot(true, client(ExchangeClientStatus.ACTIVE, "a", "b"));
    ExchangeRegistrySnapshot after = snapshot(true, client(ExchangeClientStatus.ACTIVE, "a", "c"));

    assertThat(ExchangeRegistrySnapshot.restricts(before, after)).isTrue();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(before, after).clients().get("app"))
        .extracting(ExchangeRegistrySnapshot.Client::capabilities)
        .isEqualTo(List.of("a"));
  }

  @Test
  void activatingGrantingAndSwitchingOnAreNotRestrictions() {
    ExchangeRegistrySnapshot before = snapshot(false, client(ExchangeClientStatus.SUSPENDED, "a"));
    ExchangeRegistrySnapshot after = snapshot(true, client(ExchangeClientStatus.ACTIVE, "a", "b"));

    assertThat(ExchangeRegistrySnapshot.restricts(before, after)).isFalse();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(before, after)).isEqualTo(before);
  }

  @Test
  void switchingOffIsARestriction() {
    ExchangeRegistrySnapshot before = snapshot(true, client(ExchangeClientStatus.ACTIVE, "a"));
    ExchangeRegistrySnapshot after = snapshot(false, client(ExchangeClientStatus.ACTIVE, "a"));

    assertThat(ExchangeRegistrySnapshot.restricts(before, after)).isTrue();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(before, after).enabled()).isFalse();
  }

  @Test
  void aNewClientStaysOutOfTheMergeAndAMissingOneIsARestriction() {
    ExchangeRegistrySnapshot empty = new ExchangeRegistrySnapshot(true, new TreeMap<>());
    ExchangeRegistrySnapshot withClient = snapshot(true, client(ExchangeClientStatus.ACTIVE, "a"));

    assertThat(ExchangeRegistrySnapshot.restricts(empty, withClient)).isFalse();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(empty, withClient).clients()).isEmpty();
    assertThat(ExchangeRegistrySnapshot.restricts(withClient, empty)).isTrue();
  }

  @Test
  void aChangedLimitIsNoRestrictionAndTheMergeKeepsTheOldValue() {
    ExchangeRegistrySnapshot before = snapshot(true, client(ExchangeClientStatus.ACTIVE, "a"));
    ExchangeRegistrySnapshot after =
        new ExchangeRegistrySnapshot(
            true,
            new TreeMap<>(
                Map.of(
                    "app",
                    new ExchangeRegistrySnapshot.Client(
                        "Renamed", ExchangeClientStatus.ACTIVE, List.of("a"), "2.0.0", 5, 7))));

    assertThat(ExchangeRegistrySnapshot.restricts(before, after)).isFalse();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(before, after)).isEqualTo(before);
  }

  @Test
  void capabilitiesCompareIndependentOfOrderAndDuplicates() {
    assertThat(client(ExchangeClientStatus.ACTIVE, "b", "a", "b"))
        .isEqualTo(client(ExchangeClientStatus.ACTIVE, "a", "b"));
  }

  /**
   * Builds a snapshot with one client called {@code app}.
   *
   * @param enabled the switch
   * @param client the client
   * @return the snapshot
   */
  private static @NotNull ExchangeRegistrySnapshot snapshot(
      boolean enabled, @NotNull ExchangeRegistrySnapshot.Client client) {
    return new ExchangeRegistrySnapshot(enabled, new TreeMap<>(Map.of("app", client)));
  }

  /**
   * Builds a client entry without limits.
   *
   * @param status the status
   * @param capabilities the scopes
   * @return the entry
   */
  private static @NotNull ExchangeRegistrySnapshot.Client client(
      @NotNull ExchangeClientStatus status, String... capabilities) {
    return new ExchangeRegistrySnapshot.Client(
        "App", status, List.of(capabilities), null, null, null);
  }
}
