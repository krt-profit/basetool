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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link PurchasingFeatureState}: the short-lived cache and its failure mode. */
@ExtendWith(MockitoExtension.class)
class PurchasingFeatureStateTest {

  @Mock private PurchasingBackendClient purchasingClient;

  private PurchasingFeatureState featureState;

  @BeforeEach
  void setUp() {
    featureState = new PurchasingFeatureState(purchasingClient);
  }

  private static PurchasingStateDto state(boolean enabled) {
    return new PurchasingStateDto(enabled, false, false, false, false, false, false);
  }

  @Test
  void aSuccessfulAnswerIsReadOnceAndRemembered() {
    when(purchasingClient.state()).thenReturn(state(true));

    assertThat(featureState.enabled()).isTrue();
    assertThat(featureState.enabled()).isTrue();
    assertThat(featureState.enabled()).isTrue();

    verify(purchasingClient, times(1)).state();
  }

  @Test
  void anOffAnswerIsRememberedToo() {
    when(purchasingClient.state()).thenReturn(state(false));

    assertThat(featureState.enabled()).isFalse();
    assertThat(featureState.enabled()).isFalse();

    verify(purchasingClient, times(1)).state();
  }

  @Test
  void anEmptyStateBodyAnswersOffAndIsRemembered() {
    when(purchasingClient.state()).thenReturn(null);

    assertThat(featureState.enabled()).isFalse();
    assertThat(featureState.enabled()).isFalse();

    verify(purchasingClient, times(1)).state();
  }

  @Test
  void evictingMakesTheNextCallReadTheBackendAgain() {
    when(purchasingClient.state()).thenReturn(state(true), state(false));

    assertThat(featureState.enabled()).isTrue();
    featureState.evict();
    assertThat(featureState.enabled()).isFalse();

    verify(purchasingClient, times(2)).state();
  }

  @Test
  void aFailedReadAnswersOffAndIsNotRemembered() {
    when(purchasingClient.state())
        .thenThrow(new IllegalStateException("down"))
        .thenReturn(state(true));

    assertThat(featureState.enabled()).isFalse();
    assertThat(featureState.enabled()).isTrue();

    verify(purchasingClient, times(2)).state();
  }

  @Test
  void aFailedReadAfterAnEvictionDoesNotResurrectTheEvictedAnswer() {
    when(purchasingClient.state())
        .thenReturn(state(true))
        .thenThrow(new IllegalStateException("down"));

    assertThat(featureState.enabled()).isTrue();
    featureState.evict();

    assertThat(featureState.enabled()).isFalse();
  }

  @Test
  void aRememberedAnswerIsKeptForThirtySeconds() {
    assertThat(PurchasingFeatureState.TTL).isEqualTo(Duration.ofSeconds(30));
  }
}
