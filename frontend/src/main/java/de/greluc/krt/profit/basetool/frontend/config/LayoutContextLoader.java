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

package de.greluc.krt.profit.basetool.frontend.config;

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Reads the caller's layout context from the backend at most once per request, and not at all for a
 * handler that can never render it (FE-PERF-01, REQ-FE-020).
 *
 * <p><strong>One call instead of four.</strong> The layout advices used to issue up to four
 * uncached backend reads before every page handler — {@code /api/v1/me/active-org-unit}, {@code
 * /api/v1/me/org-units}, {@code /api/v1/me/capabilities} and {@code
 * /api/v1/notifications/unread-count} — each in its own backend transaction, and together they were
 * 62 % of all backend requests. {@code GET /api/v1/me/layout} (REQ-API-012) answers the same four
 * questions through the same resolvers in one read-only transaction. {@link OrgUnitContextAdvice},
 * {@link CapabilityFlagsAdvice} and {@link LayoutMiscAdvice} each ask this loader, and the first
 * one to ask pays the call; the answer is memoised as a request attribute, so the others read it
 * for free. The squadron catalogue stays where it was — a cached read in {@link
 * OrgUnitContextAdvice#availableSquadrons(HttpServletRequest)}.
 *
 * <p><strong>No call for a handler that cannot render.</strong> {@code @ControllerAdvice} selects
 * per controller <em>type</em>, and Spring's {@code ModelFactory} builds the model before the
 * handler runs whether or not it writes a response body — so the 200-odd {@code ResponseBody}
 * handlers inside the view controllers (the in-place mutation endpoints of REQ-FE-001..010, the
 * unread-count poll) each paid for a model Jackson never reads. {@link #needsLayoutModel} looks at
 * the handler the dispatcher already matched and answers {@code false} for one that writes its body
 * directly <em>and</em> declares no {@code ModelAttribute} parameter. The second condition is
 * load-bearing: {@code JobOrderWriteController}'s AJAX create handlers are {@code ResponseBody} and
 * still read {@code canViewJobOrders} as a parameter, so they keep receiving it. A handler that
 * returns a view — a full page or an XHR fragment — always gets the whole model: the list fragments
 * of hangar, missions, refinery orders, the Lager admin and the promotion pages read {@code
 * isAllSquadronsMode}, so no fragment is skipped on the strength of a request header.
 *
 * <p><strong>Fail-closed, as a whole.</strong> The endpoint answers all four parts or none
 * (REQ-API-012), and ADR-0151's fail-closed rule stays here: a failed or empty answer yields {@link
 * LayoutContext#NONE} — no org unit, no pinnable units, every capability off, no unread badge — and
 * that failure is memoised too, so one broken request does not retry three times.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LayoutContextLoader {

  /** Request attribute holding the memoised {@link LayoutContext} of the current request. */
  static final String REQUEST_ATTRIBUTE = LayoutContextLoader.class.getName() + ".context";

  /** The backend endpoint answering the four layout questions in one read-only transaction. */
  static final String LAYOUT_PATH = "/api/v1/me/layout";

  /**
   * Per-handler-method answer of {@link #decide(HandlerMethod)}. A handler's signature never
   * changes at runtime, so reflection runs once per method for the lifetime of the application.
   */
  private static final Map<Method, Boolean> DECISIONS = new ConcurrentHashMap<>();

  /** The single seam to the backend. */
  private final BackendApiClient backendApiClient;

  /** Answers whether the current request carries an authenticated principal. */
  private final FrontendAuthHelperService authHelper;

  /**
   * Returns the caller's layout context, reading {@code GET /api/v1/me/layout} on the first call of
   * a request and the memoised answer on every later one.
   *
   * <p>Answers {@link LayoutContext#NONE} without a backend call when the caller is anonymous or
   * when {@link #needsLayoutModel(HttpServletRequest)} says the matched handler cannot use the
   * model. A backend failure or an empty body also yields {@code NONE}, logged at {@code debug}
   * because the page still renders — with the gated menu entries hidden rather than shown.
   *
   * @param request the current request; its attributes carry the memo and the matched handler
   * @return the caller's layout context; never {@code null}
   */
  @NotNull
  public LayoutContext load(@NotNull HttpServletRequest request) {
    if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof LayoutContext memo) {
      return memo;
    }
    LayoutContext context = fetch(request);
    request.setAttribute(REQUEST_ATTRIBUTE, context);
    return context;
  }

  /**
   * Performs the one backend read behind {@link #load(HttpServletRequest)}, or decides it is not
   * needed.
   *
   * @param request the current request
   * @return the fetched context, or {@link LayoutContext#NONE} when no read was due or it failed
   */
  @NotNull
  private LayoutContext fetch(@NotNull HttpServletRequest request) {
    if (!authHelper.isAuthenticated() || !needsLayoutModel(request)) {
      return LayoutContext.NONE;
    }
    try {
      MeLayoutResponse response = backendApiClient.get(LAYOUT_PATH, MeLayoutResponse.class);
      if (response == null) {
        return LayoutContext.NONE;
      }
      return new LayoutContext(
          response.activeOrgUnitId(),
          response.orgUnits() != null ? List.copyOf(response.orgUnits()) : List.of(),
          response.capabilities() != null ? response.capabilities() : CapabilitiesResponse.NONE,
          response.unreadNotifications());
    } catch (Exception ex) {
      log.debug("Failed to resolve the layout context", ex);
      return LayoutContext.NONE;
    }
  }

  /**
   * Whether the handler the dispatcher matched for this request can use the layout model at all.
   *
   * <p>{@code false} only for a handler method that writes its response body directly — {@code
   * ResponseBody} on the method or its class, or an {@link HttpEntity}, {@link ResponseBodyEmitter}
   * (so also an SSE emitter) or {@link StreamingResponseBody} return type — <em>and</em> declares
   * no {@link ModelAttribute} parameter. Everything else, including a request with no {@link
   * HandlerMethod} attribute, answers {@code true}: when in doubt the model is built, because a
   * missing attribute renders a wrong page while a surplus one only costs a call.
   *
   * @param request the current request, carrying {@link
   *     HandlerMapping#BEST_MATCHING_HANDLER_ATTRIBUTE} once the handler mapping has run
   * @return {@code true} when the layout model may be read by the handler or its view
   */
  public static boolean needsLayoutModel(@NotNull HttpServletRequest request) {
    if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
        instanceof HandlerMethod handler)) {
      return true;
    }
    return DECISIONS.computeIfAbsent(handler.getMethod(), m -> decide(handler));
  }

  /**
   * Reflects over one handler method for {@link #needsLayoutModel(HttpServletRequest)}.
   *
   * @param handler the matched handler method
   * @return {@code true} unless the handler writes its body directly and reads no model attribute
   */
  static boolean decide(@NotNull HandlerMethod handler) {
    boolean readsModelAttribute =
        Arrays.stream(handler.getMethodParameters())
            .anyMatch(p -> p.hasParameterAnnotation(ModelAttribute.class));
    if (readsModelAttribute) {
      return true;
    }
    Class<?> returnType = handler.getReturnType().getParameterType();
    boolean writesBody =
        handler.hasMethodAnnotation(ResponseBody.class)
            || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), ResponseBody.class)
            || HttpEntity.class.isAssignableFrom(returnType)
            || ResponseBodyEmitter.class.isAssignableFrom(returnType)
            || StreamingResponseBody.class.isAssignableFrom(returnType);
    return !writesBody;
  }

  /**
   * The caller's layout context as the advices consume it.
   *
   * @param activeOrgUnitId the effective org-unit context the backend resolved, or {@code null} for
   *     an admin in all-org-units mode, a member with no home Staffel, or {@link #NONE}
   * @param orgUnits the org units the caller may pin; never {@code null}, possibly empty
   * @param capabilities the caller's capability flags; never {@code null}, all off in {@link #NONE}
   * @param unreadNotifications the caller's unread-notification count, {@code 0} in {@link #NONE}
   */
  public record LayoutContext(
      @Nullable UUID activeOrgUnitId,
      @NotNull List<OrgUnitMembershipOptionDto> orgUnits,
      @NotNull CapabilitiesResponse capabilities,
      long unreadNotifications) {

    /**
     * The fail-closed context: what an anonymous caller, a body-writing handler and a failed
     * backend read all receive. No org unit, nothing to pin, every capability off, no badge.
     */
    public static final LayoutContext NONE =
        new LayoutContext(null, List.of(), CapabilitiesResponse.NONE, 0L);
  }

  /**
   * Wire-shape mirror of the backend's {@code MeController.LayoutResponse}. Kept local, like the
   * advices' own mirrors, to avoid a frontend dependency on the backend module for one envelope.
   * The nested capabilities mirror carries only the three flags the layout reads; Jackson ignores
   * the backend's other five.
   *
   * @param activeOrgUnitId the effective org-unit context, or {@code null}
   * @param orgUnits the org units the caller may pin; {@code null} only on a malformed answer
   * @param capabilities the caller's capability flags; {@code null} only on a malformed answer
   * @param unreadNotifications the caller's unread-notification count
   */
  public record MeLayoutResponse(
      @Nullable UUID activeOrgUnitId,
      @Nullable List<OrgUnitMembershipOptionDto> orgUnits,
      @Nullable CapabilitiesResponse capabilities,
      long unreadNotifications) {}
}
