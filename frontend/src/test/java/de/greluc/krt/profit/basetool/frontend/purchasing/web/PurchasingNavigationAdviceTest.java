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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Unit tests for {@link PurchasingNavigationAdvice}: when the sidebar shows the Ankauf entry. */
@ExtendWith(MockitoExtension.class)
class PurchasingNavigationAdviceTest {

  @Mock private PurchasingFeatureState featureState;
  @Mock private FrontendAuthHelperService authHelper;
  @Mock private PurchasingBackendClient purchasingClient;
  @Mock private SupplyBackendClient supplyClient;
  @Mock private QualityTierCatalog qualityTierCatalog;

  private PurchasingNavigationAdvice advice;

  @BeforeEach
  void setUp() {
    advice = new PurchasingNavigationAdvice(featureState, authHelper);
  }

  private MockHttpServletRequest renderingRequest() throws NoSuchMethodException {
    PurchasingPageController pages =
        new PurchasingPageController(
            purchasingClient, supplyClient, featureState, qualityTierCatalog);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
        new HandlerMethod(
            pages,
            PurchasingPageController.class.getMethod("delegates", String.class, Model.class)));
    return request;
  }

  private MockHttpServletRequest bodyWritingRequest() throws NoSuchMethodException {
    PurchasingProxyController proxy = new PurchasingProxyController(purchasingClient);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
        new HandlerMethod(
            proxy,
            PurchasingProxyController.class.getMethod("forumText", String.class, String.class)));
    return request;
  }

  @Test
  void aRenderedPageOfAnAuthenticatedCallerShowsTheEntryWhileTheSurfaceIsOn() throws Exception {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(featureState.enabled()).thenReturn(true);

    assertThat(advice.purchasingEnabled(renderingRequest())).isTrue();
  }

  @Test
  void aRequestWithoutAMatchedHandlerCountsAsRendered() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(featureState.enabled()).thenReturn(true);

    assertThat(advice.purchasingEnabled(new MockHttpServletRequest())).isTrue();
  }

  @Test
  void aHandlerThatWritesItsOwnBodyHidesTheEntryWithoutAskingAnyone() throws Exception {
    assertThat(advice.purchasingEnabled(bodyWritingRequest())).isFalse();

    verifyNoInteractions(authHelper, featureState);
  }

  @Test
  void anAnonymousCallerNeverSeesTheEntryAndTheSwitchIsNotRead() throws Exception {
    when(authHelper.isAuthenticated()).thenReturn(false);

    assertThat(advice.purchasingEnabled(renderingRequest())).isFalse();

    verifyNoInteractions(featureState);
  }

  @Test
  void aSwitchedOffSurfaceHidesTheEntryFromAnAuthenticatedCaller() throws Exception {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(featureState.enabled()).thenReturn(false);

    assertThat(advice.purchasingEnabled(renderingRequest())).isFalse();
  }
}
