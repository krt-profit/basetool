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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.testsupport.context.ContextShape;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Ratchets the frontend context's shape: the scheduled methods, transactional event listeners,
 * controllers and security filter chains it holds (G-24, REQ-OPS-038).
 *
 * <p>A bean that drops out of the component scan changes a count and fails here. Change {@link
 * #EXPECTED} only together with the beans that change it.
 */
@SpringBootTest
class ContextShapeTest {

  /** The frontend's shape under the test profile. */
  static final ContextShape EXPECTED = new ContextShape(0, 0, 100, 2);

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @Autowired private ApplicationContext context;

  @Test
  void theFrontendContextHasItsRecordedShape() {
    assertThat(ContextShape.of(context, "de.greluc.krt.profit.basetool"))
        .as("scheduled methods, transactional listeners, controllers, filter chains")
        .isEqualTo(EXPECTED);
  }
}
