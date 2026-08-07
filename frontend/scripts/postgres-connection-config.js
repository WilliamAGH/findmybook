import { spawn } from "node:child_process";
import fs from "node:fs";
import tls from "node:tls";
import { pathToFileURL } from "node:url";

const DATABASE_URL_ENVIRONMENT_VARIABLES = [
  "SPRING_DATASOURCE_URL",
  "DATABASE_URL",
  "POSTGRES_URL",
  "JDBC_DATABASE_URL",
];
const DATABASE_USERNAME_ENVIRONMENT_VARIABLES = [
  "SPRING_DATASOURCE_USERNAME",
  "DATABASE_USERNAME",
  "PGUSER",
];
const DATABASE_PASSWORD_ENVIRONMENT_VARIABLES = [
  "SPRING_DATASOURCE_PASSWORD",
  "DATABASE_PASSWORD",
  "PGPASSWORD",
];
const CONNECTION_SOURCE_ENVIRONMENT_VARIABLES = [
  ...DATABASE_URL_ENVIRONMENT_VARIABLES,
  ...DATABASE_USERNAME_ENVIRONMENT_VARIABLES,
  ...DATABASE_PASSWORD_ENVIRONMENT_VARIABLES,
];
const PSQL_CONNECTION_ENVIRONMENT_VARIABLES = [
  "PGHOST",
  "PGHOSTADDR",
  "PGPORT",
  "PGDATABASE",
  "PGUSER",
  "PGPASSWORD",
  "PGPASSFILE",
  "PGSERVICE",
  "PGSERVICEFILE",
  "PGSSLMODE",
  "PGREQUIRESSL",
  "PGSSLROOTCERT",
];
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "::1"]);
const SUPPORTED_SSL_MODES = new Set([
  "",
  "allow",
  "prefer",
  "require",
  "verify-ca",
  "verify-full",
  "disable",
]);

function configuredEnvironmentValue(environment, variableNames) {
  for (const variableName of variableNames) {
    const environmentValue = environment[variableName];
    if (typeof environmentValue === "string" && environmentValue.trim().length > 0) {
      return environmentValue;
    }
  }

  return undefined;
}

function decodePostgresUrlComponent(encodedComponent, componentName) {
  try {
    return decodeURIComponent(encodedComponent);
  } catch (error) {
    if (error instanceof URIError) {
      throw new Error(`Invalid percent-encoding in PostgreSQL ${componentName}`);
    }
    throw error;
  }
}

function parsePostgresUri(postgresUrl) {
  if (!URL.canParse(postgresUrl)) {
    // Node's ERR_INVALID_URL carries the original input, including credentials.
    throw new Error("PostgreSQL URL is not valid");
  }

  return new URL(postgresUrl);
}

function normalizedPostgresHost(hostname) {
  const normalizedHostname = hostname.toLowerCase();
  if (normalizedHostname.startsWith("[") && normalizedHostname.endsWith("]")) {
    return normalizedHostname.slice(1, -1);
  }

  return normalizedHostname;
}

/**
 * Resolves the datasource URL using the application's documented precedence.
 */
export function resolvePostgresUrl(environment = process.env) {
  const postgresUrl = configuredEnvironmentValue(environment, DATABASE_URL_ENVIRONMENT_VARIABLES);
  if (!postgresUrl) {
    throw new Error(
      "No PostgreSQL URL configured; set SPRING_DATASOURCE_URL, DATABASE_URL, POSTGRES_URL, or JDBC_DATABASE_URL",
    );
  }

  return postgresUrl.trim();
}

/**
 * Converts the documented JDBC PostgreSQL URL form into a URI Node and libpq can use.
 */
export function toPostgresUrl(postgresUrl) {
  if (typeof postgresUrl !== "string" || postgresUrl.trim().length === 0) {
    throw new Error("PostgreSQL URL must be a non-empty string");
  }

  const trimmedPostgresUrl = postgresUrl.trim();
  return /^jdbc:postgresql:\/\//iu.test(trimmedPostgresUrl)
    ? trimmedPostgresUrl.slice("jdbc:".length)
    : trimmedPostgresUrl;
}

/**
 * Resolves explicit application credentials before URI-embedded credentials.
 */
export function resolvePostgresCredentials(environment = process.env) {
  return {
    user: configuredEnvironmentValue(environment, DATABASE_USERNAME_ENVIRONMENT_VARIABLES),
    password: configuredEnvironmentValue(environment, DATABASE_PASSWORD_ENVIRONMENT_VARIABLES),
  };
}

/**
 * Parses the migration datasource while requiring verified TLS off loopback.
 */
export function parsePostgresUrl(postgresUrl, environment = process.env) {
  const resolvedPostgresUrl = postgresUrl || resolvePostgresUrl(environment);
  const parsed = parsePostgresUri(toPostgresUrl(resolvedPostgresUrl));
  if (parsed.protocol !== "postgres:" && parsed.protocol !== "postgresql:") {
    throw new Error(`Unsupported PostgreSQL URL protocol: ${parsed.protocol}`);
  }
  if (!parsed.hostname || (parsed.pathname && !parsed.pathname.startsWith("/"))) {
    throw new Error("PostgreSQL URL must include a hostname and hierarchical database path");
  }
  const host = normalizedPostgresHost(parsed.hostname);
  const port = parsed.port ? Number(parsed.port) : 5432;
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("PostgreSQL URL port must be between 1 and 65535");
  }

  const sslMode = (parsed.searchParams.get("sslmode") || "").toLowerCase();
  if (!SUPPORTED_SSL_MODES.has(sslMode)) {
    throw new Error(`Unsupported PostgreSQL sslmode: ${sslMode}`);
  }

  let ssl;
  let psqlSslMode;
  let rootCertificatePath;
  if (sslMode === "disable") {
    if (!LOOPBACK_HOSTS.has(host)) {
      throw new Error("sslmode=disable is allowed only for a loopback PostgreSQL host");
    }
    ssl = false;
    psqlSslMode = "disable";
  } else {
    ssl = {
      rejectUnauthorized: true,
      checkServerIdentity: (_servername, certificate) => tls.checkServerIdentity(host, certificate),
    };
    rootCertificatePath = configuredEnvironmentValue(environment, ["PGSSLROOTCERT"]);
    if (rootCertificatePath) {
      ssl.ca = fs.readFileSync(rootCertificatePath);
    }
    psqlSslMode = "verify-full";
  }

  const explicitCredentials = resolvePostgresCredentials(environment);
  return {
    host,
    port,
    database: decodePostgresUrlComponent(parsed.pathname.slice(1), "database name") || "postgres",
    user: explicitCredentials.user ?? decodePostgresUrlComponent(parsed.username, "username"),
    password: explicitCredentials.password ?? decodePostgresUrlComponent(parsed.password, "password"),
    ssl,
    psqlSslMode,
    rootCertificatePath,
  };
}

/**
 * Produces the only connection environment inherited by the psql subprocess.
 */
export function buildPsqlEnvironment(connection, environment = process.env) {
  const psqlEnvironment = { ...environment };
  for (const variableName of [
    ...CONNECTION_SOURCE_ENVIRONMENT_VARIABLES,
    ...PSQL_CONNECTION_ENVIRONMENT_VARIABLES,
  ]) {
    delete psqlEnvironment[variableName];
  }

  psqlEnvironment.PGHOST = connection.host;
  psqlEnvironment.PGPORT = String(connection.port);
  psqlEnvironment.PGDATABASE = connection.database;
  psqlEnvironment.PGSSLMODE = connection.psqlSslMode;
  if (connection.user) {
    psqlEnvironment.PGUSER = connection.user;
  }
  if (connection.password) {
    psqlEnvironment.PGPASSWORD = connection.password;
  }
  if (connection.rootCertificatePath) {
    psqlEnvironment.PGSSLROOTCERT = connection.rootCertificatePath;
  }

  return psqlEnvironment;
}

/**
 * Builds a psql subprocess invocation without serializing credentials into argv.
 */
export function buildPsqlInvocation(psqlArguments, connection, environment = process.env) {
  return {
    command: "psql",
    arguments: [...psqlArguments],
    options: {
      env: buildPsqlEnvironment(connection, environment),
      stdio: "inherit",
    },
  };
}

/**
 * Starts psql using the application datasource and preserves caller-provided psql arguments.
 */
export function spawnPsql(psqlArguments, connection, environment = process.env, spawnProcess = spawn) {
  const invocation = buildPsqlInvocation(psqlArguments, connection, environment);
  return spawnProcess(invocation.command, invocation.arguments, invocation.options);
}

/**
 * Runs psql and resolves with its exit code so the CLI forwards failures unchanged.
 */
export async function runPsql({
  psqlArguments = process.argv.slice(2),
  environment = process.env,
  spawnProcess = spawn,
} = {}) {
  const connection = parsePostgresUrl(undefined, environment);
  const child = spawnPsql(psqlArguments, connection, environment, spawnProcess);

  return new Promise((resolve, reject) => {
    child.once("error", error => reject(new Error("Unable to start psql", { cause: error })));
    child.once("exit", (exitCode, signal) => {
      if (signal) {
        reject(new Error(`psql terminated by signal ${signal}`));
        return;
      }
      resolve(exitCode ?? 1);
    });
  });
}

function runsAsScript(moduleUrl = import.meta.url, argv = process.argv) {
  return argv[1] !== undefined && moduleUrl === pathToFileURL(argv[1]).href;
}

if (runsAsScript()) {
  runPsql().then(
    exitCode => {
      process.exitCode = exitCode;
    },
    error => {
      console.error(error.message);
      process.exitCode = 1;
    },
  );
}
