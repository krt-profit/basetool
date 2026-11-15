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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeOptions;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchKind;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeGroup;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the reads of {@link PatchChangePageReader} against a stubbed {@link
 * PatchChangeBackendClient} (REQ-PATCH-008, ADR-0291): each part falls back on its own, the form
 * carries the default kind's preset, and a lost session propagates.
 */
class PatchChangePageReaderTest {

  private static final PatchChangeOptions REBUILD =
      new PatchChangeOptions(Set.of(WipeGroup.CONSUMABLES), true, false, true);

  private static final PatchChangeOptions FULL_WIPE =
      new PatchChangeOptions(EnumSet.allOf(WipeGroup.class), true, false, true);

  private final PatchChangeBackendClient client = mock(PatchChangeBackendClient.class);

  private final PatchChangePageReader reader = new PatchChangePageReader(client);

  /**
   * Builds form defaults with the given presets.
   *
   * @param presets the presets the backend sends
   * @return the defaults
   */
  private static PatchChangeFormDto form(List<PatchChangeFormDto.Preset> presets) {
    return new PatchChangeFormDto(
        "4.5", "4.4", Instant.parse("2026-11-14T20:00:00Z"), presets, List.of(), List.of());
  }

  @Test
  void thePageCarriesTheHistoryAndTheFormWithTheDefaultKindsPreset() {
    PatchChangeDto entry = mock(PatchChangeDto.class);
    PatchChangeFormDto defaults =
        form(
            List.of(
                new PatchChangeFormDto.Preset(PatchKind.FULL_WIPE, FULL_WIPE),
                new PatchChangeFormDto.Preset(PatchKind.REBUILD, REBUILD)));
    when(client.history()).thenReturn(List.of(entry));
    when(client.form()).thenReturn(defaults);

    PatchChangePageReader.AdminPage page = reader.adminPage();

    assertThat(page.history()).isEqualTo(new PatchChangePageReader.History(true, List.of(entry)));
    assertThat(page.form()).contains(new PatchChangePageReader.Form(defaults, REBUILD));
    assertThat(PatchChangePageReader.DEFAULT_KIND).isEqualTo(PatchKind.REBUILD);
  }

  @Test
  void aHistoryWithoutABodyIsAvailableAndEmpty() {
    when(client.history()).thenReturn(null);

    assertThat(reader.history()).isEqualTo(new PatchChangePageReader.History(true, List.of()));
  }

  @Test
  void aHistoryThatCannotBeReadIsUnavailableAndLeavesTheFormStanding() {
    PatchChangeFormDto defaults =
        form(List.of(new PatchChangeFormDto.Preset(PatchKind.REBUILD, REBUILD)));
    when(client.history()).thenThrow(new BackendServiceException("backend down", null, 503));
    when(client.form()).thenReturn(defaults);

    PatchChangePageReader.AdminPage page = reader.adminPage();

    assertThat(page.history()).isEqualTo(new PatchChangePageReader.History(false, List.of()));
    assertThat(page.form()).isPresent();
  }

  @Test
  void aFormThatCannotBeReadOrHasNoBodyIsEmpty() {
    when(client.history()).thenReturn(List.of());
    when(client.form()).thenThrow(new BackendServiceException("backend down", null, 503));

    assertThat(reader.adminPage().form()).isEmpty();

    doReturn(null).when(client).form();

    assertThat(reader.adminPage().form()).isEmpty();
  }

  @Test
  void aFormWithoutTheDefaultKindsPresetHasNoDefaults() {
    PatchChangeFormDto defaults =
        form(List.of(new PatchChangeFormDto.Preset(PatchKind.FULL_WIPE, FULL_WIPE)));
    when(client.history()).thenReturn(List.of());
    when(client.form()).thenReturn(defaults);

    assertThat(reader.adminPage().form()).contains(new PatchChangePageReader.Form(defaults, null));
  }

  @Test
  void theHistoryAloneDoesNotReadTheForm() {
    when(client.history()).thenReturn(List.of());

    reader.history();

    verify(client, never()).form();
  }

  @Test
  void aLostSessionPropagates() {
    when(client.history()).thenThrow(new ReauthenticationRequiredException("session lost", null));

    assertThatThrownBy(reader::adminPage).isInstanceOf(ReauthenticationRequiredException.class);
  }
}
