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

package de.greluc.krt.profit.basetool.backend.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Memoises a value for the rest of the current HTTP request under a typed {@link Key}
 * (REQ-DATA-003).
 *
 * <p>{@link #get(HttpServletRequest, Key, Supplier)} works on a given request; {@link
 * #getIfBound(Key, Supplier)} uses the thread-bound request and returns {@code null} without one.
 * Memoised values must not be {@code null}, and must not change within the request.
 */
public final class RequestMemo {

  private RequestMemo() {}

  /**
   * A typed request-attribute key. Create one per memoised value as a {@code private static final}
   * constant with {@link #of(Class, String)}; the owner's class name keeps the attribute from
   * clashing with anything else set on the request.
   *
   * @param <T> the type of the memoised value
   */
  public static final class Key<T> {

    /** The request-attribute name the value is stored under. */
    private final String attribute;

    private Key(@NotNull String attribute) {
      this.attribute = attribute;
    }

    /**
     * Creates the key for one memoised value of {@code owner}.
     *
     * @param owner the class that owns the memo; its fully qualified name prefixes the attribute
     * @param name the memo's name within {@code owner}
     * @param <T> the type of the memoised value
     * @return a key whose attribute is {@code owner.getName() + "." + name}
     */
    @NotNull
    public static <T> Key<T> of(@NotNull Class<?> owner, @NotNull String name) {
      return new Key<>(owner.getName() + "." + name);
    }

    /**
     * The request-attribute name this key stores its value under.
     *
     * @return the attribute name; never {@code null}
     */
    @NotNull
    public String attribute() {
      return attribute;
    }

    /**
     * Reads an attribute value back as this key's type.
     *
     * @param raw the attribute value, {@code null} when the attribute is absent
     * @return {@code raw} typed as {@code T}
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private T cast(@Nullable Object raw) {
      return (T) raw;
    }
  }

  /**
   * Returns the value memoised under {@code key} on {@code request}, computing and storing it on
   * the first call of the request.
   *
   * @param request the request to memoise on
   * @param key the memo's key
   * @param compute produces the value on a miss; must not return {@code null}
   * @param <T> the value type
   * @return the memoised value; never {@code null}
   */
  @NotNull
  public static <T> T get(
      @NotNull HttpServletRequest request,
      @NotNull Key<T> key,
      @NotNull Supplier<? extends T> compute) {
    T cached = key.cast(request.getAttribute(key.attribute()));
    if (cached != null) {
      return cached;
    }
    T value = compute.get();
    request.setAttribute(key.attribute(), value);
    return value;
  }

  /**
   * Returns the value memoised under {@code key} on the request bound to the current thread,
   * computing and storing it on the first call of the request.
   *
   * @param key the memo's key
   * @param compute produces the value on a miss; must not return {@code null}
   * @param <T> the value type
   * @return the memoised value, or {@code null} when no request is bound to the current thread (and
   *     then {@code compute} is not called)
   */
  @Nullable
  public static <T> T getIfBound(@NotNull Key<T> key, @NotNull Supplier<? extends T> compute) {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return null;
    }
    T cached = key.cast(attrs.getAttribute(key.attribute(), RequestAttributes.SCOPE_REQUEST));
    if (cached != null) {
      return cached;
    }
    T value = compute.get();
    attrs.setAttribute(key.attribute(), value, RequestAttributes.SCOPE_REQUEST);
    return value;
  }
}
