/**
 * Shared test helpers for frontend page tests.
 *
 * Eliminates the duplicated `json()`, fetch-mock setup, and assertion patterns
 * that were copy-pasted across 22+ test files.
 */

// ---------------------------------------------------------------------------
// Response builder
// ---------------------------------------------------------------------------

/** Build a minimal JSON Response, matching the pattern used in 22+ test files. */
export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// ---------------------------------------------------------------------------
// Fetch mock helpers
// ---------------------------------------------------------------------------

type FetchFn = typeof fetch;

/**
 * Stub `window.fetch` with a mock implementation that delegates to `handler`.
 * Automatically unstubs in afterEach (call `resetFetchMock()` in afterEach).
 *
 * @example
 * beforeEach(() => {
 *   resetFetchMock();
 *   mockFetch((url) => {
 *     if (url === "/api/admin/users") return json({ users: [] });
 *     return json({ error: "not found" }, 404);
 *   });
 * });
 */
export function mockFetch(handler: (url: string, init?: RequestInit) => Response): void {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((url: string, init?: RequestInit) => handler(url, init)),
  );
}

/** Reset fetch mock and restore globals. Call in afterEach. */
export function resetFetchMock(): void {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
}

/**
 * Wait for fetch to be called with a matching method+URL, then return the parsed body.
 *
 * @example
 * const body = await expectFetchPost("/api/admin/users", { username: "alice" });
 * expect(body).toMatchObject({ id: "1" });
 */
export async function expectFetchPost(
  urlPattern: string | RegExp,
  bodyMatcher?: Record<string, unknown>,
): Promise<unknown> {
  const fetchMock = vi.mocked(fetch);
  await vi.waitFor(() => {
    const call = fetchMock.mock.calls.find(
      ([u, init]) => matchUrl(u, urlPattern) && init?.method === "POST",
    );
    expect(call).toBeDefined();
  });
  const [, init] = fetchMock.mock.calls.find(
    ([u, o]) => matchUrl(u, urlPattern) && o?.method === "POST",
  )!;
  const body = JSON.parse(String(init!.body));
  if (bodyMatcher) {
    expect(body).toMatchObject(bodyMatcher);
  }
  return body;
}

/**
 * Wait for fetch to be called with a matching method+URL, then return the parsed body.
 */
export async function expectFetchPut(
  urlPattern: string | RegExp,
  bodyMatcher?: Record<string, unknown>,
): Promise<unknown> {
  const fetchMock = vi.mocked(fetch);
  await vi.waitFor(() => {
    const call = fetchMock.mock.calls.find(
      ([u, init]) => matchUrl(u, urlPattern) && init?.method === "PUT",
    );
    expect(call).toBeDefined();
  });
  const [, init] = fetchMock.mock.calls.find(
    ([u, o]) => matchUrl(u, urlPattern) && o?.method === "PUT",
  )!;
  const body = JSON.parse(String(init!.body));
  if (bodyMatcher) {
    expect(body).toMatchObject(bodyMatcher);
  }
  return body;
}

/**
 * Wait for fetch to be called with a matching DELETE.
 */
export async function expectFetchDelete(urlPattern: string | RegExp): Promise<void> {
  const fetchMock = vi.mocked(fetch);
  await vi.waitFor(() => {
    const call = fetchMock.mock.calls.find(
      ([u, init]) => matchUrl(u, urlPattern) && init?.method === "DELETE",
    );
    expect(call).toBeDefined();
  });
}

/**
 * Assert that fetch was NOT called with a matching method+URL.
 */
export function expectFetchNotCalled(
  method: string,
  urlPattern: string | RegExp,
): void {
  const fetchMock = vi.mocked(fetch);
  const call = fetchMock.mock.calls.find(
    ([u, init]) => matchUrl(u, urlPattern) && init?.method === method,
  );
  expect(call).toBeUndefined();
}

function matchUrl(actual: unknown, pattern: string | RegExp): boolean {
  if (typeof actual !== "string") return false;
  if (typeof pattern === "string") return actual === pattern;
  return pattern.test(actual);
}
