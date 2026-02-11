BACKFILL_CP_INIT_SCRIPT ?= /tmp/print-cp.gradle
AI_MIN_DESCRIPTION_LENGTH ?= 50
LIMIT ?= all
REGENERATE ?= false
BACKFILL_IDENTIFIER ?=
BACKFILL_MISSING_ONLY ?= true
BACKFILL_BASE_URL ?=
BACKFILL_MODEL ?=
BACKFILL_API_KEY ?=

# Backwards-compatible aliases.
BACKFILL_LIMIT ?= $(LIMIT)
BACKFILL_FORCE ?= $(REGENERATE)

.PHONY: backfill-ai-seo backfill-ai-seo-force backfill-ai-seo-one

backfill-ai-seo:
	@echo "Starting AI + SEO backfill..."
	@if [ ! -f .env ]; then \
		echo "Error: .env file not found."; \
		exit 1; \
	fi
	@set -a && . ./.env && set +a; \
	if [ -n "$(BACKFILL_BASE_URL)" ]; then \
		export AI_DEFAULT_OPENAI_BASE_URL="$(BACKFILL_BASE_URL)"; \
	fi; \
	if [ -n "$(BACKFILL_MODEL)" ]; then \
		export AI_DEFAULT_LLM_MODEL="$(BACKFILL_MODEL)"; \
		export AI_DEFAULT_SEO_LLM_MODEL="$(BACKFILL_MODEL)"; \
	fi; \
	if [ -n "$(BACKFILL_API_KEY)" ]; then \
		export AI_DEFAULT_OPENAI_API_KEY="$(BACKFILL_API_KEY)"; \
	fi; \
	if [ -z "$$SPRING_DATASOURCE_URL" ]; then \
		echo "Error: SPRING_DATASOURCE_URL not found in .env."; \
		exit 1; \
	fi; \
	if [ -n "$(BACKFILL_BASE_URL)" ] || [ -n "$(BACKFILL_MODEL)" ] || [ -n "$(BACKFILL_API_KEY)" ]; then \
		echo "Applying runtime AI overrides for backfill."; \
	fi; \
	if [ ! -f "$(BACKFILL_CP_INIT_SCRIPT)" ]; then \
		printf '%s\n' \
			'allprojects {' \
			'    tasks.register("printCp") {' \
			'        doLast {' \
			'            println project.configurations.runtimeClasspath.files.collect { it.absolutePath }.join('\'':'\'')' \
			'        }' \
			'    }' \
			'}' > "$(BACKFILL_CP_INIT_SCRIPT)"; \
	fi; \
	echo "Compiling Java classes..."; \
	$(GRADLEW) -q compileJava >/dev/null; \
	DEP_CP=$$($(GRADLEW) -q --no-configuration-cache --init-script "$(BACKFILL_CP_INIT_SCRIPT)" printCp); \
	CP="$$DEP_CP:$(PWD)/build/classes/java/main:$(PWD)/src/main/resources"; \
	LIMIT_VALUE="$(BACKFILL_LIMIT)"; \
	LIMIT_CLAUSE=""; \
	if [ -n "$$LIMIT_VALUE" ] && [ "$$LIMIT_VALUE" != "all" ]; then \
		case "$$LIMIT_VALUE" in \
			''|*[!0-9]*) echo "Error: LIMIT/BACKFILL_LIMIT must be a positive integer or 'all'."; exit 1 ;; \
			0) echo "Error: LIMIT/BACKFILL_LIMIT must be greater than 0."; exit 1 ;; \
		esac; \
		LIMIT_CLAUSE=" LIMIT $$LIMIT_VALUE"; \
	fi; \
	ID_FILE=$$(mktemp /tmp/findmybook-ai-seo-ids.XXXXXX); \
	trap 'rm -f "$$ID_FILE"' EXIT INT TERM; \
	if [ -n "$(BACKFILL_IDENTIFIER)" ]; then \
		ESCAPED_IDENTIFIER=$$(printf "%s" "$(BACKFILL_IDENTIFIER)" | sed "s/'/''/g"); \
		psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT b.id FROM books b WHERE (b.id::text = '$$ESCAPED_IDENTIFIER' OR b.slug = '$$ESCAPED_IDENTIFIER' OR b.isbn10 = '$$ESCAPED_IDENTIFIER' OR b.isbn13 = '$$ESCAPED_IDENTIFIER') AND length(btrim(COALESCE(b.description, ''))) >= $(AI_MIN_DESCRIPTION_LENGTH) LIMIT 1;" > "$$ID_FILE"; \
	else \
		if [ "$(BACKFILL_MISSING_ONLY)" = "true" ]; then \
			psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT b.id FROM books b WHERE length(btrim(COALESCE(b.description, ''))) >= $(AI_MIN_DESCRIPTION_LENGTH) AND NOT EXISTS (SELECT 1 FROM book_seo_metadata s WHERE s.book_id = b.id AND s.is_current = TRUE) ORDER BY b.created_at DESC$$LIMIT_CLAUSE;" > "$$ID_FILE"; \
		else \
			psql "$$SPRING_DATASOURCE_URL" -At -c "SELECT b.id FROM books b WHERE length(btrim(COALESCE(b.description, ''))) >= $(AI_MIN_DESCRIPTION_LENGTH) ORDER BY b.created_at DESC$$LIMIT_CLAUSE;" > "$$ID_FILE"; \
		fi; \
	fi; \
	echo "Eligibility filter: description length >= $(AI_MIN_DESCRIPTION_LENGTH)"; \
	processed=0; \
	failures=0; \
	while IFS= read -r book_id; do \
		[ -z "$$book_id" ] && continue; \
		processed=$$((processed + 1)); \
		echo "Backfilling $$book_id ($$processed)..."; \
		if [ "$(BACKFILL_FORCE)" = "true" ]; then \
			java --enable-preview --source 25 -cp "$$CP" scripts/BackfillBookAiSeoMetadata.java "$$book_id" --force || failures=$$((failures + 1)); \
		else \
			java --enable-preview --source 25 -cp "$$CP" scripts/BackfillBookAiSeoMetadata.java "$$book_id" || failures=$$((failures + 1)); \
		fi; \
	done < "$$ID_FILE"; \
	echo "Backfill complete. processed=$$processed failures=$$failures"; \
	if [ "$$processed" -eq 0 ]; then \
		echo "No books matched the current selection."; \
	fi; \
	if [ "$$failures" -gt 0 ]; then \
		exit 1; \
	fi

backfill-ai-seo-force:
	@$(MAKE) backfill-ai-seo REGENERATE=true

backfill-ai-seo-one:
	@if [ -z "$(BOOK_IDENTIFIER)" ]; then \
		echo "Usage: make backfill-ai-seo-one BOOK_IDENTIFIER=<uuid|slug|isbn> [REGENERATE=true]"; \
		exit 1; \
	fi
	@$(MAKE) backfill-ai-seo BACKFILL_IDENTIFIER="$(BOOK_IDENTIFIER)" BACKFILL_LIMIT=1 BACKFILL_MISSING_ONLY=false REGENERATE="$(REGENERATE)"
