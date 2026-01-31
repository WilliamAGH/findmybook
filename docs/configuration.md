# Configuration

## Environment Variables
Key variables in `.env`:

| Variable | Purpose |
|----------|---------|
| `SERVER_PORT` | App server port |
| `SPRING_DATASOURCE_*` | Database connection |
| `SPRING_AI_OPENAI_API_KEY` | OpenAI integration |
| `GOOGLE_BOOKS_API_KEY` | Book data source |
| `S3_*` | S3 storage (if used) |
| `APP_ADMIN_PASSWORD` | Admin user password |
| `APP_USER_PASSWORD` | Basic user password |

## User Accounts

| Username | Role(s) | Access | Password Env Variable |
|----------|---------|--------|----------------------|
| `admin` | `ADMIN`, `USER` | All + `/admin/**` | `APP_ADMIN_PASSWORD` |
| `user` | `USER` | General features | `APP_USER_PASSWORD` |

## Database Connection
If you use a Postgres URI like `postgres://user:pass@host:port/db?sslmode=prefer`, convert it to JDBC format in `application.properties` or via environment variables:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://<host>:<port>/<db>?sslmode=prefer"
export SPRING_DATASOURCE_USERNAME="<user>"
export SPRING_DATASOURCE_PASSWORD="<pass>"
```
