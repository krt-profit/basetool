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

package de.greluc.krt.profit.basetool.frontend.support;

import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Type-safe Mockito matchers for the generic response-type parameter of {@code BackendApiClient}'s
 * overloads. Unlike {@code any(Class.class)}, they select the right overload and infer {@code T}
 * from the call site, so no {@code [unchecked]} warning arises; they match any argument, including
 * {@code null}.
 */
public final class ResponseTypeMatchers {

  private ResponseTypeMatchers() {}

  /**
   * Matches any {@link ParameterizedTypeReference} argument, typed to the inferred return type so
   * the matching overload is selected without an unchecked cast.
   *
   * @param <T> the payload type of the {@link ParameterizedTypeReference}, inferred from the call
   *     site
   * @return {@code null}, after registering an "any" matcher with Mockito
   */
  public static <T> ParameterizedTypeReference<T> anyTypeRef() {
    return ArgumentMatchers.any();
  }

  /**
   * Matches any {@link Class} token argument, typed to the stubbed method's inferred return type so
   * the {@link Class}-based overload is selected without an unchecked cast.
   *
   * @param <T> the type represented by the {@link Class} token, inferred from the call site
   * @return {@code null}, after registering an "any" matcher on Mockito's matcher stack
   */
  public static <T> Class<T> anyClass() {
    return ArgumentMatchers.any();
  }
}
