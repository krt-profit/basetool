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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The UEX commodity sync's twin merge runs each pair on its own (REQ-DATA-046). */
@ExtendWith(MockitoExtension.class)
class UexCommodityTwinMergerTest {

  private static final UUID RUN = UUID.randomUUID();

  @Mock private MaterialRepository materials;
  @Mock private MaterialMergeService merges;
  @Mock private SyncReportService reports;
  @Mock private SyncChunkWriter chunkWriter;

  private UexCommodityTwinMerger merger;

  @BeforeEach
  void setUp() {
    merger = new UexCommodityTwinMerger(materials, merges, reports, chunkWriter);
    when(chunkWriter.inNewTransaction(any()))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
  }

  @Test
  void aTwinMergesAndIsReported() {
    Material fireworks = gameRow("Fireworks", 30, UUID.randomUUID());
    Material partyFavors = wikiRow("Party Favors", 54, fireworks.getP4kSubtypeUuid());
    when(materials.findAll()).thenReturn(List.of(fireworks, partyFavors));
    when(merges.merge(partyFavors.getId(), fireworks.getId(), MaterialMergeRule.UEX_WIKI_KEY))
        .thenReturn(true);
    when(materials.findById(fireworks.getId())).thenReturn(Optional.of(fireworks));

    assertThat(merger.mergeTwins(List.of(listing(30), listing(54)), RUN)).isEqualTo(1);

    verify(reports)
        .logUexEvent(
            eq(RUN),
            eq(SyncEventType.SOURCE_RECORDS_MERGED),
            eq("commodity"),
            any(),
            eq("Fireworks"),
            contains("retiredMaterial=" + partyFavors.getId()));
    verify(reports).recordApplied(1);
  }

  @Test
  void aFailingMergeIsCountedAndTheNextPairStillMerges() {
    Material fireworks = gameRow("Fireworks", 30, UUID.randomUUID());
    Material partyFavors = wikiRow("Party Favors", 54, fireworks.getP4kSubtypeUuid());
    Material ore = gameRow("Ore", 31, UUID.randomUUID());
    Material oreTwin = wikiRow("Ore Twin", 55, ore.getP4kSubtypeUuid());
    when(materials.findAll()).thenReturn(List.of(fireworks, partyFavors, ore, oreTwin));
    when(merges.merge(partyFavors.getId(), fireworks.getId(), MaterialMergeRule.UEX_WIKI_KEY))
        .thenThrow(new IllegalStateException("unique key"));
    when(merges.merge(oreTwin.getId(), ore.getId(), MaterialMergeRule.UEX_WIKI_KEY))
        .thenReturn(true);
    when(materials.findById(ore.getId())).thenReturn(Optional.of(ore));

    assertThat(merger.mergeTwins(List.of(listing(30), listing(54), listing(31), listing(55)), RUN))
        .isEqualTo(1);

    verify(reports).recordFailed(1);
    verify(reports).recordApplied(1);
  }

  @Test
  void aPairTheMergeRefusesIsNotReported() {
    Material fireworks = gameRow("Fireworks", 30, UUID.randomUUID());
    Material partyFavors = wikiRow("Party Favors", 54, fireworks.getP4kSubtypeUuid());
    when(materials.findAll()).thenReturn(List.of(fireworks, partyFavors));

    assertThat(merger.mergeTwins(List.of(listing(30), listing(54)), RUN)).isZero();

    verify(reports, never()).logUexEvent(any(), any(), anyString(), any(), any(), anyString());
    verify(reports).recordApplied(0);
  }

  private static Material gameRow(String name, int uexId, UUID resourceType) {
    Material row = row(name, uexId, MaterialSourceSystem.UEX_ONLY);
    row.setP4kUuid(UUID.randomUUID());
    row.setP4kSubtypeUuid(resourceType);
    return row;
  }

  private static Material wikiRow(String name, int uexId, UUID wikiUuid) {
    Material row = row(name, uexId, MaterialSourceSystem.BOTH);
    row.setScwikiUuid(wikiUuid);
    return row;
  }

  private static Material row(String name, int uexId, MaterialSourceSystem source) {
    Material row = new Material();
    row.setId(UUID.randomUUID());
    row.setName(name);
    row.setIdCommodity(uexId);
    row.setSourceSystems(source);
    return row;
  }

  private static UexCommodityDto listing(int id) {
    return UexCommodityDto.builder().id(id).name("Commodity " + id).build();
  }
}
