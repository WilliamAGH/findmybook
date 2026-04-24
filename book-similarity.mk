SIMILARITY_LIMIT ?= 120

.PHONY: book-similarity-backfill

book-similarity-backfill:
	@echo "Starting book similarity embedding backfill (limit=$(SIMILARITY_LIMIT))..."
	@if [ ! -f .env ]; then \
		echo "Error: .env file not found."; \
		exit 1; \
	fi
	@SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8095 $(GRADLEW) bootRun --args="--app.similarity.embeddings.backfill=true --app.similarity.embeddings.backfill-limit=$(SIMILARITY_LIMIT)"
