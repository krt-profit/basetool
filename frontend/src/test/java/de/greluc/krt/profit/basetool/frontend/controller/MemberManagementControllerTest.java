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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.MembershipDeltaResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserAttributesUpdateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.MemberEditForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class MemberManagementControllerTest {

  private BackendApiClient backendApiClient;
  private org.springframework.context.MessageSource messageSource;
  private MemberManagementController controller;
  private RedirectAttributes redirectAttributes;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    messageSource = mock(org.springframework.context.MessageSource.class);
    controller = new MemberManagementController(backendApiClient, messageSource);
    redirectAttributes = new RedirectAttributesModelMap();
  }

  // ---------------------------------------------------------------
  // deleteMember (existing tests preserved verbatim)
  // ---------------------------------------------------------------

  @Test
  void deleteMember_ShouldRedirectAndAddSuccessToast() {
    UUID userId = UUID.randomUUID();

    String view = controller.deleteMember(userId, redirectAttributes);

    verify(backendApiClient).delete("/api/v1/users/" + userId, Void.class);
    assertEquals("redirect:/members", view);
    assertEquals(
        "success.user.delete", redirectAttributes.getFlashAttributes().get("successToast"));
  }

  @Test
  void deleteMember_OnFailure_ShouldRedirectAndAddErrorToast() {
    UUID userId = UUID.randomUUID();
    doThrow(new RuntimeException("API Error")).when(backendApiClient).delete(anyString(), any());

    String view = controller.deleteMember(userId, redirectAttributes);

    verify(backendApiClient).delete("/api/v1/users/" + userId, Void.class);
    assertEquals("redirect:/members", view);
    assertEquals("error.user.delete", redirectAttributes.getFlashAttributes().get("errorToast"));
  }

  // ---------------------------------------------------------------
  // listMembers — search query assembly + page injection + error path
  // ---------------------------------------------------------------

  @Nested
  class ListMembersTests {

    @Test
    void noSearch_appendsSortOnlyToBaseUri() {
      Model model = new ConcurrentModel();
      PageResponse<UserDto> page = newPage(List.of(newUser("alice")));
      when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

      String view = controller.listMembers(null, null, null, null, model);

      assertEquals("members", view);
      // SPEZIALKOMMANDO_PLAN.md §7.5: listMembers now also fetches per-user memberships for the
      // SK column. Verify the FIRST call (the users-list endpoint) — subsequent membership calls
      // are exercised by their own test.
      ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
      verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
          .get(uriCaptor.capture(), anyTypeRef());
      assertEquals(
          "/api/v1/users?sort=username,asc",
          uriCaptor.getAllValues().get(0),
          "first backend call must hit the users list endpoint");
      assertEquals(page.content(), model.getAttribute("users"));
      assertSame(page, model.getAttribute("usersPage"));
      assertNull(model.getAttribute("search"));
    }

    @Test
    void withSearch_routesToSearchEndpointWithQueryParam() {
      Model model = new ConcurrentModel();
      when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(newPage(List.of()));

      controller.listMembers("alice", null, null, null, model);

      ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
      verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef());
      String uri = uriCaptor.getValue();
      assertTrue(
          uri.startsWith("/api/v1/users/search?query=alice"),
          "uri must start with /api/v1/users/search?query=alice, got: " + uri);
      assertTrue(uri.endsWith("sort=username,asc"));
      assertEquals("alice", model.getAttribute("search"));
    }

    @Test
    void blankSearch_routesToListEndpoint() {
      // Treat blank search as "no search" — uses the listing endpoint, not search.
      Model model = new ConcurrentModel();
      when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(newPage(List.of()));

      controller.listMembers("   ", null, null, null, model);

      ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
      verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef());
      assertTrue(uriCaptor.getValue().startsWith("/api/v1/users?"));
    }

    @Test
    void pageAndSizeParams_appendedToUri() {
      Model model = new ConcurrentModel();
      when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(newPage(List.of()));

      controller.listMembers(null, 2, 25, null, model);

      ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
      verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef());
      String uri = uriCaptor.getValue();
      assertTrue(uri.contains("page=2"), "page param missing in: " + uri);
      assertTrue(uri.contains("size=25"));
    }

    @Test
    void nullPageResponse_setsNullUsersAndNullPage() {
      // Defensive: if the backend returns null (e.g. mid-degradation), the model
      // must NOT NPE — users stays null and the view still renders.
      Model model = new ConcurrentModel();
      when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);

      String view = controller.listMembers(null, null, null, null, model);

      assertEquals("members", view);
      assertNull(model.getAttribute("users"));
      assertNull(model.getAttribute("usersPage"));
    }

    @Test
    void backendError_setsErrorAttribute_andStillReturnsView() {
      Model model = new ConcurrentModel();
      doThrow(new RuntimeException("backend down"))
          .when(backendApiClient)
          .get(anyString(), anyTypeRef());

      String view = controller.listMembers(null, null, null, null, model);

      assertEquals("members", view, "error path must NOT redirect — direct view render");
      assertEquals("error.members.load", model.getAttribute("error"));
    }

    @Test
    void fragmentRequest_returnsResultsFragmentSelector() {
      // Given — an AJAX swap request (fragment=true) for in-place filter/paging (#573).
      Model model = new ConcurrentModel();
      when(backendApiClient.get(anyString(), anyTypeRef()))
          .thenReturn(newPage(List.of(newUser("alice"))));

      // When
      String view = controller.listMembers(null, null, null, "results", model);

      // Then — only the table fragment is rendered, not the full page.
      assertEquals("members :: membersTableFragment", view);
    }
  }

  // ---------------------------------------------------------------
  // searchMembers — JSON API endpoint
  // ---------------------------------------------------------------

  @Test
  void searchMembers_returnsContentList() {
    PageResponse<UserDto> page = newPage(List.of(newUser("alice"), newUser("bob")));
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    List<UserDto> result = controller.searchMembers("ali");

    assertEquals(2, result.size());
  }

  @Test
  void searchMembers_nullResponse_returnsNull() {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);

    assertNull(controller.searchMembers("ali"));
  }

  // ---------------------------------------------------------------
  // editMember
  // ---------------------------------------------------------------

  @Nested
  class EditMemberTests {

    @Test
    void happyPath_setsUserAndPrefilledForm() {
      UUID id = UUID.randomUUID();
      UserDto user = newUser("alice");
      Model model = new ConcurrentModel();
      when(backendApiClient.get(eq("/api/v1/users/" + id), eq(UserDto.class))).thenReturn(user);

      String view = controller.editMember(id, null, model, redirectAttributes);

      assertEquals("member-edit", view);
      assertSame(user, model.getAttribute("user"));
      MemberEditForm form = (MemberEditForm) model.getAttribute("memberEditForm");
      assertNotNull(form);
      assertEquals(user.rank(), form.rank());
      assertEquals(user.description(), form.description());
      assertEquals(user.version(), form.version());
    }

    @Test
    void prefilledFormWithNullSource_isReplacedWithSourceParam() {
      // If a form is already in the model (e.g. after a redirect with flash) but
      // its source is null, the controller substitutes the request's source param.
      UUID id = UUID.randomUUID();
      UserDto user = newUser("alice");
      Model model = new ConcurrentModel();

      MemberEditForm existingForm =
          new MemberEditForm(
              5, "old desc", "alice", 1L, null, null, null, null, null, null, null, null);
      model.addAttribute("memberEditForm", existingForm);

      when(backendApiClient.get(eq("/api/v1/users/" + id), eq(UserDto.class))).thenReturn(user);

      controller.editMember(id, "profile", model, redirectAttributes);

      MemberEditForm form = (MemberEditForm) model.getAttribute("memberEditForm");
      assertEquals(
          "profile",
          form.source(),
          "null source on the existing form must be replaced by the request param");
      assertEquals(existingForm.rank(), form.rank(), "other fields must be preserved verbatim");
    }

    @Test
    void prefilledFormWithExistingSource_isNotTouched() {
      UUID id = UUID.randomUUID();
      UserDto user = newUser("alice");
      Model model = new ConcurrentModel();

      MemberEditForm existingForm =
          new MemberEditForm(
              5,
              "old desc",
              "alice",
              1L,
              "existing-source",
              null,
              null,
              null,
              null,
              null,
              null,
              null);
      model.addAttribute("memberEditForm", existingForm);

      when(backendApiClient.get(eq("/api/v1/users/" + id), eq(UserDto.class))).thenReturn(user);

      controller.editMember(id, "different-source", model, redirectAttributes);

      MemberEditForm form = (MemberEditForm) model.getAttribute("memberEditForm");
      assertSame(existingForm, form, "existing form with non-null source must NOT be replaced");
    }

    @Test
    void backendError_redirectsToListWithErrorToast() {
      UUID id = UUID.randomUUID();
      Model model = new ConcurrentModel();
      doThrow(new RuntimeException("not found"))
          .when(backendApiClient)
          .get(anyString(), anyClass());

      String view = controller.editMember(id, null, model, redirectAttributes);

      assertEquals("redirect:/members", view);
      assertEquals(
          "error.member.details.load", redirectAttributes.getFlashAttributes().get("errorToast"));
    }

    @Test
    void detailFetchFails_marksFormStaffelDetailNotLoaded_withBlankSlots() {
      // REQ-ORG-017 wipe-guard: a transient failure of GET /memberships/detail (resilience timeout
      // /
      // open circuit breaker) — while the main user GET succeeds — must NOT render seeded slots.
      // The form is flagged staffelDetailLoaded=false so the save skips the Staffel reconcile.
      UUID id = UUID.randomUUID();
      UserDto user = newUser("alice");
      Model model = new ConcurrentModel();
      when(backendApiClient.get(eq("/api/v1/users/" + id), eq(UserDto.class))).thenReturn(user);
      doThrow(new RuntimeException("detail unavailable"))
          .when(backendApiClient)
          .get(
              eq("/api/v1/users/" + id + "/memberships/detail"), eq(MembershipDeltaResponse.class));

      String view = controller.editMember(id, null, model, redirectAttributes);

      assertEquals("member-edit", view);
      MemberEditForm form = (MemberEditForm) model.getAttribute("memberEditForm");
      assertNotNull(form);
      assertEquals(
          Boolean.FALSE,
          form.staffelDetailLoaded(),
          "a failed Staffel-detail load must flag the form so the save skips the reconcile");
      assertNull(
          form.staffel1Id(), "slots stay blank when the authoritative detail is unavailable");
      assertNull(form.staffel2Id());
    }

    @Test
    void detailFetchSucceeds_marksFormLoaded_andSeedsSlotsWithFlags() {
      UUID id = UUID.randomUUID();
      UUID squadronId = UUID.randomUUID();
      UserDto user = newUser("alice");
      Model model = new ConcurrentModel();
      when(backendApiClient.get(eq("/api/v1/users/" + id), eq(UserDto.class))).thenReturn(user);
      MembershipDeltaResponse detail =
          new MembershipDeltaResponse(
              List.of(
                  new OrgUnitMembershipDto(
                      id,
                      "alice",
                      squadronId,
                      OrgUnitKind.SQUADRON,
                      true,
                      false,
                      false,
                      null,
                      1L)));
      when(backendApiClient.get(
              eq("/api/v1/users/" + id + "/memberships/detail"), eq(MembershipDeltaResponse.class)))
          .thenReturn(detail);

      controller.editMember(id, null, model, redirectAttributes);

      MemberEditForm form = (MemberEditForm) model.getAttribute("memberEditForm");
      assertNotNull(form);
      assertEquals(
          Boolean.TRUE,
          form.staffelDetailLoaded(),
          "a successful Staffel-detail load lets the save reconcile the slots as the desired set");
      assertEquals(squadronId, form.staffel1Id());
      assertEquals(Boolean.TRUE, form.staffel1Logistician());
    }
  }

  // ---------------------------------------------------------------
  // updateMember — validation errors + happy path + backend failure + source routing
  // ---------------------------------------------------------------

  @Nested
  class UpdateMemberTests {

    @Test
    void validationErrors_reRenderForm_doNotRedirect() {
      // bindingResult.hasErrors() -> direct render via editMember(...) without
      // flash. The BindingResult stays request-scoped (the fix for the
      // RedisSessionConfig flash-cycle bug).
      UUID id = UUID.randomUUID();
      UserDto user = newUser("alice");
      Model model = new ConcurrentModel();
      MemberEditForm form =
          new MemberEditForm(5, "x", "alice", 1L, null, null, null, null, null, null, null, null);

      when(backendApiClient.get(eq("/api/v1/users/" + id), eq(UserDto.class))).thenReturn(user);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(true);

      String view = controller.updateMember(id, form, br, model, redirectAttributes);

      assertEquals("member-edit", view, "validation errors must re-render directly, NOT redirect");
      verify(backendApiClient, never()).put(anyString(), any(), any());
      assertTrue(
          redirectAttributes.getFlashAttributes().isEmpty(),
          "must NOT add any flash attribute (the BindingResult lives request-scoped)");
    }

    @Test
    void happyPath_putsAttributesAndRedirectsToList() {
      UUID id = UUID.randomUUID();
      Model model = new ConcurrentModel();
      MemberEditForm form =
          new MemberEditForm(
              5, "desc", "Alice", 1L, null, null, null, null, null, null, null, null);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);

      String view = controller.updateMember(id, form, br, model, redirectAttributes);

      assertEquals("redirect:/members", view);
      // Verify the PUT call's body shape.
      ArgumentCaptor<UserAttributesUpdateDto> body =
          ArgumentCaptor.forClass(UserAttributesUpdateDto.class);
      verify(backendApiClient)
          .put(eq("/api/v1/users/" + id + "/attributes"), body.capture(), eq(Void.class));
      assertEquals(5, body.getValue().rank());
      assertEquals("desc", body.getValue().description());
      assertEquals("Alice", body.getValue().displayName());
      assertEquals(1L, body.getValue().version());
      assertEquals(
          "notification.success.save", redirectAttributes.getFlashAttributes().get("successToast"));
    }

    @Test
    void happyPath_withProfileSource_redirectsToProfile() {
      UUID id = UUID.randomUUID();
      Model model = new ConcurrentModel();
      MemberEditForm form =
          new MemberEditForm(
              5, "desc", "Alice", 1L, "profile", null, null, null, null, null, null, null);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);

      String view = controller.updateMember(id, form, br, model, redirectAttributes);

      assertEquals(
          "redirect:/profile",
          view,
          "source=profile must route the post-save redirect to /profile");
    }

    @Test
    void backendError_redirectsToEditWithErrorToast() {
      UUID id = UUID.randomUUID();
      Model model = new ConcurrentModel();
      MemberEditForm form =
          new MemberEditForm(
              5, "desc", "Alice", 1L, null, null, null, null, null, null, null, null);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);
      doThrow(new RuntimeException("backend down"))
          .when(backendApiClient)
          .put(anyString(), any(), any());

      String view = controller.updateMember(id, form, br, model, redirectAttributes);

      assertEquals("redirect:/members/" + id + "/edit", view);
      assertEquals(
          "error.member.update.failed", redirectAttributes.getFlashAttributes().get("errorToast"));
    }

    @Test
    void backendError_withSource_redirectsToEditPreservingSource() {
      UUID id = UUID.randomUUID();
      Model model = new ConcurrentModel();
      MemberEditForm form =
          new MemberEditForm(
              5, "desc", "Alice", 1L, "profile", null, null, null, null, null, null, null);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);
      doThrow(new RuntimeException("nope")).when(backendApiClient).put(anyString(), any(), any());

      String view = controller.updateMember(id, form, br, model, redirectAttributes);

      assertEquals(
          "redirect:/members/" + id + "/edit?source=profile",
          view,
          "the source param must be preserved on the failure redirect so the user "
              + "lands back on the same view");
    }

    @Test
    void detailLoadFailed_skipsMembershipReconcilePatch() {
      // REQ-ORG-017 wipe-guard (save half): when the form carries staffelDetailLoaded=false the
      // membership PATCH is skipped entirely, so the blank slots cannot strip the member's
      // Staffeln.
      UUID id = UUID.randomUUID();
      Model model = new ConcurrentModel();
      MemberEditForm form =
          new MemberEditForm(
              5, "desc", "Alice", 1L, null, null, null, null, null, null, null, null, false);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);

      controller.updateMember(id, form, br, model, redirectAttributes);

      verify(backendApiClient)
          .put(eq("/api/v1/users/" + id + "/attributes"), any(), eq(Void.class));
      verify(backendApiClient, never()).patch(anyString(), any(), any());
    }

    @Test
    void detailLoaded_sendsMembershipReconcilePatch() {
      UUID id = UUID.randomUUID();
      Model model = new ConcurrentModel();
      MemberEditForm form =
          new MemberEditForm(
              5, "desc", "Alice", 1L, null, null, null, null, null, null, null, null, true);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);

      controller.updateMember(id, form, br, model, redirectAttributes);

      verify(backendApiClient)
          .put(eq("/api/v1/users/" + id + "/attributes"), any(), eq(Void.class));
      verify(backendApiClient).patch(eq("/api/v1/users/" + id + "/memberships"), any(), any());
    }
  }

  // ---------------------------------------------------------------
  // updateMemberAjax / deleteMemberAjax — in-place JSON twins (epic #571)
  // ---------------------------------------------------------------

  @Nested
  class AjaxTwinTests {

    @Test
    void updateMemberAjax_validationError_returns422WithFieldMessages() {
      UUID id = UUID.randomUUID();
      MemberEditForm form =
          new MemberEditForm(5, "x", "alice", 1L, null, null, null, null, null, null, null, null);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(true);
      org.springframework.validation.FieldError fe =
          new org.springframework.validation.FieldError(
              "memberEditForm", "description", "too long");
      when(br.getFieldErrors()).thenReturn(List.of(fe));
      when(messageSource.getMessage(eq(fe), any())).thenReturn("Description too long");

      org.springframework.http.ResponseEntity<java.util.Map<String, Object>> response =
          controller.updateMemberAjax(id, form, br, java.util.Locale.ENGLISH);

      assertEquals(422, response.getStatusCode().value());
      assertNotNull(response.getBody());
      assertEquals("Description too long", response.getBody().get("description"));
      verify(backendApiClient, never()).put(anyString(), any(), any());
    }

    @Test
    void updateMemberAjax_happyPath_returns200WithRefreshedVersion() {
      UUID id = UUID.randomUUID();
      MemberEditForm form =
          new MemberEditForm(
              5, "desc", "Alice", 4L, null, null, null, null, null, null, null, null);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);
      // The refreshed re-fetch (no squadron/flag change) AND the post-save version read both hit
      // /api/v1/users/{id}; the stub's version (1L) is what the twin echoes back to the form.
      when(backendApiClient.get(eq("/api/v1/users/" + id), eq(UserDto.class)))
          .thenReturn(newUser("alice"));

      org.springframework.http.ResponseEntity<java.util.Map<String, Object>> response =
          controller.updateMemberAjax(id, form, br, java.util.Locale.ENGLISH);

      assertEquals(200, response.getStatusCode().value());
      assertNotNull(response.getBody());
      assertEquals(1L, response.getBody().get("version"));
      verify(backendApiClient)
          .put(eq("/api/v1/users/" + id + "/attributes"), any(), eq(Void.class));
    }

    @Test
    void updateMemberAjax_backendConflict_relaysStatusAndCodeAndDetail() {
      UUID id = UUID.randomUUID();
      MemberEditForm form =
          new MemberEditForm(
              5, "desc", "Alice", 4L, null, null, null, null, null, null, null, null);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);
      doThrow(
              new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                  "conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "Stale data"))
          .when(backendApiClient)
          .put(eq("/api/v1/users/" + id + "/attributes"), any(), eq(Void.class));

      org.springframework.http.ResponseEntity<java.util.Map<String, Object>> response =
          controller.updateMemberAjax(id, form, br, java.util.Locale.ENGLISH);

      assertEquals(409, response.getStatusCode().value());
      assertNotNull(response.getBody());
      assertEquals("OPTIMISTIC_LOCK", response.getBody().get("code"));
      assertEquals("Stale data", response.getBody().get("detail"));
    }

    @Test
    void deleteMemberAjax_success_returns200() {
      UUID id = UUID.randomUUID();

      org.springframework.http.ResponseEntity<java.util.Map<String, Object>> response =
          controller.deleteMemberAjax(id);

      assertEquals(200, response.getStatusCode().value());
      verify(backendApiClient).delete("/api/v1/users/" + id, Void.class);
    }

    @Test
    void deleteMemberAjax_backendFailure_relaysStatusAndCode() {
      UUID id = UUID.randomUUID();
      doThrow(
              new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                  "nope", null, 409, "ENTITY_IN_USE", null, List.of(), "Still in Keycloak"))
          .when(backendApiClient)
          .delete("/api/v1/users/" + id, Void.class);

      org.springframework.http.ResponseEntity<java.util.Map<String, Object>> response =
          controller.deleteMemberAjax(id);

      assertEquals(409, response.getStatusCode().value());
      assertNotNull(response.getBody());
      assertEquals("ENTITY_IN_USE", response.getBody().get("code"));
    }
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private static UserDto newUser(String name) {
    return new UserDto(
        UUID.randomUUID(),
        name,
        name + " display",
        name + " display",
        name + "@example.com",
        5,
        "desc",
        Set.of("ROLE_KRT_MEMBER"),
        Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        false);
  }

  private static PageResponse<UserDto> newPage(List<UserDto> content) {
    return new PageResponse<>(content, 0, 20, content.size(), 1, List.of("username,asc"));
  }
}
