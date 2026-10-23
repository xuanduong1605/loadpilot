# Loadpilot

Adaptive load testing and Kubernetes resource optimization with AI assistance.

Loadpilot is a thesis project exploring how to find an application's observed load capacity and compare CPU/replica configurations using reproducible performance evidence. The intended workflow combines reviewed k6 scenarios, bounded experiments, deterministic SLO evaluation and AI-assisted analysis.

## Project status

**Early development.** The frontend provides a workspace and sign-in preview. The backend provides health and a protected status endpoint. Browser authentication, target management and experiment execution are not connected yet.

| Area | Status |
| --- | --- |
| Workspace, navigation and section previews | Implemented |
| Shared form controls and sign-in preview | Implemented; no credentials sent or session created |
| Backend health, Basic authentication and Flyway baseline | Scaffold implemented; no product tables |
| Single manual build/test/package/deploy workflow | Configured; GitHub/VPS runtime verification pending |
| Sessions, targets, script versions and approval | Planned |
| k6 execution, measurements, adaptive search and optimization | Planned |
| AI drafting/analysis, reports and distributed generators | Planned |

Frontend typecheck and static export have passed. Full backend, hosting and end-to-end acceptance remain in progress.

## Intended capabilities

- Define targets, performance goals, load bounds and SLOs.
- Create, version, validate and approve manual or AI-drafted k6 scripts.
- Run bounded Kubernetes Jobs and adapt load to find an observed passing/failing interval.
- Compare CPU and replica candidates at the same required load.
- Stop interrupted work, reconcile after restart and restore original controlled workload fields.
- Explain measurements with AI, compare historical experiments and export reproducible reports.

AI proposes drafts and explanations; deterministic rules own verdicts and execution bounds. Missing required evidence produces **Insufficient data**. Optimization recommendations represent the best tested candidate within the declared budget; original workload settings must be restored after experiments.

## Technology

| Layer | Stack |
| --- | --- |
| Frontend | Next.js 16, React 19, TypeScript, Node.js 24; static export |
| Backend | Java 21, Spring Boot 3.5, Spring Security |
| Persistence | PostgreSQL 17, Flyway |
| Native hosting target | Nginx, systemd, prebuilt JAR and static files |
| Planned integrations | Grafana k6, Kubernetes Java Client, Prometheus/Grafana, Micrometer, Spring AI |

The core application is developed natively. Kubernetes, generators, monitoring and the eventual example target run in a separate laboratory. OpenTelemetry traces depend on target instrumentation.

## Repository layout

```text
apps/
  web/          Next.js frontend
  platform/     Spring Boot backend and database migrations
infra/          Hosting configuration
scripts/        Deployment helper
.github/        CI and deployment workflows
.env.example    Runtime configuration example
```

## Run locally

Run the commands below from the repository root. Examples use PowerShell; use `npm` instead of `npm.cmd` on Linux/macOS.

### Frontend preview

Requires Node.js 24 and npm. The preview can run without the backend.

```powershell
git clone https://github.com/xuanduong1605/thesis.git
cd thesis
npm.cmd --prefix apps/web ci
npm.cmd --prefix apps/web run dev
```

Open <http://localhost:3000>. The `/login/` form is a preview: use sample values. It does not authenticate or unlock product actions.

### Backend foundation

Requires JDK 21, Maven 3.9.11+ and a local PostgreSQL 17 server. A Maven Wrapper is not included yet.

Create an application role and database in `psql` as a database administrator. Assign the password interactively:

```sql
CREATE ROLE loadpilot LOGIN;
\password loadpilot
CREATE DATABASE loadpilot OWNER loadpilot;
```

Copy [.env.example](.env.example) to a local `.env` if one does not exist, then fill in its database URL, username and password. Local setup uses only the `loadpilot` application database. `.env` is ignored by Git.

Set the corresponding backend environment in a separate terminal, replacing the placeholder values with those from your local file:

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/loadpilot'
$env:POSTGRES_USER = 'loadpilot'
$env:POSTGRES_PASSWORD = '<database-password>'
$env:PLATFORM_USER = 'admin'
$env:PLATFORM_PASSWORD = '<password-at-least-20-characters>'
mvn -f apps/platform/pom.xml spring-boot:run
```

The backend defaults to port 8080. `GET /actuator/health` is public; `GET /api/status` requires the configured Basic credentials. The frontend development server proxies `/api/` to the local backend. Browser session login is future work.

Spring Boot does not automatically load the root `.env` or `.env.example`; use the environment variables above or configure them in your IDE. Keep actual credentials out of Git. The native systemd service uses a separate `/etc/loadpilot/platform.env` file.

## Verification

Frontend checks:

```powershell
npm.cmd --prefix apps/web run typecheck
npm.cmd --prefix apps/web run build
```

The production build exports to `apps/web/out/`. It requires static hosting; no production Node server is needed. A historical Windows export review reported segment-prefetch URLs returning 404 while navigation still worked. That issue and actual Linux/Nginx routing remain under verification.

Backend integration-test setup and native deployment validation remain pending. An additional database is not required to run the application. No frontend lint or automated browser-test command is provided yet.

## Manual build and deployment

There is one [Build and deploy workflow](.github/workflows/ci.yml). It runs only when you trigger it, then automatically checks, tests, packages and deploys both apps in order. Pushes and pull requests do not trigger it.

1. Commit/push the application, scripts, `infra/` templates and workflow changes to `main`. Local harness/thesis documents stay ignored.
2. Complete the one-time VPS and GitHub configuration below. Review new Flyway migrations for compatibility with the previous application release before dispatching.
3. Open **Actions → Build and deploy → Run workflow**, choose `main`, and run it. There are no inputs or second workflow to trigger; other branches are rejected.
4. The build job runs its steps directly in the workflow: backend PostgreSQL/Flyway/auth tests, frontend typecheck/export, shell/package checks and a combined JAR/Nginx smoke test. Its PostgreSQL instance exists only on the disposable GitHub runner; no test database is needed on your PC or VPS.
5. Only after the build job succeeds, the deployment job downloads that run's **vps-release-ATTEMPT** artifact, verifies its commit/checksums and automatically deploys it. Failed, canceled or incomplete builds do not proceed to deployment. Artifacts expire after 30 days; retain complete releases you need for rollback.

[deploy-vps.sh](scripts/deploy-vps.sh) verifies the bundle, locks deployment, takes a private database dump, switches the matching JAR/static release and checks service/HTTPS/API identity. Failure attempts application rollback. It never builds on the VPS or restores a database automatically. The previous application must remain compatible with any new Flyway migrations.

The workflow serializes complete runs against the VPS. Build jobs cannot access the production environment's SSH secrets; only the deployment job uses them. Rerunning failed deployment jobs uses the original successful build artifact, while rerunning all jobs creates a new artifact. Current application features do not include experiment execution; shared maintenance/start locking must be implemented before using deployment alongside active experiments.

## VPS setup

These are **manual administrator steps**, targeting Ubuntu 24.04 with Java 21, PostgreSQL 17 and Nginx. Replace the example host/domain/passwords before use. The VPS needs no Node.js, npm, Maven or Docker for the core platform. Copy the repository's `infra/` directory to the VPS first; run the template installation commands from its parent directory.

### 1. Install host packages and create accounts

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jre-headless nginx certbot python3-certbot-nginx curl ca-certificates jq tar util-linux
```

Install PostgreSQL 17 server/client using the [official Ubuntu repository instructions](https://www.postgresql.org/download/linux/ubuntu/), then verify `pg_dump --version` reports major 17. Keep PostgreSQL on loopback. Create only the application database:

```bash
sudo -u postgres psql
```

Inside `psql`:

```sql
CREATE ROLE loadpilot LOGIN;
\password loadpilot
CREATE DATABASE loadpilot OWNER loadpilot;
\q
```

Create a service account and a separate deploy account:

```bash
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin loadpilot
sudo adduser --disabled-password --gecos '' deploy
sudo install -d -o deploy -g deploy -m 0755 /opt/loadpilot /opt/loadpilot/releases /opt/loadpilot/incoming
sudo install -d -o deploy -g deploy -m 0700 /opt/loadpilot/backups
sudo install -d -o root -g root -m 0755 /etc/loadpilot
```

Authorize a dedicated SSH public key for `deploy`; its private key will be the GitHub secret below. Restrict that key's forwarding/PTY access using the `restrict` authorized-key option. Allow the chosen SSH port and HTTP/HTTPS through the provider/host firewall; keep backend port 8080 and PostgreSQL port 5432 private.

### 2. Install runtime and backup configuration

```bash
sudo install -o root -g root -m 0600 /dev/null /etc/loadpilot/platform.env
sudoedit /etc/loadpilot/platform.env
sudo install -o root -g root -m 0644 infra/systemd/loadpilot.service /etc/systemd/system/loadpilot.service
sudo systemd-analyze verify /etc/systemd/system/loadpilot.service
sudo systemctl daemon-reload
sudo systemctl enable loadpilot.service
```

Set these values in `platform.env`, replacing both passwords:

```dotenv
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/loadpilot
POSTGRES_USER=loadpilot
POSTGRES_PASSWORD=REPLACE_DATABASE_PASSWORD
PLATFORM_USER=admin
PLATFORM_PASSWORD=REPLACE_WITH_RANDOM_PASSWORD_AT_LEAST_20_CHARACTERS
```

Use an operator password of at least 20 characters. Do not start the service until a release exists. The unit's 384 MiB heap and 640 MiB memory limit are initial settings to measure on your host.

Install [loadpilot-deploy.sudoers](infra/systemd/loadpilot-deploy.sudoers) using `sudo visudo -f /etc/sudoers.d/loadpilot-deploy`; copy its rule, adjust the username if needed, and keep root ownership/mode 0440. The rule permits only this service's start/stop actions.

As `deploy`, create `~/.pg_service.conf` with:

```ini
[loadpilot-backup]
host=127.0.0.1
port=5432
dbname=loadpilot
user=loadpilot
sslmode=prefer
```

Create `~/.pgpass` with this line, replacing the password (escape colons and backslashes within it):

```text
127.0.0.1:5432:loadpilot:loadpilot:REPLACE_DATABASE_PASSWORD
```

Run `chmod 600 ~/.pg_service.conf ~/.pgpass`. Verify the backup connection without putting the password on a command line:

```bash
PGSERVICE=loadpilot-backup pg_dump --no-password --format=custom --file=/opt/loadpilot/backups/setup-check.dump
pg_restore --list /opt/loadpilot/backups/setup-check.dump
```

Arrange off-host backup retention and a restore drill separately; listing an archive does not prove restoration.

### 3. Configure the domain and HTTPS

Point your domain's DNS at the VPS. Install [loadpilot.conf](infra/nginx/loadpilot.conf), replace `loadpilot.example.com` with your domain, and enable the site. Disable Ubuntu's default site if it conflicts with this site's `default_server`:

```bash
sudo install -o root -g root -m 0644 infra/nginx/loadpilot.conf /etc/nginx/sites-available/loadpilot
sudoedit /etc/nginx/sites-available/loadpilot
sudo ln -s /etc/nginx/sites-available/loadpilot /etc/nginx/sites-enabled/loadpilot
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d loadpilot.example.com
```

Replace that example domain before running Certbot. The application/static routes may return errors until the first release; certificate provisioning can happen first. Deployment checks HTTPS locally using your domain/certificate, then verifies the public URL from GitHub. A localhost-only HTTP check is insufficient.

### 4. Configure GitHub and run the first deployment

Create a GitHub environment named **production**, restricted to `main`. Leave required-reviewer approval disabled if you want deployment to continue automatically after the tests pass. Add the following settings.

| Type | Name | Value to supply |
| --- | --- | --- |
| Variable | `VPS_HOST` | VPS hostname or IPv4 address |
| Variable | `VPS_USER` | `deploy`, or your separate deploy account |
| Variable | `VPS_SSH_PORT` | SSH port; defaults to `22` |
| Variable | `VPS_PUBLIC_URL` | `https://your.domain`, without trailing slash/path/port |
| Secret | `VPS_SSH_KEY` | Dedicated deploy account's private SSH key; usable non-interactively |
| Secret | `VPS_KNOWN_HOSTS` | Verified SSH host-key entry, including `[host]:port` for a nonstandard port |

Verify the SSH host-key fingerprint through your VPS console/provider before saving it. Do not blindly trust a fresh `ssh-keyscan` result. Enable GitHub Actions for the repository and allow the pinned official actions used by these workflows. No registry credentials or VPS database passwords belong in GitHub for this deployment path.

Run **Build and deploy** once as described above. Dispatching authorizes deployment and schema-compatible application rollback; review migrations before clicking Run workflow. Runtime secrets and root-owned host configuration are preserved across deployments; uploaded template changes are not installed automatically. A failed first release has no previous application to restore. If the final public probe fails after local activation succeeds, the workflow reports failure but leaves that locally healthy release active for diagnosis. Inspect `sudo systemctl status loadpilot.service`, `sudo journalctl -u loadpilot.service`, `sudo nginx -t`, and the `current` symlink before retrying. Never restore an old database dump automatically over newer application data.

## Roadmap

1. Complete frontend/static-hosting review and backend foundation verification.
2. Implement real sessions, login/logout, permissions and audit.
3. Add target connections, immutable scripts, validation/approval and experiment settings.
4. Deliver bounded k6 runs, stop/recovery, reliable measurements and deterministic SLOs.
5. Add adaptive capacity testing and safe CPU/replica optimization with verified restoration.
6. Integrate AI drafts/explanations, history, reports and distributed generators.
7. Verify the complete platform before building the six-service example and conducting thesis evaluation.

Experiments are intended for explicitly authorized targets and finite resource/load bounds. Production promotion, memory/node tuning and unrestricted cluster changes are outside the initial scope.
