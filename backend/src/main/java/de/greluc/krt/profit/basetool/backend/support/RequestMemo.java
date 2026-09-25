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
 * The one way the backend memoises a value for the rest of the current HTTP request (REQ-DATA-003).
 *
 * <p>Several hot paths — the scope predicates behind every list query and {@code canSee*} gate, the
 * cascaded leadership reach, the Staffel projection of every embedded user, the appointment ladder
 * — each ask the same membership question many times per request. They used to cache the answer in
 * a request attribute, each with its own string key and its own unchecked cast on the way back out.
 * A typed {@link Key} now carries the value type, so the cast lives in exactly one place: a {@code
 * Key<T>} is the only thing that writes its attribute, so what it reads back is a {@code T}.
 *
 * <p>Two flavours, matching how the callers reach the request:
 *
 * <ul>
 *   <li>{@link #get(HttpServletRequest, Key, Supplier)} works on a request the caller already holds
 *       — an injected request proxy, which fails outside a request exactly as it did before.
 *   <li>{@link #getIfBound(Key, Supplier)} reads the request bound to the current thread through
 *       {@link RequestContextHolder} and answers {@code null} when there is none, so a scheduled
 *       job or a plain unit test computes without a memo.
 * </ul>
 *
 * <p>Allocation is the value itself and nothing else: no wrapper is stored, so a memoised value may
 * not be {@code null} — callers memoise an {@code Optional}, a collection or a {@code Boolean}
 * instead, which is what every caller already did.
 *
 * <p>Every memo assumes its answer does not change within the request that asks; a request that
 * writes what a memo holds and then reads it again must not go through the memo.
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
