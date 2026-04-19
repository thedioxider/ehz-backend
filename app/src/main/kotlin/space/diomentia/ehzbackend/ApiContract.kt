package space.diomentia.ehzbackend

/**
 * Machine-checkable compatibility ticker for the API surface (everything under
 * the `/api/` prefix).
 *
 * Bumped only when the wire contract changes in a way a pre-existing client
 * couldn't tolerate — renamed fields, removed endpoints, changed status-code
 * semantics. Additive changes (new optional field, new endpoint) do NOT bump
 * this number.
 *
 * Surfaced in three places so a client can reconcile without a round-trip:
 *   - the discovery sidecar advertises it,
 *   - `GET /api/info` includes it in the JSON body,
 *   - every API response carries [HEADER_NAME] via the interceptor.
 */
object ApiContract {
    const val VERSION: Int = 1
    const val HEADER_NAME: String = "X-Contract-Version"
}
