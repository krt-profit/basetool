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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.testsupport.context.ContextShape;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ratchets the backend context's shape: the scheduled methods, transactional event listeners,
 * controllers and security filter chains it holds (G-24, REQ-OPS-038).
 *
 * <p>A bean that drops out of the component scan, for instance a module moved outside {@code
 * de.greluc.krt.profit.basetool.backend}, changes a count and fails here. Change {@link #EXPECTED}
 * only together with the beans that change it. Jobs the test profile switches off are not in this
 * context and not counted.
 */
@SpringBootTest
class ContextShapeTest {

  /** The backend's shape under the test profile. */
  static final ContextShape EXPECTED = new ContextShape(6, 4, 99, 2);

  @Autowired private ApplicationContext context;

  @Test
  void theBackendContextHasItsRecordedShape() {
    assertThat(ContextShape.of(context, "de.greluc.krt.profit.basetool"))
        .as("scheduled methods, transactional listeners, controllers, filter chains")
        .isEqualTo(EXPECTED);
  }

  @Test
  void theShapeCountsEachKindOnlyInsideThePackagePrefix() {
    try (GenericApplicationContext fixture = new GenericApplicationContext()) {
      fixture.registerBean(Jobs.class);
      fixture.registerBean(Pages.class);
      fixture.registerBean(Api.class);
      fixture.registerBean("chainA", SecurityFilterChain.class, Chain::new);
      fixture.registerBean("chainB", SecurityFilterChain.class, Chain::new);
      fixture.refresh();

      assertThat(ContextShape.of(fixture, "de.greluc.krt.profit.basetool"))
          .isEqualTo(new ContextShape(3, 1, 2, 2));
      assertThat(ContextShape.of(fixture, "com.example")).isEqualTo(new ContextShape(0, 0, 0, 2));
    }
  }

  /** A fixture bean with scheduled methods, one of them repeated, and a transactional listener. */
  static class Jobs {

    /** A fixture job. */
    @Scheduled(fixedDelay = 1000)
    void first() {}

    /** A fixture job with two schedules, counted once. */
    @Scheduled(fixedDelay = 1000)
    @Scheduled(cron = "0 0 * * * *")
    void second() {}

    /** A fixture job. */
    @Scheduled(fixedDelay = 1000)
    void third() {}

    /** A fixture listener that declares its event type instead of taking it as a parameter. */
    @TransactionalEventListener(classes = Object.class)
    void onEvent() {}
  }

  /** A fixture page controller. */
  @Controller
  static class Pages {}

  /** A fixture REST controller. */
  @RestController
  static class Api {}

  /** A fixture filter chain. */
  static class Chain implements SecurityFilterChain {

    @Override
    public boolean matches(HttpServletRequest request) {
      return false;
    }

    @Override
    public List<jakarta.servlet.Filter> getFilters() {
      return List.of();
    }
  }
}
