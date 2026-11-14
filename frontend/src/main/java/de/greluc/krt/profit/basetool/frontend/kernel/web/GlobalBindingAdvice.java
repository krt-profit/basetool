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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Registers the trimming, length-capping {@code String} property editor for every controller,
 * including the REST controllers whose {@code @RequestParam} and {@code @PathVariable} strings go
 * through the {@link WebDataBinder}.
 *
 * <p>Deliberately not scoped to {@link UsesLayoutModel}.
 */
@ControllerAdvice
public class GlobalBindingAdvice {
  /**
   * Registers {@link NormalizedStringEditor} for every controller so form-bound Strings are trimmed
   * and length-capped before validation runs. The cap is {@link
   * StringNormalization#MAX_FREE_TEXT_LENGTH}, or the largest {@link Size#max()} a {@code String}
   * field of the bound form declares above it; each field's own {@link Size} then validates it.
   */
  @InitBinder
  public void initBinder(@NotNull WebDataBinder binder) {
    binder.registerCustomEditor(
        String.class,
        new NormalizedStringEditor(
            StringNormalization.capFor(longestDeclaredString(formType(binder))), true));
  }

  @Nullable
  private static Class<?> formType(@NotNull WebDataBinder binder) {
    Object target = binder.getTarget();
    if (target != null) {
      return target.getClass();
    }
    ResolvableType targetType = binder.getTargetType();
    return targetType == null ? null : targetType.resolve();
  }

  private static int longestDeclaredString(@Nullable Class<?> formType) {
    int longest = 0;
    Class<?> type = formType;
    while (type != null && type != Object.class) {
      for (Field field : type.getDeclaredFields()) {
        Size size = field.getAnnotation(Size.class);
        if (field.getType() == String.class && size != null && size.max() != Integer.MAX_VALUE) {
          longest = Math.max(longest, size.max());
        }
      }
      type = type.getSuperclass();
    }
    return longest;
  }
}
