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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.PersonSearchHitDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonSearchResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Mockito tests for {@link AdminPersonSearchPageController} (REQ-SEC-060).
 *
 * <p>The behaviours pinned here are the ones a later refactor could quietly break without any page
 * looking wrong: the too-short term never reaches the backend, the term is bound as a URI-template
 * variable so it is percent-encoded exactly once, the casing the admin typed is relayed untouched
 * (the backend matches case-insensitively — normalising it here would make that guarantee depend on
 * two places instead of one), and a backend failure leaves {@code searched} false so the page says
 * "failed" rather than "no mentions found".
 *
 * <p>The encoding test is a regression. The term used to be run through {@code URLEncoder} and then
 * concatenated into the URI, which WebClient's default {@code TEMPLATE_AND_VALUES} mode encoded a
 * second time: the backend received the literal escape sequence, its wildcard escaping turned those
 * {@code %} into {@code \\%}, and {@code ILIKE} matched nothing. Every term with an umlaut returned
 * no hits — and on this surface no hits reads as "this person appears nowhere", which is the answer
 * an admin acts on when serving an Art. 16 rectification.
 */
class AdminPersonSearchPageControllerTest {

  private static final PersonSearchResultDto ONE_HIT =
      new PersonSearchResultDto(
          List.of(new PersonSearchHitDto("MEMBER", "app_user", "username", "id", "Snippet", null)),
          false,
          List.of());

  private static final String SEARCH_URI = "/api/v1/admin/person-search?q={q}";

  @Test
  void aTermUnderThreeCharactersNeverReachesTheBackend() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);
    Model model = new ConcurrentModel();

    String view = controller.page("Zz", null, model);

    assertEquals("admin/person-search", view);
    assertEquals("admin.personSearch.tooShort", model.getAttribute("error"));
    assertEquals(false, model.getAttribute("searched"));
    verifyNoSearch(client);
  }

  @Test
  void anEmptyTermIsTheInitialStateAndNotAnError() {
    // The page is reachable from the admin menu with no term at all; that is not a mistake the
    // admin made, so it must not render an error.
    BackendApiClient client = mock(BackendApiClient.class);
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);
    Model model = new ConcurrentModel();

    controller.page(null, null, model);

    assertFalse(model.containsAttribute("error"));
    assertEquals(false, model.getAttribute("searched"));
    assertEquals(List.of(), model.getAttribute("hits"));
    verifyNoSearch(client);
  }

  @Test
  void whitespaceIsTrimmedBeforeTheLengthIsJudged() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);
    Model model = new ConcurrentModel();

    controller.page("  Zz  ", null, model);

    assertEquals("admin.personSearch.tooShort", model.getAttribute("error"));
    assertEquals("Zz", model.getAttribute("term"), "the trimmed term is echoed back into the box");
  }

  @Test
  void theTermIsBoundAsAUriVariableAndNeverEncodedByHand() {
    // Encoded exactly once, by the WebClient, because it is a template variable. See the class
    // note: hand-encoding it was double-encoding it on the wire and every umlaut found nothing.
    BackendApiClient client = mock(BackendApiClient.class);
    stubOneHit(client);
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);

    controller.page("Müller & Söhne", null, new ConcurrentModel());

    verify(client).get(eq(SEARCH_URI), resultType(), eq("Müller & Söhne"));
  }

  @Test
  void theTypedCasingIsRelayedUntouched() {
    // The case-insensitive match is the backend's ILIKE. Normalising the term here would look
    // harmless and would move the guarantee into a second place, where the next reader has to check
    // both to know whether it still holds.
    BackendApiClient client = mock(BackendApiClient.class);
    stubOneHit(client);
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);

    controller.page("MixedCase", null, new ConcurrentModel());

    verify(client).get(eq(SEARCH_URI), resultType(), eq("MixedCase"));
  }

  @Test
  void aSuccessfulSearchPublishesTheHitsAndMarksThePageSearched() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), resultType(), any(Object[].class)))
        .thenReturn(
            new PersonSearchResultDto(
                List.of(
                    new PersonSearchHitDto(
                        "MEMBER", "app_user", "username", "id", "Snippet", "MEMBER")),
                true,
                List.of("mission_participant.comment")));
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);
    Model model = new ConcurrentModel();

    controller.page("SomeHandle", null, model);

    assertEquals(true, model.getAttribute("searched"));
    assertEquals(true, model.getAttribute("truncated"));
    // The per-column cap is carried separately, because it is reached far more often than the
    // overall one and "narrow the term" is not the remedy for it.
    assertEquals(List.of("mission_participant.comment"), model.getAttribute("cappedColumns"));
    assertEquals(1, ((List<?>) model.getAttribute("hits")).size());
    assertFalse(model.containsAttribute("error"));
  }

  @Test
  void anEmptyBodyFromTheBackendIsNoHitsRatherThanAFailure() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), resultType(), any(Object[].class))).thenReturn(null);
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);
    Model model = new ConcurrentModel();

    controller.page("SomeHandle", null, model);

    assertEquals(List.of(), model.getAttribute("hits"));
    assertEquals(false, model.getAttribute("truncated"));
    assertEquals(List.of(), model.getAttribute("cappedColumns"));
    assertEquals(true, model.getAttribute("searched"));
  }

  @Test
  void aBackendFailureLeavesSearchedFalseSoThePageCannotClaimNoMentions() {
    // "No mentions found" and "the search did not run" look identical on a page that only counts
    // hits, and they are opposite answers to a rectification request.
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), resultType(), any(Object[].class)))
        .thenThrow(new BackendServiceException("boom", new RuntimeException(), 503));
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);
    Model model = new ConcurrentModel();

    controller.page("SomeHandle", null, model);

    assertEquals("error.loadFailed", model.getAttribute("error"));
    assertEquals(false, model.getAttribute("searched"));
  }

  @Test
  void anUnexpectedFailureIsHandledTheSameWay() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), resultType(), any(Object[].class)))
        .thenThrow(new IllegalStateException("boom"));
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);
    Model model = new ConcurrentModel();

    controller.page("SomeHandle", null, model);

    assertEquals("error.loadFailed", model.getAttribute("error"));
    assertEquals(false, model.getAttribute("searched"));
  }

  @Test
  void theFragmentParameterSelectsTheResultsBlockForTheInPlaceSwap() {
    BackendApiClient client = mock(BackendApiClient.class);
    stubOneHit(client);
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);

    String view = controller.page("SomeHandle", "results", new ConcurrentModel());

    assertEquals("admin/person-search :: results", view);
  }

  @Test
  void anUnknownFragmentValueRendersTheWholePage() {
    BackendApiClient client = mock(BackendApiClient.class);
    stubOneHit(client);
    AdminPersonSearchPageController controller = new AdminPersonSearchPageController(client);

    String view = controller.page("SomeHandle", "nonsense", new ConcurrentModel());

    assertEquals("admin/person-search", view);
  }

  /**
   * The {@code ParameterizedTypeReference} matcher, spelled once.
   *
   * @return an {@code any()} matcher of the controller's result type
   */
  private static ParameterizedTypeReference<PersonSearchResultDto> resultType() {
    return ArgumentMatchers.any();
  }

  /**
   * Stubs the one overload the controller calls.
   *
   * @param client the mocked client
   */
  private static void stubOneHit(BackendApiClient client) {
    when(client.get(any(String.class), resultType(), any(Object[].class))).thenReturn(ONE_HIT);
  }

  /**
   * Asserts the controller made no backend call, on either overload.
   *
   * @param client the mocked client
   */
  private static void verifyNoSearch(BackendApiClient client) {
    verify(client, never())
        .get(
            ArgumentMatchers.<String>any(),
            ArgumentMatchers.<ParameterizedTypeReference<Object>>any(),
            any(Object[].class));
    verify(client, never()).get(ArgumentMatchers.<String>any(), ArgumentMatchers.<Class<?>>any());
  }
}
