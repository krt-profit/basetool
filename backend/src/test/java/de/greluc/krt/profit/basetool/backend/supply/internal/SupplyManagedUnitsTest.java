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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The managed units read from a scope vector of the scope kernel (REQ-ORG-032, REQ-SUP-003). */
class SupplyManagedUnitsTest {

  private static final UUID HEL = UUID.randomUUID();
  private static final UUID ARGON = UUID.randomUUID();

  @Test
  void theScopeOfAnAdminWithoutAPinManagesEveryUnit() {
    SupplyManagedUnits managed = SupplyManagedUnits.of(new ScopePredicate(true, null, Set.of()));

    assertThat(managed.everyUnit()).isTrue();
    assertThat(managed.contains(ARGON)).isTrue();
    assertThat(managed.among(List.of(HEL, ARGON))).containsExactlyInAnyOrder(HEL, ARGON);
    assertThat(managed.isEmpty()).isFalse();
  }

  @Test
  void aPinnedScopeManagesThePinnedUnitOnly() {
    SupplyManagedUnits managed = SupplyManagedUnits.of(new ScopePredicate(false, HEL, Set.of()));

    assertThat(managed.everyUnit()).isFalse();
    assertThat(managed.unitIds()).containsExactly(HEL);
    assertThat(managed.contains(ARGON)).isFalse();
    assertThat(managed.among(List.of(HEL, ARGON))).containsExactly(HEL);
  }

  @Test
  void aGrantScopeManagesTheGrantedUnits() {
    SupplyManagedUnits managed =
        SupplyManagedUnits.of(new ScopePredicate(false, null, Set.of(HEL, ARGON)));

    assertThat(managed.unitIds()).containsExactlyInAnyOrder(HEL, ARGON);
    assertThat(managed.contains(ARGON)).isTrue();
  }

  @Test
  void anEmptyScopeManagesNoUnit() {
    SupplyManagedUnits managed = SupplyManagedUnits.of(new ScopePredicate(false, null, Set.of()));

    assertThat(managed.isEmpty()).isTrue();
    assertThat(managed.among(List.of(HEL))).isEmpty();
  }

  @Test
  void theListedUnitsAreACopyAndDroppedWhileEveryUnitIsManaged() {
    Set<UUID> listed = new HashSet<>(Set.of(HEL));

    SupplyManagedUnits copied = new SupplyManagedUnits(false, listed);
    listed.add(ARGON);

    assertThat(copied.unitIds()).containsExactly(HEL);
    assertThat(new SupplyManagedUnits(true, listed).unitIds()).isEmpty();
  }
}
