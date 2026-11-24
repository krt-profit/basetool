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

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(before, after).isOn(ExchangeSwitch.GLOBAL))
        .isFalse();
  }

  @Test
  void switchingTheBankOffIsARestrictionAndSwitchingItOnIsNot() {
    ExchangeRegistrySnapshot bankOn = allOnBut();
    ExchangeRegistrySnapshot bankOff = allOnBut(ExchangeSwitch.BANK);

    assertThat(ExchangeRegistrySnapshot.restricts(bankOn, bankOff)).isTrue();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(bankOn, bankOff).isOn(ExchangeSwitch.BANK))
        .isFalse();
    assertThat(
            ExchangeRegistrySnapshot.restrictiveMerge(bankOn, bankOff).isOn(ExchangeSwitch.GLOBAL))
        .isTrue();
    assertThat(ExchangeRegistrySnapshot.restricts(bankOff, bankOn)).isFalse();
  }

  @Test
  void switchingAJobOrderSwitchOffIsARestrictionThatTouchesOnlyThatSwitch() {
    ExchangeRegistrySnapshot allOn = allOnBut();
    ExchangeRegistrySnapshot familyOff = allOnBut(ExchangeSwitch.JOB_ORDERS);
    ExchangeRegistrySnapshot writesOff = allOnBut(ExchangeSwitch.JOB_ORDER_WRITES);

    assertThat(ExchangeRegistrySnapshot.restricts(allOn, familyOff)).isTrue();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(allOn, familyOff)).isEqualTo(familyOff);
    assertThat(ExchangeRegistrySnapshot.restricts(allOn, writesOff)).isTrue();
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(allOn, writesOff)).isEqualTo(writesOff);
    assertThat(ExchangeRegistrySnapshot.restrictiveMerge(familyOff, writesOff))
        .isEqualTo(allOnBut(ExchangeSwitch.JOB_ORDERS, ExchangeSwitch.JOB_ORDER_WRITES));
    assertThat(ExchangeRegistrySnapshot.restricts(familyOff, allOn)).isFalse();
    assertThat(ExchangeRegistrySnapshot.restricts(writesOff, allOn)).isFalse();
  }

  @Test
  void aMissingSettingsRowAndASwitchedOffSnapshotGrantNoSwitch() {
    ExchangeRegistrySnapshot none = ExchangeRegistrySnapshot.of(null, List.of());

    assertThat(none).isEqualTo(allOnBut(ExchangeSwitch.values()));
    assertThat(ExchangeRegistrySnapshot.switchedOff(new TreeMap<>())).isEqualTo(none);
  }

  @Test
  void aNewClientStaysOutOfTheMergeAndAMissingOneIsARestriction() {
    ExchangeRegistrySnapshot empty = allOnBut();
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
            EnumSet.allOf(ExchangeSwitch.class),
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
   * @param enabled the global switch; every other switch is on
   * @param client the client
   * @return the snapshot
   */
  private static @NotNull ExchangeRegistrySnapshot snapshot(
      boolean enabled, @NotNull ExchangeRegistrySnapshot.Client client) {
    Set<ExchangeSwitch> on = EnumSet.allOf(ExchangeSwitch.class);
    if (!enabled) {
      on.remove(ExchangeSwitch.GLOBAL);
    }
    return new ExchangeRegistrySnapshot(on, new TreeMap<>(Map.of("app", client)));
  }

  /**
   * Builds a snapshot without clients with every switch on but the given ones.
   *
   * @param off the switches that are off
   * @return the snapshot
   */
  private static @NotNull ExchangeRegistrySnapshot allOnBut(@NotNull ExchangeSwitch... off) {
    Set<ExchangeSwitch> on = EnumSet.allOf(ExchangeSwitch.class);
    List.of(off).forEach(on::remove);
    return new ExchangeRegistrySnapshot(on, new TreeMap<>());
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
