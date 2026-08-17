# ADR-0131 — `GlobalExceptionHandler` outranks Spring's problem-details advice

- **Status:** Accepted
- **Date:** 2026-08-17
- **Deciders:** @greluc
- **Related:** spec REQ-API-004 · REQ-FE-011

## Context

`application.yml` sets `spring.mvc.problemdetails.enabled: true`. That flag makes Spring Boot
register its own `ProblemDetailsExceptionHandler` — a `ResponseEntityExceptionHandler` subclass
annotated `@ControllerAdvice` and `@Order(0)` — guarded only by
`@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)`. Our `GlobalExceptionHandler`
deliberately does not extend that base class, so the condition holds and **both** advices are live.

`ExceptionHandlerExceptionResolver` consults advice beans in precedence order and takes the first
one declaring a handler for the thrown exception. An un-annotated `@ControllerAdvice` resolves to
`Ordered.LOWEST_PRECEDENCE`, so ours lost every contest. Six exception types are declared by both:
`MethodArgumentNotValidException`, `HttpMessageNotReadableException`,
`MethodArgumentTypeMismatchException`, `HttpRequestMethodNotSupportedException`,
`NoResourceFoundException`, `ErrorResponseException`.

For those, clients received Spring's bare `ProblemDetail`: no `code`, no `correlationId`, no
`fieldErrors`, and an untranslated English `detail` — `"Invalid request content."` for a validation
failure. That silently violated REQ-API-004 while every other error path honoured it.

The failure was found in production, not in review. A bank employee could not pay out a booking
request: the frontend logged
`code=VALIDATION_FAILED, correlationId=null, detail=Invalid request content., fieldErrors=[]` — and
the `code` there was not even the backend's, but `BackendServiceException.deriveCodeFromStatus(400)`
inventing one from the status. Because the frontend needs `fieldErrors` to render an inline message
at the offending field, it fell back to a generic "some fields are invalid" toast; and because
`handleValidationExceptions`' diagnostic WARN log lives in the advice that never ran, the backend log
for that request was **empty**. Neither the user nor the operator could tell which field was
rejected. Two hours of the diagnosis went into re-deriving from source what one log line should have
stated.

`GlobalExceptionHandlerTest` was green throughout: it invokes the handler methods directly and is
structurally incapable of observing which advice bean Spring MVC would pick.

## Decision

We will annotate `GlobalExceptionHandler` with `@Order(Ordered.HIGHEST_PRECEDENCE)` and treat that
annotation as load-bearing, documented as such in its Javadoc.

We keep `spring.mvc.problemdetails.enabled: true` and keep `GlobalExceptionHandler` a plain class
rather than a `ResponseEntityExceptionHandler` subclass.

We will guard the ordering with `GlobalExceptionHandlerAdviceOrderTest`, which registers a stand-in
for Spring's package-private advice at the same `@Order(0)`, drives Spring's own discovery
(`ControllerAdviceBean.findAnnotatedBeans`, which sorts by precedence) and reproduces the
first-advice-wins lookup — the resolution the direct-invocation tests cannot see.

## Consequences

Every error response now carries the REQ-API-004 contract uniformly, including the six previously
shadowed types, and validation failures regain both their `fieldErrors` (so the frontend can mark the
offending field inline) and their WARN log line (so a production 400 is diagnosable without asking
the user to reproduce it).

The cost we accept: our advice now also wins for exception types Spring's handler arguably models
better, and the responsibility for keeping those six mappings correct is fully ours. Adding a
`@ExceptionHandler` for a Spring-native type is now a decision with contract consequences rather than
dead code, and `springNativeOverlap()` in the guard test must grow when a new overlap appears.

Any future advice that legitimately needs to pre-empt `GlobalExceptionHandler` must state its order
explicitly; relying on declaration order remains unsupported.

A broader lesson this ADR records deliberately: a handler asserted only by direct invocation is
asserted only as a function, not as a wired component. Where framework wiring decides whether code
runs at all, the test has to exercise the wiring.

## Alternatives considered

- **`GlobalExceptionHandler extends ResponseEntityExceptionHandler`** — the `@ConditionalOnMissingBean`
  would then suppress Spring's bean entirely, removing the contest at its root. Rejected as the
  larger, riskier change: it inherits ~20 handler methods and a different response-building path
  (`handleExceptionInternal`), so every existing mapping would have to be re-verified against
  inherited behaviour. Worth revisiting deliberately, not under an incident.
- **`spring.mvc.problemdetails.enabled: false`** — also removes the competing advice, but gives up
  Spring's problem-details defaults as the safety net for exception types we do not handle, which
  would then fall through to Boot's plain-JSON error path. Rejected: it trades a precedence bug for a
  coverage gap.
- **Leave it and have the frontend infer the field from `detail`** — rejected outright. It would
  parse an untranslated framework string, still produce no server-side log, and encode the defect as
  a contract.

