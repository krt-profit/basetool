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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileBlueprintSharingForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileDescriptionForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfilePayoutPreferenceForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pure-Mockito unit tests for {@link ProfileController}. The full Thymeleaf-rendering MVC
 * integration is covered separately in {@code ProfileControllerMvcTest}; here we focus on:
 *
 * <ul>
 *   <li>The unauthenticated branch (no principal → redirect home).
 *   <li>OIDC-claim-vs-backend precedence: backend overwrites token claims.
 *   <li>Join-date parsing and the months-in-squadron computation.
 *   <li>POST /profile/description happy / validation-error / optimistic- lock / generic-error
 *       branches — these decide which toast the user sees and whether the form re-renders or
 *       redirects.
 *   <li>Multi-valued OIDC claim handling (Keycloak returns lists for some custom claims).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private OidcUser principal;
  @Mock private RedirectAttributes redirectAttributes;
  @Mock private BindingResult bindingResult;
  @Mock private MessageSource messageSource;

  @InjectMocks private ProfileController controller;

  @BeforeEach
  void wireIssuerUri() {
    ReflectionTestUtils.setField(controller, "issuerUri", "https://kc.example.com/realms/iri");
  }

  // ── GET /profile — auth / backend-precedence / claim handling ────────────

  @Test
  void profile_unauthenticated_redirectsHome() {
    // The page is public but only meaningful for logged-in users; the
    // controller short-circuits before hitting the backend so a logged-out
    // request doesn't burn a HTTP call.
    String view = controller.profile(new ConcurrentModel(), /*principal*/ null);

    assertEquals("redirect:/", view);
    verifyNoInteractions(backendApiClient);
  }

  @Test
  void profile_authenticated_populatesModelFromTokenAndBackendOverride() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getEmail()).thenReturn("jdoe@example.com");
    when(principal.getAttribute("rank")).thenReturn(3);
    when(principal.getAttribute("description")).thenReturn("From-Token");
    when(principal.getAttribute("displayName")).thenReturn("JD");

    Map<String, Object> backendUser =
        Map.of(
            "rank", 7,
            "description", "From-Backend",
            "displayName", "Backend-DN",
            "version", 4L,
            "joinDate", "2024-01-15");
    when(backendApiClient.<Map<String, Object>>get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(backendUser);
    when(backendApiClient.<Map<String, Object>>get(
            eq("/api/v1/users/me/payout-preference"), anyTypeRef()))
        .thenReturn(Map.of("defaultPayoutPreference", "DONATE", "version", 4L));
    when(backendApiClient.<Map<String, Object>>get(
            eq("/api/v1/users/me/blueprint-sharing"), anyTypeRef()))
        .thenReturn(Map.of("shareBlueprintsGlobally", true, "version", 4L));

    Model model = new ConcurrentModel();
    String view = controller.profile(model, principal);

    assertEquals("profile", view);
    assertEquals("jdoe", model.getAttribute("username"));
    assertEquals("jdoe@example.com", model.getAttribute("email"));
    // Backend values must shadow the token values
    assertEquals(7, model.getAttribute("rank"));
    assertEquals("From-Backend", model.getAttribute("description"));
    assertEquals("Backend-DN", model.getAttribute("displayName"));
    assertEquals(4L, model.getAttribute("version"));
    assertEquals(java.time.LocalDate.of(2024, 1, 15), model.getAttribute("joinDate"));
    // Months between 2024-01-15 and today (>= 16 since today >= 2026-05-13)
    Long months = (Long) model.getAttribute("monthsInSquadron");
    assertNotNull(months);
    assertTrue(months >= 12, "expected at least one year, was " + months);
    // The Keycloak account URL is built from the issuer
    assertEquals(
        "https://kc.example.com/realms/iri/account", model.getAttribute("keycloakAccountUrl"));
    // ProfileDescriptionForm is seeded from current model state
    ProfileDescriptionForm form =
        (ProfileDescriptionForm) model.getAttribute("profileDescriptionForm");
    assertNotNull(form);
    assertEquals("From-Backend", form.description());
    assertEquals("Backend-DN", form.displayName());
    assertEquals(4L, form.version());
    // Default payout preference comes from its own endpoint and seeds the selector form;
    // the form echoes the same user-row version as the description form.
    assertEquals(PayoutPreference.DONATE, model.getAttribute("defaultPayoutPreference"));
    ProfilePayoutPreferenceForm payoutForm =
        (ProfilePayoutPreferenceForm) model.getAttribute("profilePayoutPreferenceForm");
    assertNotNull(payoutForm);
    assertEquals(PayoutPreference.DONATE, payoutForm.defaultPayoutPreference());
    assertEquals(4L, payoutForm.version());
    // Global blueprint-sharing flag comes from its own endpoint and seeds the toggle form, echoing
    // the same user-row version (REQ-INV-018).
    assertEquals(true, model.getAttribute("shareBlueprintsGlobally"));
    ProfileBlueprintSharingForm sharingForm =
        (ProfileBlueprintSharingForm) model.getAttribute("profileBlueprintSharingForm");
    assertNotNull(sharingForm);
    assertTrue(sharingForm.shareBlueprintsGlobally());
    assertEquals(4L, sharingForm.version());
  }

  @Test
  void profile_authenticated_backendUnavailable_keepsTokenData() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(3);
    when(principal.getAttribute("description")).thenReturn("From-Token");
    when(principal.getAttribute("displayName")).thenReturn("JD");
    when(backendApiClient.<Map<String, Object>>get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));
    when(backendApiClient.<Map<String, Object>>get(
            eq("/api/v1/users/me/payout-preference"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));
    when(backendApiClient.<Map<String, Object>>get(
            eq("/api/v1/users/me/blueprint-sharing"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    Model model = new ConcurrentModel();
    String view = controller.profile(model, principal);

    // The controller swallows backend failures so the profile page still
    // renders. The token claims are the fallback.
    assertEquals("profile", view);
    assertEquals(3, model.getAttribute("rank"));
    assertEquals("From-Token", model.getAttribute("description"));
    assertEquals("JD", model.getAttribute("displayName"));
    // Payout preference falls back to PAYOUT when its endpoint is unreachable.
    assertEquals(PayoutPreference.PAYOUT, model.getAttribute("defaultPayoutPreference"));
    // The blueprint-sharing toggle falls back to off when its endpoint is unreachable.
    assertEquals(false, model.getAttribute("shareBlueprintsGlobally"));
  }

  @Test
  void profile_multiValueClaim_returnsFirstElement() {
    // Keycloak returns custom claims as a List when they're declared as
    // multi-valued in the mapper. The controller's getSingleClaim() helper
    // must reach in and pull the first element so the template doesn't
    // render "[CMDR]" with the bracket.
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(List.of(5, 6));
    when(principal.getAttribute("description")).thenReturn(List.of("first", "second"));
    when(principal.getAttribute("displayName")).thenReturn(java.util.List.of("DN"));
    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(null);

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(5, model.getAttribute("rank"));
    assertEquals("first", model.getAttribute("description"));
    assertEquals("DN", model.getAttribute("displayName"));
  }

  @Test
  void profile_emptyListClaim_returnsList() {
    // Edge case: an empty list claim must NOT be unwrapped — keep the
    // list so the template can render an empty state without NPE.
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(List.of());
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);
    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(null);

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(List.of(), model.getAttribute("rank"));
  }

  @Test
  void profile_authenticated_backendUserNull_doesNotOverwriteToken() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn("Desc");
    when(principal.getAttribute("displayName")).thenReturn("DN");
    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(null);

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(2, model.getAttribute("rank"));
    assertEquals("Desc", model.getAttribute("description"));
    assertNull(model.getAttribute("version"), "no version was set when backend returned null");
  }

  @Test
  void profile_authenticated_backendUserMissingDescriptionKey_keepsToken() {
    // Subtle contract: the controller only overwrites `description` when
    // the backend response contains the key (even if its value is null —
    // that's how "the user cleared the field" is encoded). Missing key
    // means "don't touch".
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn("Token-Desc");
    when(principal.getAttribute("displayName")).thenReturn("Token-DN");

    // Backend response missing the description key entirely
    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(Map.of("rank", 4));

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals("Token-Desc", model.getAttribute("description"));
    assertEquals("Token-DN", model.getAttribute("displayName"));
    // rank IS overridden because backend's value is non-null
    assertEquals(4, model.getAttribute("rank"));
  }

  @Test
  void profile_invalidJoinDateFormat_isSilentlyIgnored() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);

    Map<String, Object> backendUser = new java.util.HashMap<>();
    backendUser.put("rank", 4);
    backendUser.put("joinDate", "not-a-valid-date");
    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(backendUser);

    Model model = new ConcurrentModel();
    // The malformed date must not blow up rendering — it's silently dropped
    assertEquals("profile", controller.profile(model, principal));
    assertNull(model.getAttribute("joinDate"));
    assertNull(model.getAttribute("monthsInSquadron"));
  }

  @Test
  void profile_versionFromBackendAsNumber_isUnboxedToLong() {
    // Jackson typically deserialises JSON numbers to Integer for small
    // values — the parseLong helper must promote them to Long.
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);

    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(Map.of("rank", 1, "version", 7));

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(7L, model.getAttribute("version"));
  }

  @Test
  void profile_versionFromBackendAsString_isParsedToLong() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);

    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(Map.of("rank", 1, "version", "42"));

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(42L, model.getAttribute("version"));
  }

  @Test
  void profile_versionFromBackendUnparseable_fallsBackToZero() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);

    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(Map.of("rank", 1, "version", "not-a-number"));

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(0L, model.getAttribute("version"));
  }

  // ── POST /profile/description ───────────────────────────────────────────

  @Test
  void updateDescription_happyPath_putsAndRedirectsWithSuccessToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    ProfileDescriptionForm form = new ProfileDescriptionForm("New desc", "New DN", 2L);

    String view =
        controller.updateDescription(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);

    ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .put(eq("/api/v1/users/me/description"), bodyCap.capture(), eq(Void.class));
    Map<String, Object> body = bodyCap.getValue();
    assertEquals("New desc", body.get("description"));
    assertEquals("New DN", body.get("displayName"));
    assertEquals(2L, body.get("version"));
    verify(redirectAttributes).addFlashAttribute("successToast", "notification.success.save");
  }

  @Test
  void updateDescription_nullFields_sendEmptyStrings() {
    // A non-obvious contract: when the user clears the field, the form
    // posts a null. The controller MUST send "" (not "null") so the
    // backend writes an empty string, not a literal "null" four-char value.
    when(bindingResult.hasErrors()).thenReturn(false);
    ProfileDescriptionForm form = new ProfileDescriptionForm(null, null, 1L);

    controller.updateDescription(
        form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .put(eq("/api/v1/users/me/description"), bodyCap.capture(), eq(Void.class));
    assertEquals("", bodyCap.getValue().get("description"));
    assertEquals("", bodyCap.getValue().get("displayName"));
  }

  @Test
  void updateDescription_validationError_rendersProfileViewWithoutBackendCall() {
    when(bindingResult.hasErrors()).thenReturn(true);
    // Stub the bits the profile() method needs to render
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(1);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);
    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(null);

    ProfileDescriptionForm form = new ProfileDescriptionForm("", "", 1L);

    String view =
        controller.updateDescription(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    // No redirect, no flash attribute — the binding result stays in scope
    // so the form re-renders inline.
    assertEquals("profile", view);
    verify(backendApiClient, never()).put(any(), any(), any());
    verifyNoInteractions(redirectAttributes);
  }

  @Test
  void updateDescription_optimisticLockConflict_setsConcurrencyToast() {
    when(bindingResult.hasErrors()).thenReturn(false);

    // `BackendServiceException.getProblemType()` reads from a wrapped
    // WebClientResponseException via getResponseBodyAs(ProblemDetail.class),
    // which needs a configured decoder. Instead of replicating that whole
    // setup we spy on the exception and stub the discriminator directly —
    // that's the actual contract the controller relies on.
    BackendServiceException conflict =
        org.mockito.Mockito.spy(
            new BackendServiceException(
                "concurrency-conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), null));
    org.mockito.Mockito.doReturn("concurrency-conflict").when(conflict).getProblemType();

    doThrow(conflict)
        .when(backendApiClient)
        .put(eq("/api/v1/users/me/description"), any(), eq(Void.class));

    ProfileDescriptionForm form = new ProfileDescriptionForm("d", "n", 1L);
    String view =
        controller.updateDescription(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);
    verify(redirectAttributes).addFlashAttribute("errorToast", "error.concurrency.conflict");
  }

  @Test
  void updateDescription_genericBackendException_setsGenericErrorToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    BackendServiceException other =
        new BackendServiceException("boom", null, 500, "UNKNOWN", null, List.of(), null);
    doThrow(other).when(backendApiClient).put(any(), any(), any());

    ProfileDescriptionForm form = new ProfileDescriptionForm("d", "n", 1L);
    String view =
        controller.updateDescription(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);
    verify(redirectAttributes).addFlashAttribute("errorToast", "error.profile.update.failed");
  }

  @Test
  void updateDescription_unexpectedException_setsGenericErrorToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    doThrow(new RuntimeException("network")).when(backendApiClient).put(any(), any(), any());

    ProfileDescriptionForm form = new ProfileDescriptionForm("d", "n", 1L);
    String view =
        controller.updateDescription(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);
    verify(redirectAttributes).addFlashAttribute("errorToast", "error.profile.update.failed");
  }

  // ── POST /profile/description (AJAX / krtFetch — epic #571) ───────────────

  @Test
  void updateDescriptionAjax_happyPath_returns200WithRefreshedVersion() {
    when(bindingResult.hasErrors()).thenReturn(false);
    // refreshedUserVersion() re-fetches /me for the bumped row version after the write.
    when(backendApiClient.<Map<String, Object>>get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(Map.of("version", 5L));

    ProfileDescriptionForm form = new ProfileDescriptionForm("New desc", "New DN", 4L);
    ResponseEntity<Map<String, Object>> response =
        controller.updateDescriptionAjax(form, bindingResult, principal);

    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(5L, body.get("version"));
    assertEquals("New desc", body.get("description"));
    assertEquals("New DN", body.get("displayName"));
    verify(backendApiClient).put(eq("/api/v1/users/me/description"), any(), eq(Void.class));
  }

  @Test
  void updateDescriptionAjax_optimisticLockConflict_returns409WithOptimisticLockCode() {
    when(bindingResult.hasErrors()).thenReturn(false);
    // Same spy-on-the-exception trick as the redirect path: stub the discriminator the controller
    // actually reads instead of replicating the WebClient ProblemDetail decode.
    BackendServiceException conflict =
        org.mockito.Mockito.spy(
            new BackendServiceException(
                "concurrency-conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), null));
    org.mockito.Mockito.doReturn("concurrency-conflict").when(conflict).getProblemType();
    doThrow(conflict)
        .when(backendApiClient)
        .put(eq("/api/v1/users/me/description"), any(), eq(Void.class));
    when(messageSource.getMessage(any(), any(), any(), any())).thenReturn("conflict-message");

    ProfileDescriptionForm form = new ProfileDescriptionForm("d", "n", 1L);
    ResponseEntity<Map<String, Object>> response =
        controller.updateDescriptionAjax(form, bindingResult, principal);

    assertEquals(409, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("OPTIMISTIC_LOCK", response.getBody().get("code"));
    assertEquals("conflict-message", response.getBody().get("detail"));
  }

  @Test
  void updateDescriptionAjax_validationError_returns400WithoutBackendCall() {
    when(bindingResult.hasErrors()).thenReturn(true);
    when(bindingResult.getFieldErrors()).thenReturn(List.of());
    when(messageSource.getMessage(any(), any(), any(), any())).thenReturn("validation-failed");

    ProfileDescriptionForm form = new ProfileDescriptionForm("x", "y", 1L);
    ResponseEntity<Map<String, Object>> response =
        controller.updateDescriptionAjax(form, bindingResult, principal);

    assertEquals(400, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("validation-failed", response.getBody().get("detail"));
    verify(backendApiClient, never()).put(any(), any(), any());
  }

  // ── POST /profile/payout-preference ──────────────────────────────────────

  @Test
  void updatePayoutPreference_happyPath_putsAndRedirectsWithSuccessToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.DONATE, 2L);

    String view =
        controller.updatePayoutPreference(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);

    ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .put(eq("/api/v1/users/me/payout-preference"), bodyCap.capture(), eq(Void.class));
    Map<String, Object> body = bodyCap.getValue();
    assertEquals("DONATE", body.get("preference"));
    assertEquals(2L, body.get("version"));
    verify(redirectAttributes).addFlashAttribute("successToast", "notification.success.save");
  }

  @Test
  void updatePayoutPreference_optimisticLockConflict_setsConcurrencyToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    BackendServiceException conflict =
        org.mockito.Mockito.spy(
            new BackendServiceException(
                "concurrency-conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), null));
    org.mockito.Mockito.doReturn("concurrency-conflict").when(conflict).getProblemType();
    doThrow(conflict)
        .when(backendApiClient)
        .put(eq("/api/v1/users/me/payout-preference"), any(), eq(Void.class));

    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.PAYOUT, 1L);
    String view =
        controller.updatePayoutPreference(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);
    verify(redirectAttributes).addFlashAttribute("errorToast", "error.concurrency.conflict");
  }

  @Test
  void updatePayoutPreference_genericException_setsGenericErrorToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    doThrow(new RuntimeException("network")).when(backendApiClient).put(any(), any(), any());

    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.PAYOUT, 1L);
    String view =
        controller.updatePayoutPreference(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);
    verify(redirectAttributes).addFlashAttribute("errorToast", "error.profile.update.failed");
  }

  @Test
  void updatePayoutPreference_validationError_rendersProfileViewWithoutBackendCall() {
    // A binding/type-conversion error (e.g. an unparseable version) must re-render the profile
    // view inline — keeping the BindingResult request-scoped so it never serialises through a
    // Redis FlashMap — and must NOT issue the PUT or set a toast.
    when(bindingResult.hasErrors()).thenReturn(true);
    // Stub the bits the re-entrant profile() render needs.
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(1);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);
    when(backendApiClient.<Map<String, Object>>get(any(String.class), anyTypeRef()))
        .thenReturn(null);

    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.PAYOUT, 1L);

    String view =
        controller.updatePayoutPreference(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("profile", view);
    verify(backendApiClient, never()).put(any(), any(), any());
    verifyNoInteractions(redirectAttributes);
  }

  // ── POST /profile/payout-preference (AJAX / krtFetch — epic #571) ─────────

  @Test
  void updatePayoutPreferenceAjax_happyPath_returns200WithRefreshedVersion() {
    when(bindingResult.hasErrors()).thenReturn(false);
    when(backendApiClient.<Map<String, Object>>get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(Map.of("version", 7L));

    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.DONATE, 6L);
    ResponseEntity<Map<String, Object>> response =
        controller.updatePayoutPreferenceAjax(form, bindingResult, principal);

    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(7L, body.get("version"));
    assertEquals("DONATE", body.get("defaultPayoutPreference"));

    ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .put(eq("/api/v1/users/me/payout-preference"), bodyCap.capture(), eq(Void.class));
    assertEquals("DONATE", bodyCap.getValue().get("preference"));
    assertEquals(6L, bodyCap.getValue().get("version"));
  }

  @Test
  void updatePayoutPreferenceAjax_optimisticLockConflict_returns409WithOptimisticLockCode() {
    when(bindingResult.hasErrors()).thenReturn(false);
    BackendServiceException conflict =
        org.mockito.Mockito.spy(
            new BackendServiceException(
                "concurrency-conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), null));
    org.mockito.Mockito.doReturn("concurrency-conflict").when(conflict).getProblemType();
    doThrow(conflict)
        .when(backendApiClient)
        .put(eq("/api/v1/users/me/payout-preference"), any(), eq(Void.class));
    when(messageSource.getMessage(any(), any(), any(), any())).thenReturn("conflict-message");

    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.PAYOUT, 1L);
    ResponseEntity<Map<String, Object>> response =
        controller.updatePayoutPreferenceAjax(form, bindingResult, principal);

    assertEquals(409, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("OPTIMISTIC_LOCK", response.getBody().get("code"));
    assertEquals("conflict-message", response.getBody().get("detail"));
  }

  @Test
  void updatePayoutPreferenceAjax_validationError_returns400WithoutBackendCall() {
    when(bindingResult.hasErrors()).thenReturn(true);
    when(bindingResult.getFieldErrors()).thenReturn(List.of());
    when(messageSource.getMessage(any(), any(), any(), any())).thenReturn("validation-failed");

    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.PAYOUT, 1L);
    ResponseEntity<Map<String, Object>> response =
        controller.updatePayoutPreferenceAjax(form, bindingResult, principal);

    assertEquals(400, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("validation-failed", response.getBody().get("detail"));
    verify(backendApiClient, never()).put(any(), any(), any());
  }

  // ── POST /profile/blueprint-sharing (REQ-INV-018) ────────────────────────

  @Test
  void updateBlueprintSharing_happyPath_putsAndRedirectsWithSuccessToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    ProfileBlueprintSharingForm form = new ProfileBlueprintSharingForm(true, 2L);

    String view =
        controller.updateBlueprintSharing(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);

    ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .put(eq("/api/v1/users/me/blueprint-sharing"), bodyCap.capture(), eq(Void.class));
    Map<String, Object> body = bodyCap.getValue();
    assertEquals(true, body.get("shareBlueprintsGlobally"));
    assertEquals(2L, body.get("version"));
    verify(redirectAttributes).addFlashAttribute("successToast", "notification.success.save");
  }

  @Test
  void updateBlueprintSharingAjax_happyPath_returns200WithRefreshedVersion() {
    when(bindingResult.hasErrors()).thenReturn(false);
    when(backendApiClient.<Map<String, Object>>get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(Map.of("version", 9L));

    ProfileBlueprintSharingForm form = new ProfileBlueprintSharingForm(true, 8L);
    ResponseEntity<Map<String, Object>> response =
        controller.updateBlueprintSharingAjax(form, bindingResult, principal);

    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(9L, body.get("version"));
    assertEquals(true, body.get("shareBlueprintsGlobally"));

    ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .put(eq("/api/v1/users/me/blueprint-sharing"), bodyCap.capture(), eq(Void.class));
    assertEquals(true, bodyCap.getValue().get("shareBlueprintsGlobally"));
    assertEquals(8L, bodyCap.getValue().get("version"));
  }

  @Test
  void updateBlueprintSharingAjax_optimisticLockConflict_returns409WithOptimisticLockCode() {
    when(bindingResult.hasErrors()).thenReturn(false);
    BackendServiceException conflict =
        org.mockito.Mockito.spy(
            new BackendServiceException(
                "concurrency-conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), null));
    org.mockito.Mockito.doReturn("concurrency-conflict").when(conflict).getProblemType();
    doThrow(conflict)
        .when(backendApiClient)
        .put(eq("/api/v1/users/me/blueprint-sharing"), any(), eq(Void.class));
    when(messageSource.getMessage(any(), any(), any(), any())).thenReturn("conflict-message");

    ProfileBlueprintSharingForm form = new ProfileBlueprintSharingForm(false, 1L);
    ResponseEntity<Map<String, Object>> response =
        controller.updateBlueprintSharingAjax(form, bindingResult, principal);

    assertEquals(409, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("OPTIMISTIC_LOCK", response.getBody().get("code"));
    assertEquals("conflict-message", response.getBody().get("detail"));
  }
}
