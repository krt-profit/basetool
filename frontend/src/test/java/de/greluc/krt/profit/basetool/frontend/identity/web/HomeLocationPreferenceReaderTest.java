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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyHomeLocationResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.ProfileHomeLocationSection;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies how the pages read the member's home-location setting (REQ-PATCH-030, REQ-PATCH-033):
 * the form default, and a failed read that degrades to "no choice" instead of failing the page.
 */
@ExtendWith(MockitoExtension.class)
class HomeLocationPreferenceReaderTest {

  private static final UUID LORVILLE = UUID.fromString("5a1e0000-0000-4000-8000-00000000b001");
  private static final UUID LEVSKI = UUID.fromString("5a1e0000-0000-4000-8000-00000000b002");

  @Mock private IdentityBackendClient identityClient;

  private HomeLocationPreferenceReader reader;

  @BeforeEach
  void setUp() {
    reader = new HomeLocationPreferenceReader(identityClient);
  }

  @Test
  void theFormDefaultIsTheStoredHomeLocation() {
    when(identityClient.myHomeLocation()).thenReturn(new MyHomeLocationResponse(LORVILLE, 2L));

    assertThat(reader.formDefault()).contains(LORVILLE);
  }

  @Test
  void aMemberWithoutAChoiceHasNoFormDefault() {
    when(identityClient.myHomeLocation()).thenReturn(new MyHomeLocationResponse(null, 0L));

    assertThat(reader.formDefault()).isEmpty();
  }

  @Test
  void aFailedReadLeavesTheFormWithoutADefault() {
    when(identityClient.myHomeLocation())
        .thenThrow(new BackendServiceException("down", null, 503, null, null, List.of(), null));

    assertThat(reader.formDefault()).isEmpty();
  }

  @Test
  void aStoredChoiceTheProfileNoLongerOffersShowsAsNoneWithItsVersion() {
    when(identityClient.myHomeLocation()).thenReturn(new MyHomeLocationResponse(LEVSKI, 5L));
    when(identityClient.homeLocations())
        .thenReturn(List.of(new LocationDto(LORVILLE, "Lorville", null, false, true, 0L)));

    ProfileHomeLocationSection section = reader.profileSection();

    assertThat(section.homeLocationId()).isNull();
    assertThat(section.version()).isEqualTo(5L);
    assertThat(section.options()).extracting(LocationDto::id).containsExactly(LORVILLE);
  }
}
