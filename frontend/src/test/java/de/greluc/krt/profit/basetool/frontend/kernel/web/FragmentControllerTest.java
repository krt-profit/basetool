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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Pins what the composed {@link FragmentController} and {@link FragmentGetMapping} carry: the
 * layout opt-in, the shared path, the {@code GET} verb and the {@code krtFetch} header condition.
 */
class FragmentControllerTest {

  @Test
  void aFragmentControllerOptsIntoTheLayoutModel() {
    assertThat(AnnotatedElementUtils.hasAnnotation(Fixture.class, UsesLayoutModel.class)).isTrue();
  }

  @Test
  void aFragmentControllerCarriesTheSharedPath() {
    RequestMapping mapping =
        AnnotatedElementUtils.findMergedAnnotation(Fixture.class, RequestMapping.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/fragments");
  }

  @Test
  void aFragmentHandlerMapsAnAjaxGet() throws NoSuchMethodException {
    Method handler = Fixture.class.getDeclaredMethod("body");

    RequestMapping mapping =
        AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/body");
    assertThat(mapping.method()).containsExactly(RequestMethod.GET);
    assertThat(mapping.headers()).containsExactly(RequestHeaders.AJAX);
  }

  @Controller
  @FragmentController("/fragments")
  private static final class Fixture {
    @FragmentGetMapping("/body")
    String body() {
      return "body";
    }
  }
}
