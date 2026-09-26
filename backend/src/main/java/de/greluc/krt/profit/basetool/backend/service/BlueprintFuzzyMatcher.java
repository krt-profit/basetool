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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportSuggestionDto;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Dependency-free fuzzy matcher that suggests master products for an unmatched external blueprint
 * name.
 *
 * <p>A candidate's score is the larger of the Levenshtein ratio and the token-set Jaccard
 * similarity over normalized keys (see {@link BlueprintNameNormalizer}).
 */
@Component
public class BlueprintFuzzyMatcher {

  /** Default minimum score for a candidate to be offered as a suggestion. */
  public static final double DEFAULT_THRESHOLD = 0.5;

  /** Default maximum number of suggestions returned per external name. */
  public static final int DEFAULT_LIMIT = 5;

  /**
   * Returns the best {@code limit} products scoring at least {@code threshold}, highest first, ties
   * broken by product name; a domain wrapper over {@link #topMatches}.
   *
   * @param normalizedQuery the normalized external name
   * @param candidates the master products to score
   * @param limit maximum number of suggestions ({@code <= 0} yields an empty list)
   * @param threshold minimum score in {@code [0.0, 1.0]}
   * @return the top suggestions, highest score first
   */
  @NotNull
  public List<BlueprintImportSuggestionDto> topSuggestions(
      @NotNull String normalizedQuery,
      @NotNull List<ResolvedProduct> candidates,
      int limit,
      double threshold) {
    return topMatches(
            normalizedQuery,
            candidates,
            ResolvedProduct::productKey,
            Comparator.comparing(
                ResolvedProduct::productName, Comparator.nullsLast(String::compareToIgnoreCase)),
            limit,
            threshold)
        .stream()
        .map(
            match ->
                new BlueprintImportSuggestionDto(
                    match.candidate().productKey(), match.candidate().productName(), match.score()))
        .toList();
  }

  /**
   * Returns the best {@code limit} candidates scoring at least {@code threshold}, highest rounded
   * score first, ties broken by {@code tieBreak}.
   *
   * @param <T> the candidate type
   * @param normalizedQuery the normalized external name
   * @param candidates the candidates to score
   * @param keyExtractor extracts each candidate's normalized key; {@code null} or empty keys are
   *     skipped
   * @param tieBreak secondary order for equal scores
   * @param limit maximum number of matches ({@code <= 0} yields an empty list)
   * @param threshold minimum raw score in {@code [0.0, 1.0]}
   * @return the top matches, highest score first
   */
  @NotNull
  public <T> List<Scored<T>> topMatches(
      @NotNull String normalizedQuery,
      @NotNull Collection<T> candidates,
      @NotNull Function<? super T, String> keyExtractor,
      @NotNull Comparator<? super T> tieBreak,
      int limit,
      double threshold) {
    if (limit <= 0 || normalizedQuery.isEmpty()) {
      return List.of();
    }
    Set<String> queryTokens = tokenize(normalizedQuery);
    List<Scored<T>> scored = new ArrayList<>();
    for (T candidate : candidates) {
      String key = keyExtractor.apply(candidate);
      if (key == null || key.isEmpty()) {
        continue;
      }
      double value = score(normalizedQuery, queryTokens, key);
      if (value >= threshold) {
        scored.add(new Scored<>(candidate, round(value)));
      }
    }
    scored.sort(
        Comparator.comparingDouble((Scored<T> match) -> match.score())
            .reversed()
            .thenComparing(Scored::candidate, tieBreak));
    return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
  }

  /**
   * A ranked candidate: the candidate itself and its rounded similarity score in {@code [0.0,
   * 1.0]}.
   *
   * @param <T> the candidate type
   * @param candidate the scored candidate
   * @param score the rounded similarity score
   */
  public record Scored<T>(@NotNull T candidate, double score) {}

  /**
   * Computes the larger of the Levenshtein ratio and the token-set Jaccard similarity.
   *
   * @param query the normalized query key
   * @param queryTokens the pre-split token set of the query
   * @param candidateKey the normalized candidate key
   * @return the similarity score in {@code [0.0, 1.0]}
   */
  private double score(
      @NotNull String query, @NotNull Set<String> queryTokens, @NotNull String candidateKey) {
    if (candidateKey.isEmpty()) {
      return 0.0;
    }
    double lev = levenshteinRatio(query, candidateKey);
    double jaccard = jaccard(queryTokens, tokenize(candidateKey));
    return Math.max(lev, jaccard);
  }

  /**
   * Levenshtein similarity ratio: {@code 1 - editDistance / max(len(a), len(b))}, clamped to {@code
   * [0.0, 1.0]}. Two empty strings score 1.0; an empty against a non-empty scores 0.0.
   *
   * @param a first string
   * @param b second string
   * @return the similarity ratio
   */
  private double levenshteinRatio(@NotNull String a, @NotNull String b) {
    int maxLen = Math.max(a.length(), b.length());
    if (maxLen == 0) {
      return 1.0;
    }
    return 1.0 - (double) levenshteinDistance(a, b) / maxLen;
  }

  /**
   * Computes the Levenshtein edit distance with unit costs, keeping only two rows.
   *
   * @param a first string
   * @param b second string
   * @return the minimum number of single-character edits turning {@code a} into {@code b}
   */
  private int levenshteinDistance(@NotNull String a, @NotNull String b) {
    int n = a.length();
    int m = b.length();
    if (n == 0) {
      return m;
    }
    if (m == 0) {
      return n;
    }
    int[] previous = new int[m + 1];
    int[] current = new int[m + 1];
    for (int j = 0; j <= m; j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= n; i++) {
      current[0] = i;
      char ca = a.charAt(i - 1);
      for (int j = 1; j <= m; j++) {
        int cost = ca == b.charAt(j - 1) ? 0 : 1;
        current[j] =
            Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[m];
  }

  /**
   * Computes the Jaccard similarity {@code |intersection| / |union|} of two token sets; an empty
   * set scores 0.0.
   *
   * @param a first token set
   * @param b second token set
   * @return the Jaccard similarity in {@code [0.0, 1.0]}
   */
  private double jaccard(@NotNull Set<String> a, @NotNull Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    int intersection = 0;
    for (String token : a) {
      if (b.contains(token)) {
        intersection++;
      }
    }
    int union = a.size() + b.size() - intersection;
    return union == 0 ? 0.0 : (double) intersection / union;
  }

  /**
   * Splits a normalized key into its space-separated word tokens (empty tokens dropped).
   *
   * @param normalized a normalized product key
   * @return the token set (never {@code null})
   */
  @NotNull
  private Set<String> tokenize(@NotNull String normalized) {
    Set<String> tokens = new HashSet<>();
    for (String token : normalized.split(" ")) {
      if (!token.isEmpty()) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  /**
   * Rounds a score to three decimal places so the wire payload stays compact and stable.
   *
   * @param score the raw score
   * @return the score rounded to three decimals
   */
  private double round(double score) {
    return Math.round(score * 1000.0) / 1000.0;
  }
}
