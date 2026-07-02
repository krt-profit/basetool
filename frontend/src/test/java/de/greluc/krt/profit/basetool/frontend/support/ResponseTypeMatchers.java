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
 * Type-safe Mockito argument matchers for the generic response-type parameter of {@code
 * BackendApiClient}'s {@code get}/{@code getCached}/{@code post}/… overloads.
 *
 * <p>Stubbing or verifying those calls needs a matcher that (a) selects the right overload — {@link
 * ParameterizedTypeReference} versus {@link Class} — and (b) carries a concrete generic type so
 * {@code javac} emits no {@code [unchecked]} warning. The naive {@code
 * any(ParameterizedTypeReference.class)} / {@code any(Class.class)} passes a <em>raw</em> {@code
 * Class} token, which erases the method's type variable and produces exactly that warning at every
 * call site.
 *
 * <p>These helpers wrap {@link ArgumentMatchers#any()} behind a parameterized return type instead.
 * The type variable {@code T} is inferred from each call site (the stubbed method's return type for
 * {@code when(...).thenReturn(...)}, or {@link Object} when the result is discarded by {@code
 * verify(...)}), so the matcher stays fully typed and no suppression is required. Runtime behaviour
 * is that of {@code any()}: it matches any argument, including {@code null}.
 */
public final class ResponseTypeMatchers {

  private ResponseTypeMatchers() {}

  /**
   * Matches any {@link ParameterizedTypeReference} argument, typed to the stubbed method's inferred
   * return type so the {@code ParameterizedTypeReference}-based overload is selected without an
   * unchecked cast.
   *
   * @param <T> the payload type carried by the {@link ParameterizedTypeReference}, inferred from
   *     the call site
   * @return {@code null}, after registering an "any" matcher on Mockito's matcher stack
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
