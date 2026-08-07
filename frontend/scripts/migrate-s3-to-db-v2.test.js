const nodeBuiltin = (moduleName) => `node:${moduleName}`;
const { default: assert } = await import(/* @vite-ignore */ nodeBuiltin("assert/strict"));

const { describe, it } = process.env.VITEST
  ? await import("vitest")
  : await import(/* @vite-ignore */ nodeBuiltin("test"));

if (process.env.VITEST) {
  describe("migrate-s3-to-db-v2 operational tests", () => {
    it("should_RunOperationalImportsUnderNode_When_VitestUsesHappyDom", async () => {
      const { execFileSync } = await import(/* @vite-ignore */ nodeBuiltin("child_process"));
      const { fileURLToPath } = await import(/* @vite-ignore */ nodeBuiltin("url"));
      const nodeTestEnvironment = { ...process.env };
      delete nodeTestEnvironment.VITEST;

      execFileSync(
        process.execPath,
        ["--test", fileURLToPath(import.meta.url)],
        { env: nodeTestEnvironment, stdio: "inherit" },
      );
    });
  });
} else {
  const { BookMigrator, JsonParser } = await import("./migrate-s3-to-db-v2.js");
  const {
    buildPsqlInvocation,
    parsePostgresUrl,
    resolvePostgresUrl,
    runPsql,
  } = await import("./postgres-connection-config.js");

  describe("BookMigrator.allocatePersistedSlug", () => {
    it("should_DelegateDistinctIdentitySlugsToPostgres_When_TitlesMatch", async () => {
      const firstBookId = "01989d24-0000-7000-8000-000000000001";
      const secondBookId = "01989d24-0000-7000-8000-000000000002";
      const databaseCalls = [];
      const client = {
        query: async (statement, parameters = []) => {
          databaseCalls.push({ statement, parameters });
          return { rows: [{ slug: `database-owned-${parameters[1]}` }] };
        },
      };
      const migrator = new BookMigrator(client);

      const firstSlug = await migrator.allocatePersistedSlug("Shared & Title", firstBookId);
      const secondSlug = await migrator.allocatePersistedSlug("Shared & Title", secondBookId);

      assert.equal(firstSlug, `database-owned-${firstBookId}`);
      assert.equal(secondSlug, `database-owned-${secondBookId}`);
      assert.notEqual(firstSlug, secondSlug);
      assert.deepEqual(databaseCalls, [
        {
          statement: "SELECT public.generate_slug($1, $2::uuid) AS slug",
          parameters: ["Shared & Title", firstBookId],
        },
        {
          statement: "SELECT public.generate_slug($1, $2::uuid) AS slug",
          parameters: ["Shared & Title", secondBookId],
        },
      ]);
    });

    it("should_RejectBlankPostgresSlug_When_PostgresViolatesThePersistenceContract", async () => {
      const bookId = "01989d24-0000-7000-8000-000000000003";
      const migrator = new BookMigrator({
        query: async () => ({ rows: [{ slug: " " }] }),
      });

      await assert.rejects(
        migrator.allocatePersistedSlug("Shared Title", bookId),
        /PostgreSQL returned a blank slug/,
      );
    });

    it("should_InsertDistinctIdentitySlugs_When_SameTitleUsesDistinctExternalIds", async () => {
      const databaseCalls = [];
      const client = {
        query: async (statement, parameters = []) => {
          databaseCalls.push({ statement, parameters });
          if (statement.includes("public.generate_slug")) {
            return { rows: [{ slug: `database-owned-${parameters[1]}` }] };
          }
          return { rows: [] };
        },
      };
      const migrator = new BookMigrator(client);

      const firstBookId = await migrator.findOrCreateBook("google-book-one", { title: "Shared Title" });
      const secondBookId = await migrator.findOrCreateBook("google-book-two", { title: "Shared Title" });
      const bookInserts = databaseCalls.filter(({ statement }) => statement.includes("INSERT INTO books"));

      assert.equal(bookInserts.length, 2);
      assert.notEqual(firstBookId, secondBookId);
      assert.equal(bookInserts[0].parameters[10], `database-owned-${firstBookId}`);
      assert.equal(bookInserts[1].parameters[10], `database-owned-${secondBookId}`);
      assert.equal(
        databaseCalls.filter(({ statement }) => statement.includes("public.generate_slug")).length,
        2,
      );
      assert.ok(databaseCalls.every(({ statement }) => !statement.includes("ensure_unique_slug")));
    });
  });

  describe("JsonParser provider identity", () => {
    it("should_PreserveDistinctProviderRecords_When_TitleAndFirstAuthorMatchWithoutAnIsbn", () => {
      const parser = new JsonParser();
      const records = parser.parse(JSON.stringify({
        items: [
          { id: "provider-volume-one", volumeInfo: { title: "Shared Title", authors: ["Shared Author"] } },
          { id: "provider-volume-two", volumeInfo: { title: "Shared Title", authors: ["Shared Author"] } },
        ],
      }), "provider-records.json");

      assert.deepEqual(records.map(({ id }) => id), ["provider-volume-one", "provider-volume-two"]);
    });

    it("should_CoalesceDuplicateProviderRecords_When_ExternalIdsMatch", () => {
      const parser = new JsonParser();
      const records = parser.parse(JSON.stringify({
        items: [
          { id: "provider-volume-one", volumeInfo: { title: "Shared Title", authors: ["Shared Author"] } },
          { id: "provider-volume-one", volumeInfo: { title: "Updated Shared Title", authors: ["Shared Author"] } },
        ],
      }), "duplicate-provider-records.json");

      assert.equal(records.length, 1);
      assert.equal(records[0].id, "provider-volume-one");
    });
  });

  describe("parsePostgresUrl", () => {
    it("should_UseApplicationUrlPrecedence_When_SeveralUrlsAreConfigured", () => {
      const environment = {
        SPRING_DATASOURCE_URL: "postgres://spring@spring.example.test/spring",
        DATABASE_URL: "postgres://database@database.example.test/database",
        POSTGRES_URL: "postgres://postgres@postgres.example.test/postgres",
        JDBC_DATABASE_URL: "jdbc:postgresql://jdbc.example.test/jdbc",
      };

      assert.equal(
        resolvePostgresUrl(environment),
        "postgres://spring@spring.example.test/spring",
      );
    });

    it("should_UseEachFallbackUrlInApplicationOrder_When_HigherPriorityUrlsAreUnset", () => {
      assert.equal(
        resolvePostgresUrl({ DATABASE_URL: "postgres://database.example.test/books" }),
        "postgres://database.example.test/books",
      );
      assert.equal(
        resolvePostgresUrl({ POSTGRES_URL: "postgres://postgres.example.test/books" }),
        "postgres://postgres.example.test/books",
      );
      assert.equal(
        resolvePostgresUrl({ JDBC_DATABASE_URL: "jdbc:postgresql://jdbc.example.test/books" }),
        "jdbc:postgresql://jdbc.example.test/books",
      );
    });

    it("should_UseCredentialFreeJdbcUrlAndExplicitSpringCredentials_When_MatchingEnvExample", () => {
      const config = parsePostgresUrl(undefined, {
        SPRING_DATASOURCE_URL: "jdbc:postgresql://aws-1-us-west-1.pooler.supabase.com:5432/postgres",
        SPRING_DATASOURCE_USERNAME: "postgres.something",
        SPRING_DATASOURCE_PASSWORD: "[YOUR-PASSWORD]",
      });

      assert.equal(config.host, "aws-1-us-west-1.pooler.supabase.com");
      assert.equal(config.port, 5432);
      assert.equal(config.database, "postgres");
      assert.equal(config.user, "postgres.something");
      assert.equal(config.password, "[YOUR-PASSWORD]");
      assert.equal(config.ssl.rejectUnauthorized, true);
      assert.equal(typeof config.ssl.checkServerIdentity, "function");
    });

    it("should_PreferExplicitSpringCredentials_When_UrlAlsoContainsCredentials", () => {
      const config = parsePostgresUrl(
        "postgres://url-user:url-password@db.example.test/books",
        {
          SPRING_DATASOURCE_USERNAME: "spring-user",
          SPRING_DATASOURCE_PASSWORD: "spring-password",
        },
      );

      assert.equal(config.user, "spring-user");
      assert.equal(config.password, "spring-password");
    });

    it("should_RejectNonPostgresUrlSchemes_When_ParsingPostgresUrl", () => {
      assert.throws(
        () => parsePostgresUrl("https://db.example.test/books"),
        /Unsupported PostgreSQL URL protocol: https:/,
      );
    });

    it("should_VerifyTls_When_RemoteUrlOmitsSslMode", () => {
      const config = parsePostgresUrl("postgres://user:password@db.example.test/books", {});

      assert.equal(config.ssl.rejectUnauthorized, true);
      assert.equal(typeof config.ssl.checkServerIdentity, "function");
    });

    it("should_VerifyCertificateIdentityAgainstRemoteIp_When_PgOmitsServerName", () => {
      const config = parsePostgresUrl("postgres://user:password@75.8.210.183/books", {});

      assert.equal(
        config.ssl.checkServerIdentity(undefined, { subjectaltname: "IP Address:75.8.210.183" }),
        undefined,
      );
      const identityError = config.ssl.checkServerIdentity(
        undefined,
        { subjectaltname: "IP Address:75.8.210.184" },
      );
      assert.equal(identityError?.code, "ERR_TLS_CERT_ALTNAME_INVALID");
    });

    it("should_RejectPlaintext_When_RemoteUrlDisablesTls", () => {
      assert.throws(() => parsePostgresUrl(
        "postgres://user:password@db.example.test/books?sslmode=disable",
      ), /sslmode=disable is allowed only for a loopback PostgreSQL host/);
    });

    it("should_AllowPlaintext_When_LoopbackUrlExplicitlyDisablesTls", () => {
      const config = parsePostgresUrl(
        "postgres://user:password@127.0.0.1:5432/books?sslmode=disable",
      );

      assert.equal(config.ssl, false);
    });

    it("should_DecodePercentEncodedCredentialsAndDatabaseName_When_ParsingPostgresUrl", () => {
      const config = parsePostgresUrl(
        "postgres://reader%40tenant:token%3Avalue@db.example.test/book%20catalog",
        {},
      );

      assert.equal(config.user, "reader@tenant");
      assert.equal(config.password, "token:value");
      assert.equal(config.database, "book catalog");
    });

    it("should_RejectMalformedPercentEncoding_When_ParsingPostgresUrl", () => {
      assert.throws(
        () => parsePostgresUrl("postgres://user:password@db.example.test/books%ZZ"),
        /Invalid percent-encoding in PostgreSQL database name/,
      );
    });

    it("should_RejectMalformedAuthorityWithoutExposingCredentials_When_ParsingPostgresUrl", () => {
      const malformedUrl = "postgres://user:topsecret@[invalid";

      assert.throws(
        () => parsePostgresUrl(malformedUrl),
        (error) => {
          assert.equal(error.message, "PostgreSQL URL is not valid");
          assert.equal(String(error).includes("topsecret"), false);
          return true;
        },
      );
    });

    it("should_RejectHostlessDatasourceForms_When_ParsingPostgresUrl", () => {
      for (const hostlessUrl of ["postgres:books", "postgres:///books"]) {
        assert.throws(
          () => parsePostgresUrl(hostlessUrl),
          /PostgreSQL URL must include a hostname and hierarchical database path/,
        );
      }
    });

    it("should_RejectPortZero_When_ParsingPostgresUrl", () => {
      assert.throws(
        () => parsePostgresUrl("postgres://db.example.test:0/books"),
        /PostgreSQL URL port must be between 1 and 65535/,
      );
    });
  });

  describe("buildPsqlInvocation", () => {
    it("should_UseCanonicalRunnerWithNoDirectPsql_When_MakeRecipesNeedPostgres", async () => {
      const { readFileSync } = await import(/* @vite-ignore */ nodeBuiltin("fs"));
      const { fileURLToPath } = await import(/* @vite-ignore */ nodeBuiltin("url"));
      const makeRecipePaths = [
        fileURLToPath(new URL("../../Makefile", import.meta.url)),
        fileURLToPath(new URL("../../backfill-ai-seo.mk", import.meta.url)),
      ];

      for (const makeRecipePath of makeRecipePaths) {
        const makeRecipeContents = readFileSync(makeRecipePath, "utf8");
        const runnerCommands = makeRecipeContents.match(
          /node frontend\/scripts\/postgres-connection-config\.js[^\r\n]*/gu,
        ) ?? [];

        assert.doesNotMatch(makeRecipeContents, /(?:^|[^a-zA-Z0-9_])psql(?:\s|["'])/u);
        assert.ok(runnerCommands.length > 0, `${makeRecipePath} must invoke the PostgreSQL runner`);
        for (const runnerCommand of runnerCommands) {
          assert.match(runnerCommand, /(?:^|\s)-X(?:\s|$)/u);
        }
      }
    });

    it("should_PassConnectionOnlyThroughPgEnvironment_When_SpawningPsql", () => {
      const connection = parsePostgresUrl(
        "postgres://url-user:url-password@db.example.test:5444/book%20catalog?sslmode=require",
        {
          SPRING_DATASOURCE_USERNAME: "spring-user",
          SPRING_DATASOURCE_PASSWORD: "spring-password",
        },
      );
      const psqlArguments = ["--set", "search_path=public", "--command", "SELECT 1"];
      const invocation = buildPsqlInvocation(psqlArguments, connection, {
        PATH: "/usr/bin",
        DATABASE_URL: "postgres://leaked-user:leaked-password@other.example.test/other",
        SPRING_DATASOURCE_USERNAME: "leaked-user",
        SPRING_DATASOURCE_PASSWORD: "leaked-password",
        PGHOST: "unexpected-host",
        PGDATABASE: "postgres://unexpected-user:unexpected-password@unexpected-host/unexpected-database",
        PGPASSWORD: "unexpected-password",
        PGSSLMODE: "disable",
      });

      assert.equal(invocation.command, "psql");
      assert.deepEqual(invocation.arguments, psqlArguments);
      assert.equal(invocation.options.stdio, "inherit");
      assert.equal(invocation.options.env.PGHOST, "db.example.test");
      assert.equal(invocation.options.env.PGPORT, "5444");
      assert.equal(invocation.options.env.PGDATABASE, "book catalog");
      assert.equal(invocation.options.env.PGUSER, "spring-user");
      assert.equal(invocation.options.env.PGPASSWORD, "spring-password");
      assert.equal(invocation.options.env.PGSSLMODE, "verify-full");
      assert.equal(invocation.options.env.DATABASE_URL, undefined);
      assert.equal(invocation.options.env.SPRING_DATASOURCE_USERNAME, undefined);
      assert.equal(invocation.options.env.SPRING_DATASOURCE_PASSWORD, undefined);
      assert.equal(invocation.arguments.join("\\u0000").includes("spring-password"), false);
      assert.equal(invocation.arguments.join("\\u0000").includes("url-password"), false);
    });

    it("should_DisablePsqlTlsOnlyForExplicitLoopbackMode_When_BuildingInvocation", () => {
      const connection = parsePostgresUrl(
        "postgres://reader:password@127.0.0.1:5432/books?sslmode=disable",
      );
      const invocation = buildPsqlInvocation([], connection, {});

      assert.equal(invocation.options.env.PGSSLMODE, "disable");
    });

    it("should_SpawnPsqlWithPreservedArguments_When_RunningTheCli", async () => {
      const { EventEmitter } = await import(/* @vite-ignore */ nodeBuiltin("events"));
      const psqlArguments = ["--command", "SELECT current_user"];
      const spawnedChild = new EventEmitter();
      let receivedInvocation;

      const exitCode = await runPsql({
        psqlArguments,
        environment: {
          SPRING_DATASOURCE_URL: "jdbc:postgresql://db.example.test:5432/books",
          SPRING_DATASOURCE_USERNAME: "reader",
          SPRING_DATASOURCE_PASSWORD: "password",
        },
        spawnProcess: (command, arguments_, options) => {
          receivedInvocation = { command, arguments: arguments_, options };
          queueMicrotask(() => spawnedChild.emit("exit", 0, null));
          return spawnedChild;
        },
      });

      assert.equal(exitCode, 0);
      assert.equal(receivedInvocation.command, "psql");
      assert.deepEqual(receivedInvocation.arguments, psqlArguments);
      assert.equal(receivedInvocation.options.stdio, "inherit");
      assert.equal(receivedInvocation.options.env.PGUSER, "reader");
      assert.equal(receivedInvocation.options.env.PGPASSWORD, "password");
      assert.equal(receivedInvocation.arguments.join(" ").includes("password"), false);
    });
  });
}
