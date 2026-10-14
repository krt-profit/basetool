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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Only the active delegates read the purchase fields of a need (REQ-PUR-003, REQ-SUP-006). */
@ExtendWith(MockitoExtension.class)
class PurchasingNeedPriceAudienceTest {

  @Mock private PurchasingAccessPolicy accessPolicy;

  private PurchasingNeedPriceAudience audience;

  @BeforeEach
  void setUp() {
    audience = new PurchasingNeedPriceAudience(accessPolicy);
  }

  @Test
  void anActiveDelegateMayReadThePurchaseFields() {
    UUID userId = UUID.randomUUID();
    when(accessPolicy.isActiveDelegate(userId)).thenReturn(true);

    assertThat(audience.mayReadPurchaseFields(userId)).isTrue();
  }

  @Test
  void anyoneElseMayNot() {
    UUID userId = UUID.randomUUID();
    when(accessPolicy.isActiveDelegate(userId)).thenReturn(false);

    assertThat(audience.mayReadPurchaseFields(userId)).isFalse();
  }
}
