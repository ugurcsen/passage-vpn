# Contributing to PassageVPN

Thanks for your interest in contributing! This document explains how to get
started, the conventions to follow, and the pull request process.

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
By participating, you are expected to uphold this code. Please report
unacceptable behavior via [GitHub Issues](https://github.com/ugurcsen/passage-vpn/issues).

## Ways to Contribute

| Type | Where |
|---|---|
| Bug report | [GitHub Issues](https://github.com/ugurcsen/passage-vpn/issues) — use the bug template |
| Feature request | [GitHub Issues](https://github.com/ugurcsen/passage-vpn/issues) — use the feature request template |
| Code contribution | Fork → branch → PR |
| Documentation | Same PR flow; edits to `docs/`, `README.md`, inline comments |
| Testing | Run `make test` and report failures, or add missing tests |

## Reporting Security Vulnerabilities

**Do not** open a public issue for security vulnerabilities. Instead, please
report them responsibly by opening a
[private security advisory](https://github.com/ugurcsen/passage-vpn/security/advisories/new)
on GitHub. We will respond promptly and coordinate a fix before any public
disclosure.

## Development Setup

### Prerequisites

- **Java 25** (Temurin recommended)
- **Node.js 24+** and npm
- **Docker & Docker Compose** (for full-stack)
- **Git**

### Quick start (Docker)

```bash
git clone https://github.com/ugurcsen/passage-vpn.git
cd passage-vpn
cp .env.example .env        # edit secrets (PASSAGE_JWT_SECRET, etc.)
make up                     # build + start all services
make logs                   # follow logs
# Open http://localhost:8080 and complete the setup wizard
```

### Local development (no Docker)

Backend:

```bash
cp .env.example .env        # edit secrets
cd backend
./gradlew bootRun           # runs on :8080
```

Frontend (separate terminal):

```bash
cd frontend
npm install
npm run dev                 # Vite dev server on :5173 (proxies /api + /ws)
```

### Verify your setup

```bash
make test                   # backend + frontend tests — must be green
```

## Project Structure

```
passage-vpn/
├── backend/               # Java 25 / Spring Boot 3.5 (Gradle Kotlin DSL)
│   └── src/main/java/com/passagevpn/
│       ├── api/           # REST controllers + DTOs (never leak entities)
│       ├── auth/          # JWT, MFA, password auth
│       ├── security/      # RBAC, filters
│       ├── config/        # Spring config classes
│       └── ...            # domain modules (user, pki, access, monitor, etc.)
├── frontend/              # React 18 + TypeScript + MUI v6 + Vite
│   └── src/features/      # feature-folder structure
├── openvpn/               # OpenVPN container image + scripts
├── docs/                  # architecture, API reference, access rules
└── Makefile               # primary dev/ops interface
```

## Making Changes

### 1. Create a branch

Branch off from `main`:

```bash
git checkout -b feature/my-feature main
```

Use descriptive branch names: `feature/`, `fix/`, `docs/`, `chore/`.

### 2. Commit messages

Follow [Conventional Commits](https://www.conventionalcommits.org/) style:

```
<type>(<scope>): <short summary>
```

Examples:

- `feat(portal): add profile QR download`
- `fix(mfa): reject expired TOTP challenge tokens`
- `docs(api): regenerate endpoint reference`
- `chore(ci): add docker build job`

**Rules:**
- Imperative mood ("add feature", not "added feature")
- English only
- Subject line max 72 characters
- Body wraps at 80 characters (optional, for complex changes)

### 3. Code style

#### Backend (Java)

- **Formatter**: Spotless with Google Java Format — run `./gradlew spotlessApply`
- **No native SQL**: Use JPQL/Criteria only (SQLite-specific SQL is forbidden;
  keeps PostgreSQL migration path clean)
- **DTO boundary**: Controllers expose DTOs from `api` package, never JPA entities
- **Validation**: Use `jakarta.validation` annotations on DTOs
- **Error handling**: Throw `ApiException` → `@RestControllerAdvice` maps to `ApiError`
- **New endpoints**: Add `@Tag` and `@Operation` annotations for Swagger docs

#### Frontend (TypeScript/React)

- **Formatter**: ESLint — run `npm run lint`
- **Feature folders**: Components live under `src/features/<feature>/`
- **State management**: Server state via TanStack Query; local state via React
- **Forms**: React Hook Form + Zod validation
- **UI**: MUI v6 components; dark mode is the default theme
- **User-facing strings**: English only

### 4. Testing requirements

| Layer | Framework | Run |
|---|---|---|
| Backend unit + integration | JUnit 5 + Mockito + Spring Boot Test | `cd backend && ./gradlew test` |
| Frontend component + hook | Vitest + React Testing Library | `cd frontend && npm run test` |
| Full suite | — | `make test` |

**Mandatory before PR:**
- All existing tests pass (`make test` green)
- New backend logic has unit tests
- New frontend components/hooks have tests
- Backend coverage ≥ 80% instruction (enforced by JaCoCo)
- Frontend coverage ≥ 90% statements / 80% branches / 90% functions

### 5. Documentation

If your change affects user-facing behavior:
- Update `docs/api.md` (`make api-docs` from a running backend)
- Update `docs/configuration.md` for new environment variables
- Update `README.md` if the quick start or feature list changes

## Pull Request Process

1. **Open an issue first** (for non-trivial changes) to discuss the approach.
2. **Fork** the repository and create your branch from `main`.
3. **Make your changes** following the conventions above.
4. **Run quality checks** before pushing:

   ```bash
   make lint                 # backend spotless + frontend eslint
   make test                 # backend + frontend tests
   ```

5. **Push** your branch and open a pull request against `main`.
6. **Fill in the PR template** — link the related issue, describe what changed
   and why.
7. **Respond to review feedback** — maintainers may request changes before merge.

### PR Checklist

The PR template will include this checklist (auto-verified where possible):

- [ ] `make test` is green
- [ ] New logic has unit tests (backend + frontend where applicable)
- [ ] Code passes `make lint` (Spotless + ESLint)
- [ ] Commit messages follow Conventional Commits style
- [ ] All user-facing strings and comments are in English
- [ ] Documentation updated (if applicable)
- [ ] No secrets, credentials, or `.env` values committed

## Issue Guidelines

### Bug reports

Include:
- Steps to reproduce
- Expected vs. actual behavior
- Environment (OS, Docker version, browser)
- Relevant logs (`make logs` output)

### Feature requests

Include:
- Use case (why this feature is needed)
- Proposed solution (if you have one)
- Alternatives considered

## Questions?

Open a [GitHub Discussion](https://github.com/ugurcsen/passage-vpn/discussions)
or ask in the relevant issue thread.
