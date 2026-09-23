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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pins the contract of the one request memo the scope resolvers, the cascade and the user mapper
 * share (BE-SIMP-09): computed once per request and key, kept apart by key, and a plain computation
 * — or nothing at all — outside a request.
 */
class RequestMemoTest {

  private static final RequestMemo.Key<Optional<String>> NAME =
      RequestMemo.Key.of(RequestMemoTest.class, "name");
  private static final RequestMemo.Key<Boolean> FLAG =
      RequestMemo.Key.of(RequestMemoTest.class, "flag");
  private static final RequestMemo.Key<Map<String, Integer>> MAP =
      RequestMemo.Key.of(RequestMemoTest.class, "map");

  @AfterEach
  void unbind() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void theAttributeIsTheOwnersClassNameAndTheMemoName() {
    assertThat(NAME.attribute()).isEqualTo(RequestMemoTest.class.getName() + ".name");
  }

  @Test
  void computesOncePerRequestAndKey() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    AtomicInteger calls = new AtomicInteger();

    Optional<String> first =
        RequestMemo.get(request, NAME, () -> Optional.of("v" + calls.incrementAndGet()));
    Optional<String> second =
        RequestMemo.get(request, NAME, () -> Optional.of("v" + calls.incrementAndGet()));

    assertThat(first).contains("v1");
    assertThat(second).isSameAs(first);
    assertThat(calls).hasValue(1);
  }

  @Test
  void anEmptyOptionalIsAMemoisedAnswerNotAMiss() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    AtomicInteger calls = new AtomicInteger();

    RequestMemo.get(request, NAME, () -> emptyCounting(calls));
    RequestMemo.get(request, NAME, () -> emptyCounting(calls));

    assertThat(calls).hasValue(1);
  }

  @Test
  void keysDoNotShareAValue() {
    MockHttpServletRequest request = new MockHttpServletRequest();

    RequestMemo.get(request, NAME, () -> Optional.of("x"));
    boolean flag = RequestMemo.get(request, FLAG, () -> Boolean.FALSE);

    assertThat(flag).isFalse();
    assertThat(request.getAttribute(FLAG.attribute())).isEqualTo(Boolean.FALSE);
  }

  @Test
  void aNewRequestComputesAgain() {
    AtomicInteger calls = new AtomicInteger();

    RequestMemo.get(new MockHttpServletRequest(), FLAG, () -> calls.incrementAndGet() > 0);
    RequestMemo.get(new MockHttpServletRequest(), FLAG, () -> calls.incrementAndGet() > 0);

    assertThat(calls).hasValue(2);
  }

  @Test
  void theThreadBoundFlavourMemoisesOnTheBoundRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    Map<String, Integer> first = RequestMemo.getIfBound(MAP, HashMap::new);
    first.put("a", 1);
    Map<String, Integer> second = RequestMemo.getIfBound(MAP, HashMap::new);

    assertThat(second).isSameAs(first).containsEntry("a", 1);
    assertThat(request.getAttribute(MAP.attribute())).isSameAs(first);
  }

  @Test
  void theThreadBoundFlavourAnswersNullAndComputesNothingOutsideARequest() {
    AtomicInteger calls = new AtomicInteger();

    Map<String, Integer> memo =
        RequestMemo.getIfBound(
            MAP,
            () -> {
              calls.incrementAndGet();
              return new HashMap<>();
            });

    assertThat(memo).isNull();
    assertThat(calls).hasValue(0);
  }

  private static Optional<String> emptyCounting(AtomicInteger calls) {
    calls.incrementAndGet();
    return Optional.empty();
  }
}
