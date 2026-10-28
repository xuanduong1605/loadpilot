# System connections and live inspection (F-016 / Stage 4)

Each registered system owns one connection configuration. Users enter its Kubernetes HTTPS endpoint, optional PEM certificate authority, scoped bearer token and optional Prometheus endpoint/CA/token. No kubeconfig parsing, exec plugins, client shell, cluster writes or load generation. Use a namespace-scoped read-only ServiceAccount; never an administrator kubeconfig. The adapter issues only fixed GET requests. Registration and connection saving perform no target HTTP requests.

## Access and storage

OPERATOR session and CSRF are required for writes. Credential forms operate only on TLS or loopback development/tunnels. `CONNECTION_MASTER_KEY` is a backend-only Base64 32-byte AES key. Tokens use AES-256-GCM with independent random nonces and target/field authenticated context; plaintext, encryption key, cookies and upstream response bodies are never returned, logged or audited. Key absence disables credential writes/reads with503 but leaves metadata/history available. Preserve this key separately from DB backups; replacement requires re-entering credentials (no silent rotation).

`CONNECTION_ALLOWED_ORIGINS` is a comma-separated list of exact HTTP(S) origins configured by the platform operator, including explicit ports where needed. Kubernetes/Prometheus require HTTPS. API probes may use allowlisted HTTP. `CONNECTION_ALLOWED_ADDRESSES` is a comma-separated list of literal IP addresses for those private services. Empty lists deny outbound calls. At every connection attempt resolve DNS, require every result in the IP allowlist, reject unspecified/multicast/link-local/metadata addresses, and pin the validated resolution to the HTTP client. No redirects, environment proxy, insecure TLS switch or credential forwarding to other origins. Explicitly allow loopback only for isolated development fixtures.

## API

| Route | Behavior |
| --- | --- |
| GET `/api/integrations/config` | encryption configured flag and allowed origins; no key or token |
| GET `/api/integrations/targets/{id}/connection` | configuration metadata, revision, endpoints, CAs and `hasKubernetesToken`/`hasPrometheusToken`; never token values; unconfigured returns revision0 |
| PUT same route | `revision`, `targetRevision`, `kubernetesUrl`, `kubernetesCa`, `kubernetesToken`, `prometheusUrl`, `prometheusCa`, `prometheusToken`, `readOnlyAcknowledged:true`; blank token retains an existing token, initial Kubernetes token required; clearing monitoring URL removes its credential; atomic configuration/reference/audit update; stale revision409 |
| GET `/api/integrations/targets/{id}/status` | fresh read-only snapshot with target/config revisions, collection time and independent API/Kubernetes/resource-metrics/Prometheus outcomes; archived systems409; unconfigured components explicitly NOT_CONFIGURED |

Errors use code/message/UTC timestamp, with no raw exception or submitted value. Bad fields400, denied origin/address403, stale metadata/configuration409, missing target404, unavailable storage/key503. Upstream401/403/offline/TLS/timeout/oversize/invalid-response conditions are component outcomes, never platform-session401.

Requests use bounded concurrency (four inspections), finite connect/read/call timeouts, bounded response bodies (2MiB), capped namespaced lists (200) and no automatic HTTP retries. Credential uploads are limited to32KiB before JSON deserialization, including unknown-length streams. Truncated lists produce incomplete scope, never a complete resource aggregate. Saved configuration has independent optimistic revision and updates the target's credential/monitoring references atomically; target revision protects concurrent scope edits. Changing an authenticated origin requires a replacement token; a blank token cannot forward a saved credential to another origin. Managed references cannot be reassigned through metadata edits.

## Live status and statistics

Kubernetes Java Client26.0.1 is pinned for this slice; official [version compatibility](https://github.com/kubernetes-client/java/wiki/2.-Versioning-and-Compatibility) remains the deployment compatibility reference. Inspect the chosen Deployment, its owned ReplicaSets, and their owned Pods, restricted to the registered namespace/container. Report desired/ready/available/updated replicas, observed generation, pod phase/readiness/restarts, and selected-container CPU/memory requests/limits separately from consumption. Matching labels alone cannot establish ownership.

Consumption comes from the Kubernetes `metrics.k8s.io/v1beta1` PodMetrics API. Each value retains source timestamp/window, selected container, CPU millicores and memory bytes. Missing, negative, future, stale (older than120s), wrong scope or malformed samples are unavailable; do not zero-fill or mix incompatible windows into an aggregate. These are current resource observations, not SLO evidence or throughput. See the official [resource metrics pipeline](https://kubernetes.io/docs/tasks/debug/debug-cluster/resource-metrics-pipeline/).

API probe checks the registered base URL without credentials or redirects and reports HTTP availability only. Prometheus probe checks `/-/ready` and a bounded `up` instant query for namespace-scoped health; application throughput/latency/error mappings remain Stage9 and cannot be invented here. All views show actual source/time/units and distinguish current data from previously collected/stale data. Auto-refresh is opt-in, visible-tab only, avoids overlapping requests and stops on error. Manual inspections send `X-Loadpilot-Interactive:true`; background polls do not update the server's independent operator-idle clock. An expired session is revoked even while status polling continues. No lab outage changes stored registration or invents SLO failure.

Verification: isolated TLS Kubernetes/Prometheus-shaped fixture through the real Java client, encryption/redaction/revision/persistence tests, owner/metrics validity cases, destination/DNS/redirect/TLS/timeout/body-bound failures and browser setup/status/reload/multiple-system flows. These do not substitute for actual deployed systemd/lab/RBAC/network-policy/first-release acceptance; keep those gates open until directly demonstrated.
