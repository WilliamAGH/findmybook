SHELL := /bin/sh

# Configurable variables
PORT ?= 8095
GRADLEW ?= ./gradlew

# Migration args (override via: make migrate-books MIGRATE_MAX=100 MIGRATE_SKIP=0 MIGRATE_PREFIX=books/v1/ MIGRATE_DEBUG=true)
MIGRATE_MAX ?= 0
MIGRATE_SKIP ?= 0
MIGRATE_PREFIX ?= books/v1/
MIGRATE_DEBUG ?= false
S3_ACL_SCOPE ?= all
S3_ACL_PREFIX ?=
S3_ACL_DRY_RUN ?= false
S3_ACL_VERBOSE ?= false

.PHONY: run build test lint kill-port migrate-books cluster-books check-s3-in-db fix-s3-acl-public-all db-verify-author-constraints db-verify-book-title-constraints

# Kill any process currently listening on $(PORT)
kill-port:
	@echo "Checking for processes on port $(PORT)..."
	@PIDS=$$(lsof -ti tcp:$(PORT)); \
	if [ -n "$$PIDS" ]; then \
	  echo "Killing PIDs: $$PIDS"; \
	  kill -9 $$PIDS || true; \
	else \
	  echo "No processes found on port $(PORT)."; \
	fi

# Run the application locally in dev mode
run: kill-port
	SERVER_PORT=$(PORT) SPRING_PROFILES_ACTIVE=dev $(GRADLEW) bootRun

# Build the application JAR
build:
	$(GRADLEW) clean build

# Run tests
test:
	$(GRADLEW) test

lint:
	@echo "Running lint/format..."
	@if $(GRADLEW) -q tasks --all | grep -q "spotlessApply"; then \
	  $(GRADLEW) spotlessApply; \
	else \
	  echo "spotlessApply task not configured; skipping."; \
	fi


# Fast S3 -> Postgres books migration (standalone Node.js script - v2 refactored)
# Requires: npm install pg @aws-sdk/client-s3
# Uses SPRING_DATASOURCE_URL, S3_* env vars from .env
migrate-books:
	@echo "Running standalone S3 -> Postgres migration (v2 - Refactored)..."
	@node frontend/scripts/migrate-s3-to-db-v2.js --max=$(MIGRATE_MAX) --skip=$(MIGRATE_SKIP) --prefix=$(MIGRATE_PREFIX) --debug=$(MIGRATE_DEBUG)

# Database schema operations
db-reset:
	@echo "⚠️  WARNING: This will DROP and recreate all tables!"
	@echo "Press Ctrl+C to abort, or wait 3 seconds to continue..."
	@sleep 3
	@echo "Resetting database schema..."
	@if [ -f .env ]; then \
		set -a && source .env && set +a && \
		if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
			echo "❌ Error: SPRING_DATASOURCE_URL not found in .env"; \
			exit 1; \
		fi && \
		psql "$$SPRING_DATASOURCE_URL" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" && \
			psql "$$SPRING_DATASOURCE_URL" -v ON_ERROR_STOP=1 -f src/main/resources/schema.sql && \
		echo "✅ Database schema reset complete"; \
	else \
		echo "❌ Error: .env file not found"; \
		exit 1; \
	fi

# Apply schema without dropping (safe for existing data)
db-migrate:
	@echo "Applying schema changes (safe mode)..."
	@if [ -f .env ]; then \
		set -a && source .env && set +a && \
		if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
			echo "❌ Error: SPRING_DATASOURCE_URL not found in .env"; \
			exit 1; \
		fi && \
			psql "$$SPRING_DATASOURCE_URL" -v ON_ERROR_STOP=1 -f src/main/resources/schema.sql && \
		echo "✅ Schema migration complete"; \
	else \
		echo "❌ Error: .env file not found"; \
		exit 1; \
	fi

# Verify author constraints align with migration-defined guardrails
db-verify-author-constraints:
	@echo "Verifying author schema constraints..."
	@if [ -f .env ]; then \
		set -a && source .env && set +a && \
		if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
			echo "❌ Error: SPRING_DATASOURCE_URL not found in .env"; \
			exit 1; \
		fi && \
		missing=$$(psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT string_agg(expected, ',') FROM (VALUES ('authors_name_non_blank_check'), ('authors_name_leading_character_check')) AS expected_constraints(expected) LEFT JOIN pg_constraint c ON c.conname = expected_constraints.expected AND c.conrelid = 'authors'::regclass WHERE c.oid IS NULL;") && \
		if [ -n "$$missing" ]; then \
			echo "❌ Missing author constraints: $$missing"; \
			exit 1; \
		fi && \
		non_blank_def=$$(psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'authors'::regclass AND conname = 'authors_name_non_blank_check';") && \
		leading_def=$$(psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'authors'::regclass AND conname = 'authors_name_leading_character_check';") && \
		echo "$$non_blank_def" | grep -F "btrim(name) <> ''" >/dev/null || { echo "❌ authors_name_non_blank_check has unexpected definition: $$non_blank_def"; exit 1; } && \
		echo "$$leading_def" | grep -F "regexp_replace(" >/dev/null || { echo "❌ authors_name_leading_character_check missing regexp_replace: $$leading_def"; exit 1; } && \
		echo "$$leading_def" | grep -F "^[[:alpha:][:digit:]]" >/dev/null || { echo "❌ authors_name_leading_character_check missing alpha/digit guard: $$leading_def"; exit 1; } && \
			echo "✅ Author constraints match migration-defined guardrails"; \
		else \
			echo "❌ Error: .env file not found"; \
			exit 1; \
		fi

# Verify book title constraints align with migration-defined guardrails
db-verify-book-title-constraints:
	@echo "Verifying book title schema constraints..."
	@if [ -f .env ]; then \
		set -a && source .env && set +a && \
		if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
			echo "❌ Error: SPRING_DATASOURCE_URL not found in .env"; \
			exit 1; \
		fi && \
		missing=$$(psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT string_agg(expected, ',') FROM (VALUES ('books_title_non_blank_check'), ('books_title_leading_character_check')) AS expected_constraints(expected) LEFT JOIN pg_constraint c ON c.conname = expected_constraints.expected AND c.conrelid = 'books'::regclass WHERE c.oid IS NULL;") && \
		if [ -n "$$missing" ]; then \
			echo "❌ Missing book title constraints: $$missing"; \
			exit 1; \
		fi && \
		non_blank_def=$$(psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'books'::regclass AND conname = 'books_title_non_blank_check';") && \
		leading_def=$$(psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'books'::regclass AND conname = 'books_title_leading_character_check';") && \
		echo "$$non_blank_def" | grep -F "btrim(title) <> ''" >/dev/null || { echo "❌ books_title_non_blank_check has unexpected definition: $$non_blank_def"; exit 1; } && \
		echo "$$leading_def" | grep -F "regexp_replace(title" >/dev/null || { echo "❌ books_title_leading_character_check missing regexp_replace: $$leading_def"; exit 1; } && \
		echo "$$leading_def" | grep -F "^[[:alpha:][:digit:]]" >/dev/null || { echo "❌ books_title_leading_character_check missing alpha/digit guard: $$leading_def"; exit 1; } && \
		echo "✅ Book title constraints match migration-defined guardrails"; \
	else \
		echo "❌ Error: .env file not found"; \
		exit 1; \
	fi

# Refresh search materialized view
db-refresh-search:
	@echo "Refreshing search view..."
	@if [ -f .env ]; then \
		set -a && source .env && set +a && \
		if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
			echo "❌ Error: SPRING_DATASOURCE_URL not found in .env"; \
			exit 1; \
		fi && \
		psql "$$SPRING_DATASOURCE_URL" -c "SELECT refresh_book_search_view();" && \
		echo "✅ Search view refreshed"; \
	else \
		echo "❌ Error: .env file not found"; \
		exit 1; \
	fi

# Generate slugs for all books (run after migration)
db-generate-slugs:
	@echo "Generating SEO slugs for all books..."
	@if [ -f .env ]; then \
		set -a && source .env && set +a && \
		if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
			echo "❌ Error: SPRING_DATASOURCE_URL not found in .env"; \
			exit 1; \
		fi && \
		psql "$$SPRING_DATASOURCE_URL" -c "SELECT generate_all_book_slugs();" && \
		echo "✅ Slugs generated for all books"; \
	else \
		echo "❌ Error: .env file not found"; \
		exit 1; \
	fi

# Cluster books into edition families (run after adding new books)
cluster-books:
	@echo "Clustering books into edition families..."
	@if [ -f .env ]; then \
		set -a && source .env && set +a && \
		if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
			echo "❌ Error: SPRING_DATASOURCE_URL not found in .env"; \
			exit 1; \
		fi && \
		echo "Clustering by ISBN prefix..." && \
		psql "$$SPRING_DATASOURCE_URL" -c "SELECT * FROM cluster_books_by_isbn();" && \
		echo "Clustering by Google canonical ID..." && \
		psql "$$SPRING_DATASOURCE_URL" -c "SELECT * FROM cluster_books_by_google_canonical();" && \
		echo "Getting statistics..." && \
		psql "$$SPRING_DATASOURCE_URL" -c "SELECT * FROM get_clustering_stats();" && \
		echo "✅ Book clustering complete"; \
	else \
		echo "❌ Error: .env file not found"; \
		exit 1; \
	fi

# Validate every DB-referenced S3 key and clear stale s3_image_path values when missing
check-s3-in-db:
	@echo "Checking book_image_links.s3_image_path against object storage..."
	@if [ -f .env ]; then \
		set -a && . ./.env && set +a; \
		if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
			echo "❌ Error: SPRING_DATASOURCE_URL not found in .env"; \
			exit 1; \
		fi; \
		if [ -z "$$S3_BUCKET" ]; then \
			echo "❌ Error: S3_BUCKET not found in .env"; \
			exit 1; \
		fi; \
		if ! command -v aws >/dev/null 2>&1; then \
			echo "❌ Error: aws CLI not found. Install AWS CLI to run this check."; \
			exit 1; \
		fi; \
		total=$$(psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT count(*) FROM book_image_links WHERE s3_image_path IS NOT NULL AND btrim(s3_image_path) <> ''"); \
		if [ "$$total" -eq 0 ] 2>/dev/null; then \
			echo "✅ No non-empty s3_image_path values found."; \
			exit 0; \
		fi; \
		tmp_rows=$$(mktemp); \
		trap 'rm -f "$$tmp_rows"' EXIT INT TERM; \
		psql "$$SPRING_DATASOURCE_URL" -At -F "|" -c "SELECT id, s3_image_path FROM book_image_links WHERE s3_image_path IS NOT NULL AND btrim(s3_image_path) <> '' ORDER BY id" > "$$tmp_rows"; \
		checked=0; \
		found=0; \
		missing=0; \
		update_errors=0; \
		endpoint_flag=""; \
		if [ -n "$$S3_SERVER_URL" ]; then \
			endpoint_flag="--endpoint-url $$S3_SERVER_URL"; \
		fi; \
		while IFS='|' read -r row_id s3_key; do \
			checked=$$((checked + 1)); \
			printf '[%s/%s] %s ... ' "$$checked" "$$total" "$$s3_key"; \
			if AWS_PAGER="" aws s3api head-object --bucket "$$S3_BUCKET" --key "$$s3_key" $$endpoint_flag >/dev/null 2>&1; then \
				found=$$((found + 1)); \
				echo "OK"; \
			else \
				missing=$$((missing + 1)); \
				echo "MISSING -> clearing DB value"; \
				escaped_row_id=$$(printf "%s" "$$row_id" | sed "s/'/''/g"); \
				escaped_s3_key=$$(printf "%s" "$$s3_key" | sed "s/'/''/g"); \
				if ! psql "$$SPRING_DATASOURCE_URL" -v ON_ERROR_STOP=1 -c "UPDATE book_image_links SET s3_image_path = NULL WHERE id = '$$escaped_row_id' AND s3_image_path = '$$escaped_s3_key'" >/dev/null; then \
					update_errors=$$((update_errors + 1)); \
					echo "   ❌ Failed DB update for row $$row_id"; \
				fi; \
			fi; \
		done < "$$tmp_rows"; \
		echo "✅ Done. checked=$$checked found=$$found missing=$$missing updateErrors=$$update_errors"; \
	else \
		echo "❌ Error: .env file not found"; \
		exit 1; \
	fi

# Ensure every object in S3 bucket has public-read ACL.
# Optional env vars:
#   S3_ACL_DRY_RUN=true  -> report changes without writing ACLs
#   S3_ACL_PREFIX=path/  -> limit scan to keys under prefix
#   S3_ACL_SCOPE=images  -> process image-like keys only
#   S3_ACL_SCOPE=json    -> process *.json keys only
#   S3_ACL_VERBOSE=true  -> log every changed key (default: false)
fix-s3-acl-public-all:
	@./scripts/fix-s3-object-acl.sh \
		--scope "$(S3_ACL_SCOPE)" \
		--prefix "$(S3_ACL_PREFIX)" \
		--dry-run "$(S3_ACL_DRY_RUN)" \
		--verbose "$(S3_ACL_VERBOSE)"

include backfill-ai-seo.mk
