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

package de.greluc.krt.profit.basetool.frontend.shell.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import de.greluc.krt.profit.basetool.frontend.shell.web.QuickAccessSearchController.QuickAccessSearchItem;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Unit tests for {@link QuickAccessSearchController}: the parallel fan-out, the limits, failing
 * closed per type, the metric and keeping the term out of the MDC and the labels (REQ-UI-033).
 */
class QuickAccessSearchControllerTest {

  private static final Duration TIMEOUT = Duration.ofMillis(300);

  private final StaticMessageSource messages = new StaticMessageSource();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  /** Pins the locale and the labels the hits resolve. */
  @BeforeEach
  void setUp() {
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    messages.addMessage("palette.kind.mission", Locale.ENGLISH, "Mission");
    messages.addMessage("palette.kind.operation", Locale.ENGLISH, "Operation");
    messages.addMessage("palette.kind.material", Locale.ENGLISH, "Material");
    messages.addMessage("mission.status.planned", Locale.ENGLISH, "PLANNED");
  }

  /** Clears the locale and the MDC the tests set. */
  @AfterEach
  void tearDown() {
    LocaleContextHolder.resetLocaleContext();
    MDC.clear();
  }

  /** Every type's hits come back in contributor order, labelled in the caller's language. */
  @Test
  void answersEveryTypeInContributorOrder() {
    Instant start = Instant.parse("2026-11-01T18:00:00Z");
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) ->
                    List.of(
                        new QuickAccessHit(
                            "/missions/1", "Mining run", "mission.status.planned", start))),
            contributor(
                QuickAccessSearchKind.MATERIAL,
                (q, limit) ->
                    List.of(new QuickAccessHit("/materials/2", "Mining laser", null, null))));

    assertThat(controller.search("min", null))
        .containsExactly(
            new QuickAccessSearchItem(
                "mission",
                "/missions/1",
                "Mining run",
                "Mission",
                "clipboard-check",
                "PLANNED",
                start),
            new QuickAccessSearchItem(
                "material", "/materials/2", "Mining laser", "Material", "swap", null, null));
  }

  /** The contributors run at the same time, not one after the other. */
  @Test
  void asksTheContributorsInParallel() {
    CountDownLatch bothStarted = new CountDownLatch(2);
    BiFunction<String, Integer, List<QuickAccessHit>> waitForTheOther =
        (q, limit) -> {
          bothStarted.countDown();
          try {
            return bothStarted.await(1, TimeUnit.SECONDS)
                ? List.of(new QuickAccessHit("/x", "x", null, null))
                : List.of();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
          }
        };
    QuickAccessSearchController controller =
        controller(
            contributor(QuickAccessSearchKind.MISSION, waitForTheOther),
            contributor(QuickAccessSearchKind.OPERATION, waitForTheOther));

    assertThat(controller.search("xx", null)).hasSize(2);
  }

  /** A term below two characters asks no contributor. */
  @Test
  void ignoresATermBelowTwoCharacters() {
    AtomicInteger calls = new AtomicInteger();
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) -> {
                  calls.incrementAndGet();
                  return List.of();
                }));

    assertThat(controller.search(" a ", null)).isEmpty();
    assertThat(controller.search(null, null)).isEmpty();
    assertThat(calls).hasValue(0);
  }

  /** A single character asks only the type that searches by number. */
  @Test
  void aSingleCharacterAsksOnlyTheNumberSearch() {
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) -> List.of(new QuickAccessHit("/missions/1", "m", null, null))),
            contributor(
                QuickAccessSearchKind.JOB_ORDER,
                (q, limit) -> List.of(new QuickAccessHit("/orders/1", "#7", null, null))));

    assertThat(controller.search("7", null))
        .extracting(QuickAccessSearchItem::kind)
        .containsExactly("job_order");
  }

  /** The contributor gets the trimmed term and a limit of five, and more than five are cut. */
  @Test
  void asksForFiveTrimmedAndCutsTheRest() {
    List<String> seen = new ArrayList<>();
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) -> {
                  seen.add(q + "|" + limit);
                  List<QuickAccessHit> many = new ArrayList<>();
                  for (int i = 0; i < 7; i++) {
                    many.add(new QuickAccessHit("/missions/" + i, "m" + i, null, null));
                  }
                  return many;
                }));

    assertThat(controller.search("  mining  ", null)).hasSize(5);
    assertThat(seen).containsExactly("mining|5");
  }

  /** A term longer than the cap asks no contributor. */
  @Test
  void ignoresAnOverlongTerm() {
    AtomicInteger calls = new AtomicInteger();
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) -> {
                  calls.incrementAndGet();
                  return List.of();
                }));

    assertThat(controller.search("x".repeat(101), null)).isEmpty();
    assertThat(calls).hasValue(0);
  }

  /** A kind narrows the search to that type; an unknown kind asks nobody. */
  @Test
  void aKindNarrowsTheSearch() {
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) -> List.of(new QuickAccessHit("/missions/1", "m", null, null))),
            contributor(
                QuickAccessSearchKind.MATERIAL,
                (q, limit) -> List.of(new QuickAccessHit("/materials/1", "m", null, null))));

    assertThat(controller.search("mm", List.of("material")))
        .extracting(QuickAccessSearchItem::kind)
        .containsExactly("material");
    assertThat(controller.search("mm", List.of("member"))).isEmpty();
  }

  /** A failing or slow type yields no hits of its own and leaves the others standing. */
  @Test
  void failsClosedPerType() {
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) -> {
                  throw new IllegalStateException("backend down for " + q);
                }),
            contributor(
                QuickAccessSearchKind.OPERATION,
                (q, limit) -> {
                  try {
                    Thread.sleep(TIMEOUT.multipliedBy(5).toMillis());
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return List.of(new QuickAccessHit("/operations/1", "late", null, null));
                }),
            contributor(
                QuickAccessSearchKind.MATERIAL,
                (q, limit) -> List.of(new QuickAccessHit("/materials/1", "steel", null, null))));

    assertThat(controller.search("st", null))
        .extracting(QuickAccessSearchItem::href)
        .containsExactly("/materials/1");
    assertThat(count("mission", "error")).isEqualTo(1.0);
    assertThat(count("operation", "timeout")).isEqualTo(1.0);
    assertThat(count("material", "hits")).isEqualTo(1.0);
  }

  /** A lost session is no type failure: it reaches the global handler. */
  @Test
  void aLostSessionPropagates() {
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) -> {
                  throw new ReauthenticationRequiredException("no token", null);
                }));

    assertThatThrownBy(() -> controller.search("mm", null))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  /** A search adds no series, so the term is in no label value. */
  @Test
  void theTermNeverBecomesALabel() {
    QuickAccessSearchController controller =
        controller(contributor(QuickAccessSearchKind.MISSION, (q, limit) -> List.of()));
    int before = registry.find(MetricNames.QUICK_ACCESS_SEARCH).meters().size();

    controller.search("secret-term", null);

    Collection<Meter> meters = registry.find(MetricNames.QUICK_ACCESS_SEARCH).meters();
    assertThat(meters).hasSize(before);
    assertThat(meters)
        .flatExtracting(m -> m.getId().getTags())
        .allSatisfy(tag -> assertThat(tag.getValue()).doesNotContain("secret"));
    assertThat(count("mission", "empty")).isEqualTo(1.0);
  }

  /** The contributor sees the caller's MDC, which never carries the term. */
  @Test
  void theTermNeverReachesTheMdc() {
    MDC.put("correlationId", "c-1");
    Map<String, String> seen = new ConcurrentHashMap<>();
    QuickAccessSearchController controller =
        controller(
            contributor(
                QuickAccessSearchKind.MISSION,
                (q, limit) -> {
                  Map<String, String> mdc = MDC.getCopyOfContextMap();
                  if (mdc != null) {
                    seen.putAll(mdc);
                  }
                  return List.of();
                }));

    controller.search("secret-term", null);

    assertThat(seen).containsEntry("correlationId", "c-1");
    assertThat(seen.values()).noneMatch(v -> v.contains("secret"));
    assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("correlationId");
  }

  /** The term is a query parameter of a fixed path. */
  @Test
  void theTermIsNoPathSegment() throws NoSuchMethodException {
    assertThat(QuickAccessSearchController.PATH).isEqualTo("/quick-access/search");
    assertThat(
            QuickAccessSearchController.class
                .getMethod("search", String.class, List.class)
                .getAnnotation(GetMapping.class)
                .value())
        .containsExactly("/quick-access/search");
  }

  private double count(@NotNull String kind, @NotNull String outcome) {
    return registry
        .get(MetricNames.QUICK_ACCESS_SEARCH)
        .tag(MetricNames.TAG_KIND, kind)
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .counter()
        .count();
  }

  private @NotNull QuickAccessSearchController controller(
      @NotNull QuickAccessSearchContributor... contributors) {
    return new QuickAccessSearchController(
        List.of(contributors),
        new ParallelPageLoader(),
        messages,
        new QuickAccessSearchMetrics(registry),
        TIMEOUT);
  }

  private static @NotNull QuickAccessSearchContributor contributor(
      @NotNull QuickAccessSearchKind kind,
      @NotNull BiFunction<String, Integer, List<QuickAccessHit>> results) {
    return new QuickAccessSearchContributor() {
      @Override
      public @NotNull QuickAccessSearchKind kind() {
        return kind;
      }

      @Override
      public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
        return results.apply(query, limit);
      }
    };
  }
}
