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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.shell.web.QuickAccessSearchMetrics.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the quick access's entity search by asking every {@link QuickAccessSearchContributor} in
 * parallel, each failing closed on its own (REQ-UI-033, ADR-0297).
 *
 * <p>The term stays a query parameter: it is never logged, put into the MDC or used as a label.
 */
@Slf4j
@RestController
@PreAuthorize("isAuthenticated()")
public class QuickAccessSearchController {

  /** The endpoint path; mirrored by {@code data-entity-search-url} in {@code palette.html}. */
  public static final String PATH = "/quick-access/search";

  /** The shortest term any type is searched with. */
  static final int MIN_TERM_LENGTH = 1;

  /** The longest term that is searched; a longer one answers nothing. */
  static final int MAX_TERM_LENGTH = 100;

  /** The most hits per type. */
  static final int HITS_PER_KIND = 5;

  /** How long one type may take before it counts as failed. */
  private static final Duration DEFAULT_CONTRIBUTOR_TIMEOUT = Duration.ofSeconds(2);

  private final List<QuickAccessSearchContributor> contributors;
  private final ParallelPageLoader loader;
  private final MessageSource messageSource;
  private final QuickAccessSearchMetrics metrics;
  private final Duration contributorTimeout;

  /**
   * One hit of the answer, already in the caller's language.
   *
   * @param kind the entity type's key
   * @param href the page the hit opens
   * @param label the entity's name
   * @param crumb the type's name, shown as the breadcrumb
   * @param icon the sprite symbol without its {@code krt-icon-} prefix
   * @param status the entity's status, or {@code null}
   * @param date the entity's date, or {@code null}
   * @param adminHref the page the hit opens in administration mode, or {@code null}
   */
  public record QuickAccessSearchItem(
      @NotNull String kind,
      @NotNull String href,
      @NotNull String label,
      @NotNull String crumb,
      @NotNull String icon,
      @Nullable String status,
      @Nullable Instant date,
      @Nullable String adminHref) {}

  /**
   * Creates the controller with the default per-type timeout.
   *
   * @param contributors one per entity type, in answer order
   * @param loader runs the contributors in parallel with the request context
   * @param messageSource resolves the type names and statuses
   * @param metrics counts each type's outcome
   */
  @Autowired
  public QuickAccessSearchController(
      @NotNull List<QuickAccessSearchContributor> contributors,
      @NotNull ParallelPageLoader loader,
      @NotNull MessageSource messageSource,
      @NotNull QuickAccessSearchMetrics metrics) {
    this(contributors, loader, messageSource, metrics, DEFAULT_CONTRIBUTOR_TIMEOUT);
  }

  /**
   * Creates the controller with a given per-type timeout.
   *
   * @param contributors one per entity type, in answer order
   * @param loader runs the contributors in parallel with the request context
   * @param messageSource resolves the type names and statuses
   * @param metrics counts each type's outcome
   * @param contributorTimeout how long one type may take
   */
  QuickAccessSearchController(
      @NotNull List<QuickAccessSearchContributor> contributors,
      @NotNull ParallelPageLoader loader,
      @NotNull MessageSource messageSource,
      @NotNull QuickAccessSearchMetrics metrics,
      @NotNull Duration contributorTimeout) {
    this.contributors = List.copyOf(contributors);
    this.loader = loader;
    this.messageSource = messageSource;
    this.metrics = metrics;
    this.contributorTimeout = contributorTimeout;
  }

  /**
   * Searches the entity types the caller may open.
   *
   * @param q the search term; a type is asked only when the trimmed term has at least its minimum
   *     length, and one longer than 100 characters answers nothing
   * @param kind the type keys to narrow to, or {@code null} for every type
   * @return at most five hits per type, in contributor order
   * @throws ReauthenticationRequiredException when the session can no longer reach the backend
   */
  @NotNull
  @GetMapping(value = PATH, headers = "X-Requested-With=XMLHttpRequest")
  public List<QuickAccessSearchItem> search(
      @RequestParam(name = "q", required = false) @Nullable String q,
      @RequestParam(name = "kind", required = false) @Nullable List<String> kind) {
    String term = q == null ? "" : q.trim();
    if (term.length() < MIN_TERM_LENGTH || term.length() > MAX_TERM_LENGTH) {
      return List.of();
    }
    Set<QuickAccessSearchKind> wanted = wantedKinds(kind);
    List<QuickAccessSearchContributor> asked =
        contributors.stream()
            .filter(c -> wanted.contains(c.kind()))
            .filter(c -> term.length() >= c.kind().minTermLength())
            .toList();
    List<CompletableFuture<List<QuickAccessHit>>> futures =
        asked.stream()
            .map(c ->
                loader
                    .loadAsync(() -> c.results(term, HITS_PER_KIND))
                    .orTimeout(contributorTimeout.toMillis(), TimeUnit.MILLISECONDS))
            .toList();
    Locale locale = LocaleContextHolder.getLocale();
    return IntStream.range(0, asked.size())
        .boxed()
        .flatMap(i -> collect(asked.get(i).kind(), futures.get(i), locale).stream())
        .toList();
  }

  private static @NotNull Set<QuickAccessSearchKind> wantedKinds(@Nullable List<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return EnumSet.allOf(QuickAccessSearchKind.class);
    }
    Set<QuickAccessSearchKind> out = EnumSet.noneOf(QuickAccessSearchKind.class);
    keys.forEach(k -> QuickAccessSearchKind.fromKey(k).ifPresent(out::add));
    return out;
  }

  private @NotNull List<QuickAccessSearchItem> collect(
      @NotNull QuickAccessSearchKind kind,
      @NotNull CompletableFuture<List<QuickAccessHit>> future,
      @NotNull Locale locale) {
    List<QuickAccessHit> hits;
    try {
      hits = future.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      rethrowLostSession(cause);
      Outcome outcome = cause instanceof TimeoutException ? Outcome.TIMEOUT : Outcome.ERROR;
      metrics.record(kind, outcome);
      Level level =
          outcome == Outcome.TIMEOUT || cause instanceof BackendServiceException
              ? Level.DEBUG
              : Level.WARN;
      log.atLevel(level)
          .log(
              "Quick-access search of {} answered nothing: {} {}",
              kind.key(),
              outcome.label(),
              cause.getClass().getSimpleName());
      return List.of();
    }
    List<QuickAccessHit> limited = hits.stream().limit(HITS_PER_KIND).toList();
    metrics.record(kind, limited.isEmpty() ? Outcome.EMPTY : Outcome.HITS);
    String crumb = messageSource.getMessage(kind.labelKey(), null, kind.labelKey(), locale);
    return limited.stream()
        .map(h ->
            new QuickAccessSearchItem(
                kind.key(),
                h.href(),
                h.name(),
                crumb,
                kind.icon(),
                h.statusKey() == null
                    ? null
                    : messageSource.getMessage(h.statusKey(), null, h.statusKey(), locale),
                h.date(),
                h.adminHref()))
        .toList();
  }

  private static void rethrowLostSession(@NotNull Throwable cause) {
    if (cause instanceof ReauthenticationRequiredException reauth) {
      throw reauth;
    }
    if (ReauthenticationRequiredException.isReauthSignal(cause)) {
      throw new ReauthenticationRequiredException(
          "Quick-access search lost the OAuth2 session", cause);
    }
  }
}
