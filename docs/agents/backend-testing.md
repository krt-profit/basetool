# Backend test fixtures and Spring test contexts

Read this before writing a backend unit test that wires real collaborators or a
`@SpringBootTest`.

## Test fixtures — wire a real collaborator through the constructor, never by reflection

When a unit test needs a **real** collaborator inside the service under test — the usual case here:
a service was split (`#921`, `#922`, `#14`) and the fixture wants the real sub-service rather than a
mock that would only assert delegation — build the service with `new X(...)` in `@BeforeEach`.

**Do not** create it with `@InjectMocks` and then patch the field with
`ReflectionTestUtils.setField` or `Field.set`. Mockito does not inject one `@InjectMocks` target into
another, which is why that pattern existed; but the target fields are `private final` (Lombok
`@RequiredArgsConstructor`), and reflective mutation of a final field is exactly what **JEP 500**
(JDK 26, "Prepare to Make Final Mean Final") warns about under
`--illegal-final-field-mutation=warn` and a later release will refuse outright. Thirty-one such call
sites across sixteen files were converted for that reason; do not reintroduce one.

Mechanics:

- `@Mock` and `@InjectMocks` fields are all initialised **before** `@BeforeEach`, so an
  `@InjectMocks` sub-service can be passed straight into the explicit constructor call. Build the
  graph bottom-up when a sub-service itself needs one.
- Argument order is the `@RequiredArgsConstructor` **field-declaration order** of the service.
- Where `@InjectMocks` was passing `null` for a dependency the fixture never reaches, pass `null`
  explicitly instead of inventing a mock: same wiring as before, now visible, and no risk of a mock
  changing a null-guarded path.
- A constructor change now breaks the fixture at **compile time** rather than leaving a silent
  `null` — that is the point, not a regression.

Nothing enforces this today: `--illegal-final-field-mutation` does not exist on JDK 25, so it cannot
be passed to the test JVM yet and an unexercisable flag is not worth adding. **When the toolchain
moves to JDK 26 or later, arm it** — `jvmArgs("--illegal-final-field-mutation=deny")` on the `Test`
tasks turns this rule into a gate, and doing it as part of that bump is how the conversion above
stays done.

## Spring test contexts — reuse one, do not open one (REQ-OPS-041)

Every distinct `@MockitoBean` set, mock field name or inlined property boots another full
application context, and `TestContextBudgetTest` fails the build above the budget (36) with the full
grouping. Before declaring a mock or property on a `@SpringBootTest`:

- Use a plain `@SpringBootTest` when the test does not need a mock. `jwt()` and `@WithMockUser`
  never call the `JwtDecoder`, so do not mock it.
- Use `@LeafServiceMockTest` when the test mocks a leaf service in its set, and `@Autowired` the
  mock. A bean joins that set only when no security bean reaches it — `LeafServiceMockSecurityTest`
  enforces it; a test that must mock a security bean keeps its own context.
- Mock a bean under the field name the other classes already use; the field name is part of the key.
