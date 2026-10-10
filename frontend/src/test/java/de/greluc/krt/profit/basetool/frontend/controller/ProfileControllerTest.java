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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.frontend.model.dto.MyBlueprintSharingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MyBlueprintSharingResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.MyPayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MyPayoutPreferenceResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDescriptionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileBlueprintSharingForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfileDescriptionForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ProfilePayoutPreferenceForm;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Mockito unit tests for {@link ProfileController}.
 *
 * <ul>
 *   <li>Backend data overrides OIDC token claims.
 *   <li>Join-date and months-in-squadron computation.
 *   <li>POST /profile/description success, validation, optimistic-lock and generic-error branches.
 *   <li>Multi-valued OIDC claims.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

  private static final String ME = "/api/v1/users/me";

  @Mock private BackendApiClient backendApiClient;
  @Mock private OidcUser principal;
  @Mock private RedirectAttributes redirectAttributes;
  @Mock private BindingResult bindingResult;
  @Mock private MessageSource messageSource;

  private ProfileController controller;

  @BeforeEach
  void setUp() {
    controller =
        new ProfileController(
            new IdentityBackendClient(backendApiClient),
            new NotificationBackendClient(backendApiClient),
            messageSource);
    ReflectionTestUtils.setField(controller, "issuerUri", "https://kc.example.com/realms/iri");
  }

  private static UserDto user(
      Integer rank,
      String description,
      String displayName,
      Long version,
      LocalDate joinDate,
      List<SquadronReferenceDto> squadrons) {
    return new UserDto(
        UUID.fromString("0b6f7c1e-3a52-4d8e-9f10-2c3d4e5f6a7b"),
        "jdoe",
        displayName,
        displayName,
        null,
        rank,
        description,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        squadrons,
        version,
        joinDate,
        null);
  }

  @Test
  void profile_authenticated_populatesModelFromTokenAndBackendOverride() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getEmail()).thenReturn("jdoe@example.com");
    when(principal.getAttribute("rank")).thenReturn(3);
    when(principal.getAttribute("description")).thenReturn("From-Token");
    when(principal.getAttribute("displayName")).thenReturn("JD");

    List<SquadronReferenceDto> squadrons =
        List.of(
            new SquadronReferenceDto(
                UUID.fromString("5a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), "IRIDIUM", "IRI"));
    when(backendApiClient.get(ME, UserDto.class))
        .thenReturn(
            user(7, "From-Backend", "Backend-DN", 4L, LocalDate.of(2024, 1, 15), squadrons));
    when(backendApiClient.get(
            "/api/v1/users/me/payout-preference", MyPayoutPreferenceResponse.class))
        .thenReturn(new MyPayoutPreferenceResponse("DONATE", 4L));
    when(backendApiClient.get(
            "/api/v1/users/me/blueprint-sharing", MyBlueprintSharingResponse.class))
        .thenReturn(new MyBlueprintSharingResponse(true, 4L));

    Model model = new ConcurrentModel();
    String view = controller.profile(model, principal);

    assertEquals("profile", view);
    assertEquals("jdoe", model.getAttribute("username"));
    assertEquals("jdoe@example.com", model.getAttribute("email"));
    assertEquals(7, model.getAttribute("rank"));
    assertEquals("From-Backend", model.getAttribute("description"));
    assertEquals("Backend-DN", model.getAttribute("displayName"));
    assertEquals(4L, model.getAttribute("version"));
    assertEquals(squadrons, model.getAttribute("profileSquadrons"));
    assertEquals(LocalDate.of(2024, 1, 15), model.getAttribute("joinDate"));
    Long months = (Long) model.getAttribute("monthsInSquadron");
    assertNotNull(months);
    assertTrue(months >= 12, "expected at least one year, was " + months);
    assertEquals(
        "https://kc.example.com/realms/iri/account", model.getAttribute("keycloakAccountUrl"));
    ProfileDescriptionForm form =
        (ProfileDescriptionForm) model.getAttribute("profileDescriptionForm");
    assertNotNull(form);
    assertEquals("From-Backend", form.description());
    assertEquals("Backend-DN", form.displayName());
    assertEquals(4L, form.version());
    assertEquals(PayoutPreference.DONATE, model.getAttribute("defaultPayoutPreference"));
    ProfilePayoutPreferenceForm payoutForm =
        (ProfilePayoutPreferenceForm) model.getAttribute("profilePayoutPreferenceForm");
    assertNotNull(payoutForm);
    assertEquals(PayoutPreference.DONATE, payoutForm.defaultPayoutPreference());
    assertEquals(4L, payoutForm.version());
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
    when(backendApiClient.get(any(String.class), any(Class.class)))
        .thenThrow(new RuntimeException("backend down"));

    Model model = new ConcurrentModel();
    String view = controller.profile(model, principal);

    assertEquals("profile", view);
    assertEquals(3, model.getAttribute("rank"));
    assertEquals("From-Token", model.getAttribute("description"));
    assertEquals("JD", model.getAttribute("displayName"));
    assertEquals(PayoutPreference.PAYOUT, model.getAttribute("defaultPayoutPreference"));
    assertEquals(false, model.getAttribute("shareBlueprintsGlobally"));
    assertEquals(true, model.getAttribute("deletionRequestUnavailable"));
  }

  @Test
  void profile_multiValueClaim_returnsFirstElement() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(List.of(5, 6));
    when(principal.getAttribute("description")).thenReturn(List.of("first", "second"));
    when(principal.getAttribute("displayName")).thenReturn(java.util.List.of("DN"));

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(5, model.getAttribute("rank"));
    assertEquals("first", model.getAttribute("description"));
    assertEquals("DN", model.getAttribute("displayName"));
  }

  @Test
  void profile_emptyListClaim_returnsList() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(List.of());
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);

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

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(2, model.getAttribute("rank"));
    assertEquals("Desc", model.getAttribute("description"));
    assertNull(model.getAttribute("version"), "no version was set when backend returned null");
  }

  @Test
  void profile_authenticated_backendUserWithoutRank_keepsTokenRank() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn("Token-Desc");
    when(principal.getAttribute("displayName")).thenReturn("Token-DN");
    when(backendApiClient.get(ME, UserDto.class))
        .thenReturn(user(null, "Backend-Desc", "Backend-DN", 3L, null, null));

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(2, model.getAttribute("rank"));
    assertEquals("Backend-Desc", model.getAttribute("description"));
    assertEquals("Backend-DN", model.getAttribute("displayName"));
    assertNull(model.getAttribute("profileSquadrons"));
  }

  @Test
  void profile_withoutJoinDate_setsNoTenure() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);
    when(backendApiClient.get(ME, UserDto.class)).thenReturn(user(4, null, null, 1L, null, null));

    Model model = new ConcurrentModel();
    assertEquals("profile", controller.profile(model, principal));
    assertNull(model.getAttribute("joinDate"));
    assertNull(model.getAttribute("monthsInSquadron"));
  }

  @Test
  void profile_backendUserWithoutVersion_fallsBackToZero() {
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(2);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);
    when(backendApiClient.get(ME, UserDto.class)).thenReturn(user(1, null, null, null, null, null));

    Model model = new ConcurrentModel();
    controller.profile(model, principal);

    assertEquals(0L, model.getAttribute("version"));
  }

  @Test
  void updateDescription_happyPath_putsAndRedirectsWithSuccessToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    ProfileDescriptionForm form = new ProfileDescriptionForm("New desc", "New DN", 2L);

    String view =
        controller.updateDescription(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);
    verify(backendApiClient)
        .put(
            "/api/v1/users/me/description",
            new UserDescriptionRequest("New desc", "New DN", 2L),
            Void.class);
    verify(redirectAttributes).addFlashAttribute("successToast", "notification.success.save");
  }

  @Test
  void updateDescription_nullFields_sendEmptyStrings() {
    when(bindingResult.hasErrors()).thenReturn(false);
    ProfileDescriptionForm form = new ProfileDescriptionForm(null, null, 1L);

    controller.updateDescription(
        form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    ArgumentCaptor<UserDescriptionRequest> bodyCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .put(eq("/api/v1/users/me/description"), bodyCap.capture(), eq(Void.class));
    assertEquals("", bodyCap.getValue().description());
    assertEquals("", bodyCap.getValue().displayName());
  }

  @Test
  void updateDescription_validationError_rendersProfileViewWithoutBackendCall() {
    when(bindingResult.hasErrors()).thenReturn(true);
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(1);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);

    ProfileDescriptionForm form = new ProfileDescriptionForm("", "", 1L);

    String view =
        controller.updateDescription(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("profile", view);
    verify(backendApiClient, never()).put(any(), any(), any());
    verifyNoInteractions(redirectAttributes);
  }

  @Test
  void updateDescription_optimisticLockConflict_setsConcurrencyToast() {
    when(bindingResult.hasErrors()).thenReturn(false);

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

  @Test
  void updateDescriptionAjax_happyPath_returns200WithRefreshedVersion() {
    when(bindingResult.hasErrors()).thenReturn(false);
    when(backendApiClient.get(ME, UserDto.class)).thenReturn(user(1, null, null, 5L, null, null));

    ProfileDescriptionForm form = new ProfileDescriptionForm("New desc", "New DN", 4L);
    ResponseEntity<Map<String, Object>> response =
        controller.updateDescriptionAjax(form, bindingResult, principal);

    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(5L, body.get("version"));
    assertEquals("New desc", body.get("description"));
    assertEquals("New DN", body.get("displayName"));
    verify(backendApiClient)
        .put(
            "/api/v1/users/me/description",
            new UserDescriptionRequest("New desc", "New DN", 4L),
            Void.class);
  }

  @Test
  void updateDescriptionAjax_versionRefreshFails_returnsPriorVersionPlusOne() {
    when(bindingResult.hasErrors()).thenReturn(false);
    when(backendApiClient.get(ME, UserDto.class)).thenThrow(new RuntimeException("backend down"));

    ProfileDescriptionForm form = new ProfileDescriptionForm("d", "n", 4L);
    ResponseEntity<Map<String, Object>> response =
        controller.updateDescriptionAjax(form, bindingResult, principal);

    assertNotNull(response.getBody());
    assertEquals(5L, response.getBody().get("version"));
  }

  @Test
  void updateDescriptionAjax_optimisticLockConflict_returns409WithOptimisticLockCode() {
    when(bindingResult.hasErrors()).thenReturn(false);
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

  @Test
  void updatePayoutPreference_happyPath_putsAndRedirectsWithSuccessToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.DONATE, 2L);

    String view =
        controller.updatePayoutPreference(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);
    verify(backendApiClient)
        .put(
            "/api/v1/users/me/payout-preference",
            new MyPayoutPreferenceRequest("DONATE", 2L),
            Void.class);
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
    when(bindingResult.hasErrors()).thenReturn(true);
    when(principal.getPreferredUsername()).thenReturn("jdoe");
    when(principal.getAttribute("rank")).thenReturn(1);
    when(principal.getAttribute("description")).thenReturn(null);
    when(principal.getAttribute("displayName")).thenReturn(null);

    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.PAYOUT, 1L);

    String view =
        controller.updatePayoutPreference(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("profile", view);
    verify(backendApiClient, never()).put(any(), any(), any());
    verifyNoInteractions(redirectAttributes);
  }

  @Test
  void updatePayoutPreferenceAjax_happyPath_returns200WithRefreshedVersion() {
    when(bindingResult.hasErrors()).thenReturn(false);
    when(backendApiClient.get(ME, UserDto.class)).thenReturn(user(1, null, null, 7L, null, null));

    ProfilePayoutPreferenceForm form = new ProfilePayoutPreferenceForm(PayoutPreference.DONATE, 6L);
    ResponseEntity<Map<String, Object>> response =
        controller.updatePayoutPreferenceAjax(form, bindingResult, principal);

    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(7L, body.get("version"));
    assertEquals("DONATE", body.get("defaultPayoutPreference"));
    verify(backendApiClient)
        .put(
            "/api/v1/users/me/payout-preference",
            new MyPayoutPreferenceRequest("DONATE", 6L),
            Void.class);
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

  @Test
  void updateBlueprintSharing_happyPath_putsAndRedirectsWithSuccessToast() {
    when(bindingResult.hasErrors()).thenReturn(false);
    ProfileBlueprintSharingForm form = new ProfileBlueprintSharingForm(true, 2L);

    String view =
        controller.updateBlueprintSharing(
            form, bindingResult, new ConcurrentModel(), principal, redirectAttributes);

    assertEquals("redirect:/profile", view);
    verify(backendApiClient)
        .put(
            "/api/v1/users/me/blueprint-sharing",
            new MyBlueprintSharingRequest(true, 2L),
            Void.class);
    verify(redirectAttributes).addFlashAttribute("successToast", "notification.success.save");
  }

  @Test
  void updateBlueprintSharingAjax_happyPath_returns200WithRefreshedVersion() {
    when(bindingResult.hasErrors()).thenReturn(false);
    when(backendApiClient.get(ME, UserDto.class)).thenReturn(user(1, null, null, 9L, null, null));

    ProfileBlueprintSharingForm form = new ProfileBlueprintSharingForm(true, 8L);
    ResponseEntity<Map<String, Object>> response =
        controller.updateBlueprintSharingAjax(form, bindingResult, principal);

    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    assertEquals(9L, body.get("version"));
    assertEquals(true, body.get("shareBlueprintsGlobally"));
    verify(backendApiClient)
        .put(
            "/api/v1/users/me/blueprint-sharing",
            new MyBlueprintSharingRequest(true, 8L),
            Void.class);
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
