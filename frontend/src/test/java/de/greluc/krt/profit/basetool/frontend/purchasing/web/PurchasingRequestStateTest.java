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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import org.junit.jupiter.api.Test;

/** Verifies one capability read across cold or warm purchasing navigation state. */
class PurchasingRequestStateTest {

  @Test
  void navigationAndRepeatedPageReadsShareOneSnapshot() {
    for (boolean warm : new boolean[] {false, true}) {
      PurchasingBackendClient client = mock(PurchasingBackendClient.class);
      PurchasingStateDto snapshot =
          new PurchasingStateDto(true, true, false, false, false, false, false);
      when(client.state()).thenReturn(snapshot);
      PurchasingRequestState reader = new PurchasingRequestState(client);
      PurchasingFeatureState navigation = new PurchasingFeatureState(reader);
      if (warm) {
        assertThat(navigation.enabled()).isTrue();
      }

      assertThat(reader.read()).isSameAs(snapshot);
      assertThat(navigation.enabled()).isTrue();
      assertThat(reader.read()).isSameAs(snapshot);

      verify(client, times(1)).state();
    }
  }

  @Test
  void differentRequestReadersNeverSharePrincipalDependentCapabilities() {
    PurchasingBackendClient client = mock(PurchasingBackendClient.class);
    PurchasingStateDto first =
        new PurchasingStateDto(true, true, false, false, false, false, false);
    PurchasingStateDto second =
        new PurchasingStateDto(true, false, false, false, false, false, false);
    when(client.state()).thenReturn(first, second);

    assertThat(new PurchasingRequestState(client).read()).isSameAs(first);
    assertThat(new PurchasingRequestState(client).read()).isSameAs(second);

    verify(client, times(2)).state();
  }
}
