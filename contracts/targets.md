# Registered systems (F-015 / roadmap 3a)

Users register multiple systems through Targets. Each record selects one API/environment and one Kubernetes Deployment/container, plus references to credentials/monitoring managed on the backend. Credentials themselves are not target metadata and must never appear in this API. Saving does not contact any endpoint or prove connectivity. Read-only diagnostics and live status are F-016; load execution remains gated by later stages.

All routes require an OPERATOR session. POST/PUT require the CSRF bootstrap header defined in [access](access.md). JSON errors use code/message/UTC timestamp without raw exceptions or submitted values.

| Endpoint | Semantics |
| --- | --- |
| GET `/api/targets?offset=0&limit=20&includeArchived=false` | `{ "items": [], "total": 0, "offset": 0, "limit": 20 }`; stable created time/ID order; limit 1–100, nonnegative offset |
| POST `/api/targets` | validated input below; 201 entity and Location `/api/targets/{id}` |
| GET `/api/targets/{id}` | 200 entity, including archived; unknown/malformed ID 404/400 |
| PUT `/api/targets/{id}` | input plus current revision; 200 new revision; stale revision or archived target 409 |
| POST `/api/targets/{id}/archive` | `{ "revision": 1 }`; 200 archived entity; repeated archive is idempotent; stale active revision 409 |

Input example:

```json
{
  "name": "Checkout staging",
  "baseUrl": "https://checkout.example.invalid",
  "environment": "STAGING",
  "namespace": "checkout",
  "deployment": "checkout-api",
  "container": "api",
  "credentialRef": "checkout-readonly",
  "monitoringRef": "lab-prometheus"
}
```

Name: 1–100 characters, unique among active records ignoring case. Environment: DEVELOPMENT, STAGING or PRODUCTION (metadata only; no production load authorization). API URL: absolute HTTP(S), maximum 2048 characters, valid host/port, no credentials/query/fragment. Namespace/container: Kubernetes DNS labels (1–63). Deployment: DNS subdomain (1–253). References: optional backend identifiers, 1–100 ASCII letters/digits/dot/underscore/hyphen; never passwords, tokens, paths or kubeconfig. No external request occurs here; endpoint/address allowlists are enforced by the later connection adapter.

Entity adds UUID `id`, integer `revision` starting at 1, UTC `createdAt`, `updatedAt`, nullable `archivedAt`. Edits increment revision and cannot rewrite future experiment snapshots. Archive retains identity and history; no deletion endpoint. Name reuse is allowed after archive. Updates and audit writes commit atomically; database/audit failure cannot report success. Audit contains configured actor, target ID, action and timestamp without sensitive connection payloads.

Errors: 400 `VALIDATION_FAILED`, 404 `TARGET_NOT_FOUND`, 409 `TARGET_CONFLICT`, 503 `TARGETS_UNAVAILABLE`. Validation messages identify fields without echoing submitted data. Browser list supports loading/empty/error/retry/pagination/archive filtering; detail uses static `/targets/detail/?id=UUID`, create/edit forms and archive confirmation. Invalid IDs, lost updates and archived state stay visible. Connection status initially reads “Not checked”; never label a saved record connected.
