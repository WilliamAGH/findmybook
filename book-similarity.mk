SIMILARITY_CP_INIT_SCRIPT ?= /tmp/print-cp.gradle
SIMILARITY_LIMIT ?= 120
SIMILARITY_TOP ?= 20
SIMILARITY_BATCH_SIZE ?= 32
SIMILARITY_ANCHOR ?=
SIMILARITY_BOOK ?=
SIMILARITY_ID_FILE ?=
SIMILARITY_PROFILE ?=
SIMILARITY_MODEL ?=
SIMILARITY_DRY_RUN ?= false

.PHONY: book-similarity-backfill book-similarity-anchor

book-similarity-backfill:
	@echo "Starting book similarity embedding backfill..."
	@if [ ! -f .env ]; then \
		echo "Error: .env file not found."; \
		exit 1; \
	fi
	@if [ ! -f "$(SIMILARITY_CP_INIT_SCRIPT)" ]; then \
		printf '%s\n' \
			'allprojects {' \
			'    tasks.register("printCp") {' \
			'        doLast {' \
			'            println project.configurations.runtimeClasspath.files.collect { it.absolutePath }.join('\'':'\'')' \
			'        }' \
			'    }' \
			'}' > "$(SIMILARITY_CP_INIT_SCRIPT)"; \
	fi
	@echo "Compiling Java classes..."
	@$(GRADLEW) -q compileJava --rerun-tasks >/dev/null
	@DEP_CP=$$($(GRADLEW) -q --no-configuration-cache --console=plain --init-script "$(SIMILARITY_CP_INIT_SCRIPT)" printCp); \
	CP="$$DEP_CP:$(PWD)/build/classes/java/main:$(PWD)/src/main/resources"; \
	set -- "--limit=$(SIMILARITY_LIMIT)" "--top=$(SIMILARITY_TOP)" "--batch-size=$(SIMILARITY_BATCH_SIZE)"; \
	if [ -n "$(SIMILARITY_ANCHOR)" ]; then set -- "$$@" "--anchor=$(SIMILARITY_ANCHOR)"; fi; \
	if [ -n "$(SIMILARITY_BOOK)" ]; then set -- "$$@" "--book=$(SIMILARITY_BOOK)"; fi; \
	if [ -n "$(SIMILARITY_ID_FILE)" ]; then set -- "$$@" "--id-file=$(SIMILARITY_ID_FILE)"; fi; \
	if [ -n "$(SIMILARITY_PROFILE)" ]; then set -- "$$@" "--profile=$(SIMILARITY_PROFILE)"; fi; \
	if [ -n "$(SIMILARITY_MODEL)" ]; then set -- "$$@" "--model=$(SIMILARITY_MODEL)"; fi; \
	if [ "$(SIMILARITY_DRY_RUN)" = "true" ]; then set -- "$$@" "--dry-run"; fi; \
	java --enable-preview --source 25 -cp "$$CP" scripts/BackfillBookSimilarityEmbeddings.java "$$@"

book-similarity-anchor:
	@if [ -z "$(BOOK_IDENTIFIER)" ]; then \
		echo "Usage: make book-similarity-anchor BOOK_IDENTIFIER=<uuid|slug|isbn> [SIMILARITY_LIMIT=120] [SIMILARITY_TOP=20]"; \
		exit 1; \
	fi
	@$(MAKE) book-similarity-backfill SIMILARITY_ANCHOR="$(BOOK_IDENTIFIER)"
