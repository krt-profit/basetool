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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.PromotionEligibilityResponse;
import de.greluc.krt.profit.basetool.backend.service.PromotionEligibilityService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-Mockito unit tests for {@link PromotionEligibilityController}. The controller is a thin
 * pass-through: the {@code /my} endpoints receive the caller's JWT subject via
 * {@code @CurrentUserSub} and forward it to the service, while the {@code /user/{userId}} branch is
 * the officer/admin view gated by a method-level {@code @PreAuthorize}. These tests pin the
 * subject/argument forwarding for all three endpoints; the JWT-subject extraction and its {@code
 * AccessDenied} failure modes now live in {@code CurrentUserArgumentResolver} and are covered by
 * {@code CurrentUserArgumentResolverTest}.
 */
@ExtendWith(MockitoExtension.class)
class PromotionEligibilityControllerTest {

  @Mock private PromotionEligibilityService service;

  @InjectMocks private PromotionEligibilityController controller;

  private static PromotionEligibilityResponse eligibility(UUID userId, int from, int to) {
    return new PromotionEligibilityResponse(userId, from, to, true, true, List.of());
  }

  @Test
  void myEligibility_forwardsCallerSubToService() {
    UUID sub = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    List<PromotionEligibilityResponse> expected = List.of(eligibility(sub, 20, 19));
    when(service.evaluateAllForUser(sub)).thenReturn(expected);

    List<PromotionEligibilityResponse> result = controller.myEligibility(sub);

    assertThat(result).isSameAs(expected);
    verify(service).evaluateAllForUser(sub);
  }

  @Test
  void myEligibilityForRanks_forwardsSubAndRanks() {
    UUID sub = UUID.fromString("b0b00000-0000-4000-8000-000000000002");
    PromotionEligibilityResponse expected = eligibility(sub, 20, 19);
    when(service.evaluateForRanks(sub, 20, 19)).thenReturn(expected);

    PromotionEligibilityResponse result = controller.myEligibilityForRanks(20, 19, sub);

    assertThat(result).isSameAs(expected);
    verify(service).evaluateForRanks(sub, 20, 19);
  }

  @Test
  void eligibilityForUser_forwardsTargetUserIdToService() {
    UUID targetUserId = UUID.fromString("ca401000-0000-4000-8000-000000000003");
    List<PromotionEligibilityResponse> expected = List.of(eligibility(targetUserId, 20, 19));
    when(service.evaluateAllForUserAsAdmin(targetUserId)).thenReturn(expected);

    List<PromotionEligibilityResponse> result = controller.eligibilityForUser(targetUserId);

    assertThat(result).isSameAs(expected);
    verify(service).evaluateAllForUserAsAdmin(targetUserId);
  }
}
