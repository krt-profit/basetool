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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Chunk-local lookups preserve source identity, name adoption and terminal disambiguation. */
class UexChunkIndexTest {

  @Test
  void externalIdentityWinsAndNewRowsAreVisibleWithinTheChunk() {
    City known = new City();
    known.setIdCity(1);
    known.setName("Known");
    UexChunkIndex<City> index =
        UexChunkIndex.of(List.of(known), City::getIdCity, City::getName, false);
    assertThat(index.resolve(1, "Renamed", City::new)).isSameAs(known);
    known.setName("Renamed");
    City added = index.resolve(2, "Added", City::new);
    added.setIdCity(2);
    added.setName("Added");
    assertThat(index.resolve(2, "Added", City::new)).isSameAs(added);
    assertThat(index.resolve(3, "Renamed", City::new)).isSameAs(known);
    known.setIdCity(3);
    assertThat(index.resolve(1, "Other", City::new)).isNotSameAs(known);
  }

  @Test
  void terminalNameAdoptionNeverOverwritesAnAlreadyClaimedIdentity() {
    Terminal unclaimed = new Terminal();
    unclaimed.setName("Shared");
    UexChunkIndex<Terminal> index =
        UexChunkIndex.of(List.of(unclaimed), Terminal::getIdTerminal, Terminal::getName, true);
    assertThat(index.resolve(10, "Shared", Terminal::new)).isSameAs(unclaimed);
    unclaimed.setIdTerminal(10);
    assertThat(index.resolve(11, "Shared", Terminal::new)).isNotSameAs(unclaimed);
    assertThat(index.resolve(10, "Shared", Terminal::new)).isSameAs(unclaimed);
  }

  @Test
  void terminalNameAdoptionConsumesEveryUnclaimedCandidateInRepositoryOrder() {
    Terminal first = new Terminal();
    first.setName("Shared");
    first.setHidden(true);
    Terminal second = new Terminal();
    second.setName("Shared");
    second.setIsAutoLoadOverridden(true);
    second.setIsAutoLoad(true);
    UexChunkIndex<Terminal> index =
        UexChunkIndex.of(List.of(first, second), Terminal::getIdTerminal, Terminal::getName, true);

    assertThat(index.resolve(10, "Shared", Terminal::new)).isSameAs(first);
    assertThat(index.resolve(11, "Shared", Terminal::new)).isSameAs(second);
    first.setIdTerminal(10);
    second.setIdTerminal(11);
    assertThat(index.resolve(10, "Renamed", Terminal::new)).isSameAs(first);
    assertThat(index.resolve(12, "Shared", Terminal::new)).isNotSameAs(first).isNotSameAs(second);
    assertThat(first.getHidden()).isTrue();
    assertThat(second.getIsAutoLoadOverridden()).isTrue();
    assertThat(second.getIsAutoLoad()).isTrue();
  }
}
