# Troubleshooting

## Port Conflicts
If port 8095 is in use:

**macOS/Linux:**
```bash
kill -9 $(lsof -ti :8095)
```

**Windows:**
```bash
FOR /F "tokens=5" %i IN ('netstat -ano ^| findstr :8095') DO taskkill /F /PID %i
```

## Debugging Overrides

**Bypass Caches:**
```properties
googlebooks.api.override.bypass-caches=true
```

**Bypass Rate Limiter:**
```properties
resilience4j.ratelimiter.instances.googleBooksServiceRateLimiter.limitForPeriod=2147483647
resilience4j.ratelimiter.instances.googleBooksServiceRateLimiter.limitRefreshPeriod=1ms
resilience4j.ratelimiter.instances.googleBooksServiceRateLimiter.timeoutDuration=0ms
```
