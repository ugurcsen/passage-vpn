# =========================================================
# OpenVPN management panel — primary dev/ops interface
# =========================================================
SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE      := docker compose
BACKEND_DIR  := backend
FRONTEND_DIR := frontend
ENV_FILE     := .env

.PHONY: help up down build logs ps restart \
        backend-dev frontend-dev \
        test test-backend test-frontend \
        lint lint-backend lint-frontend format \
        migrate seed-admin seed-demo backup \
        api-docs pki-init clean reset install

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

# ---------- containers ----------

up: ## Build and start all services
	$(COMPOSE) up -d --build

down: ## Stop services (keep data)
	$(COMPOSE) down

logs: ## Tail logs for all services
	$(COMPOSE) logs -f --tail=200

ps: ## Show service status
	$(COMPOSE) ps

build: ## Build backend + frontend + images
	$(MAKE) -C $(BACKEND_DIR) build 2>/dev/null || $(BACKEND_DIR)/gradlew -p $(BACKEND_DIR) build
	$(MAKE) -C $(FRONTEND_DIR) build 2>/dev/null || (cd $(FRONTEND_DIR) && npm run build)
	$(COMPOSE) build

restart: ## Restart all services
	$(COMPOSE) restart

# ---------- local development ----------

backend-dev: ## Run backend locally (needs .env)
	@test -f $(ENV_FILE) || { echo "Missing .env — copy from .env.example"; exit 1; }
	cd $(BACKEND_DIR) && ./gradlew bootRun

frontend-dev: ## Run frontend Vite dev server
	cd $(FRONTEND_DIR) && npm run dev

# ---------- quality ----------

test: ## Backend + frontend tests
	$(MAKE) test-backend test-frontend

test-backend: ## Backend tests (gradle)
	cd $(BACKEND_DIR) && ./gradlew test

test-frontend: ## Frontend tests (vitest)
	cd $(FRONTEND_DIR) && npm run test

lint: ## Frontend lint + backend spotless check
	$(MAKE) lint-backend lint-frontend

lint-backend: ## Backend spotless check
	cd $(BACKEND_DIR) && ./gradlew spotlessCheck

lint-frontend: ## Frontend eslint
	cd $(FRONTEND_DIR) && npm run lint

format: ## Apply formatting (backend spotless, frontend prettier via eslint --fix)
	cd $(BACKEND_DIR) && ./gradlew spotlessApply
	cd $(FRONTEND_DIR) && npx eslint src --fix

# ---------- admin / ops ----------

migrate: ## Apply DB migrations (via backend Flyway on boot; manual run for local)
	cd $(BACKEND_DIR) && ./gradlew bootRun --args='--spring.flyway.baseline-on-migrate=true' &
	@echo "Migrations run automatically on backend start; manual: java -jar backend.jar --spring.flyway.enabled=true"

seed-admin: ## Create initial admin user (non-wizard path)
	curl -sS -X POST http://localhost:8080/internal/seed-admin \
		-H 'Content-Type: application/json' \
		-d "{\"username\":\"admin\",\"password\":\"$${OPNL_ADMIN_PASSWORD:-change-me}\"}"

seed-demo: ## Load demo data (sample users/groups/rules); needs setup complete
	@code=$$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/internal/seed-demo \
		-H 'Content-Type: application/json' \
		-H "X-Internal-Token: $${OPNL_INTERNAL_TOKEN:-change-me-internal-token}" \
		-d '{"force":false}'); \
	[ "$$code" = "200" ] && echo "Demo data loaded" || { echo "seed-demo failed (HTTP $$code)"; exit 1; }

backup: ## Produce backup archive (config + PKI + DB dump)
	@test -d data || { echo "No data dir"; exit 1; }
	@ts=$$(date +%Y%m%d-%H%M%S); tar -czf backup-opnl-$$ts.tar.gz -C data . && echo "Wrote backup-opnl-$$ts.tar.gz"

api-docs: ## Regenerate docs/api.md from a running backend (needs up) + show swagger URL
	@code=$$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/v3/api-docs); \
	[ "$$code" = "200" ] || { echo "Backend not reachable on :8080 (got $$code) — run 'make up' first."; exit 1; }
	python3 scripts/gen_api_docs.py "http://localhost:8080/v3/api-docs" > docs/api.md
	@echo "Wrote docs/api.md"
	@echo "Swagger UI: http://localhost:8080/swagger-ui.html"

pki-init: ## Re-run PKI init (CA + server cert) via setup API
	@echo "Use the setup wizard UI at /setup instead."

# ---------- maintenance ----------

clean: ## Remove build artifacts (keep data)
	cd $(BACKEND_DIR) && ./gradlew clean
	rm -rf $(FRONTEND_DIR)/dist $(FRONTEND_DIR)/node_modules

reset: ## Stop services and wipe runtime data (danger!)
	$(COMPOSE) down -v
	rm -rf data
	@echo "Runtime data removed."

install: ## Single-command install (see install.sh)
	./install.sh
