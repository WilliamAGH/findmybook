# findmybook.net

Spring Boot + Svelte application for book lookup and recommendations using OpenAI and Google Books API.

![findmybook screenshot](src/main/resources/static/images/findmybook-net-screenshot.png)

**Live Demo:** [findmybook.net](https://findmybook.net)

## Quick Start

### Prerequisites
- Java 25
- Gradle (via `./gradlew`)
- Node 22.17.0

### Running locally

1. **Configure:** Copy `.env.example` to `.env` and update values.
   ```bash
   cp .env.example .env
   ```
2. **Run:** Start the application in dev mode.
   ```bash
   SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8095 ./gradlew bootRun
   ```
3. **Frontend (optional HMR):** Run Vite for SPA development.
   ```bash
   npm --prefix frontend run dev
   ```
4. **Access:** Open [http://localhost:8095](http://localhost:8095).

## Documentation

Detailed documentation is available in the `docs/` directory:

- [Development Guide](docs/development.md) - Shortcuts, JVM settings, and code analysis.
- [Configuration](docs/configuration.md) - Environment variables, user accounts, and database setup.
- [API Reference](docs/api.md) - Key endpoints, search pagination details, and admin API.
- [Features & Operations](docs/features.md) - S3 backfill, sitemap generation, and UML.
- [Troubleshooting](docs/troubleshooting.md) - Port conflicts and debugging overrides.

## Contributing

[Open an issue](https://github.com/WilliamAGH/findmybook/issues/new) for bugs or feature requests. PRs welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Other Projects

- [Composer](https://composerai.app) — AI-assisted email application ([GitHub](https://github.com/WilliamAGH/ComposerAI))
- [TUI4J](https://github.com/WilliamAGH/tui4j) — Modern terminal user interface library for Java
- [Brief](https://williamcallahan.com/projects/brief) — Beautiful terminal AI chat with tool calling ([GitHub](https://github.com/WilliamAGH/brief))

## License

Copyright © 2026 [William Callahan](https://williamcallahan.com).

See [LICENSE.md](LICENSE.md).
