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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.ResolvableType;
import org.springframework.validation.DataBinder;
import org.springframework.web.bind.WebDataBinder;

/** Verifies the length cap the global form binding derives from the bound form's declarations. */
class GlobalBindingAdviceTest {

  private static final int DEFAULT_CAP = StringNormalization.MAX_FREE_TEXT_LENGTH;

  private final GlobalBindingAdvice advice = new GlobalBindingAdvice();

  /**
   * A form declaring a free-text field above the default cap beside a short one.
   *
   * @param description the long free text
   * @param name the short text
   */
  record LongForm(@Size(max = 20_000) String description, @Size(max = 255) String name) {}

  /**
   * A form declaring no maximum.
   *
   * @param note the text
   */
  record PlainForm(String note) {}

  @Test
  void aFormDeclaringALongerFieldBindsTextUpToThatMaximum() {
    LongForm form = construct(LongForm.class, Map.of("description", " " + text(15_000) + " "));

    assertThat(form.description()).isEqualTo(text(15_000));
  }

  @Test
  void aFormDeclaringALongerFieldStillRefusesTextBeyondIt() {
    WebDataBinder binder = binder(LongForm.class);

    assertThatThrownBy(() -> binder.convertIfNecessary(text(20_001), String.class))
        .isInstanceOf(TypeMismatchException.class);
  }

  @Test
  void aFormWithoutADeclaredMaximumKeepsTheDefaultCap() {
    WebDataBinder binder = binder(PlainForm.class);

    assertThat(binder.convertIfNecessary(text(DEFAULT_CAP), String.class))
        .isEqualTo(text(DEFAULT_CAP));
    assertThatThrownBy(() -> binder.convertIfNecessary(text(DEFAULT_CAP + 1), String.class))
        .isInstanceOf(TypeMismatchException.class);
  }

  @Test
  void aBinderWithoutAFormKeepsTheDefaultCap() {
    WebDataBinder binder = new WebDataBinder(null);
    advice.initBinder(binder);

    assertThatThrownBy(() -> binder.convertIfNecessary(text(DEFAULT_CAP + 1), String.class))
        .isInstanceOf(TypeMismatchException.class);
  }

  private WebDataBinder binder(@NotNull Class<?> formType) {
    WebDataBinder binder = new WebDataBinder(null, "form");
    binder.setTargetType(ResolvableType.forClass(formType));
    advice.initBinder(binder);
    return binder;
  }

  private <T> T construct(@NotNull Class<T> formType, @NotNull Map<String, String> values) {
    WebDataBinder binder = binder(formType);
    binder.construct(
        new DataBinder.ValueResolver() {
          @Override
          public @Nullable Object resolveValue(@NotNull String name, @NotNull Class<?> type) {
            return values.get(name);
          }

          @Override
          public @NotNull Set<String> getNames() {
            return values.keySet();
          }
        });
    assertThat(binder.getBindingResult().hasErrors()).isFalse();
    return formType.cast(binder.getTarget());
  }

  private static String text(int length) {
    return "x".repeat(length);
  }
}
