/**
 * Custom condition that checks if S3 environment variables are present
 * This enables S3 services automatically when the required environment variables are available
 *
 * @author William Callahan
 *
 * Features:
 * - Validates presence of S3_ACCESS_KEY_ID environment variable
 * - Validates presence of S3_SECRET_ACCESS_KEY environment variable
 * - Validates presence of S3_BUCKET environment variable
 * - Enables S3-dependent beans only when all required variables are present
 * - Logs S3 availability status once during startup
 */
package net.findmybook.config;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.lang.NonNull;

public class S3EnvironmentCondition implements Condition {

    private static final Logger logger = LoggerFactory.getLogger(S3EnvironmentCondition.class);
    private static final AtomicBoolean messageLogged = new AtomicBoolean(false);

    @Override
    public boolean matches(@NonNull ConditionContext context, @NonNull AnnotatedTypeMetadata metadata) {
        String accessKeyId = firstNonBlank(context, "s3.access-key-id", "S3_ACCESS_KEY_ID");
        String secretAccessKey = firstNonBlank(context, "s3.secret-access-key", "S3_SECRET_ACCESS_KEY");
        String bucket = firstNonBlank(context, "s3.bucket-name", "S3_BUCKET");
        
        boolean hasRequiredVars = hasText(accessKeyId)
            && hasText(secretAccessKey)
            && hasText(bucket);
        
        // Only log the message once to avoid spam during startup
        if (messageLogged.compareAndSet(false, true)) {
            if (hasRequiredVars) {
                logger.info("✅ S3 environment variables detected - enabling S3 services (bucket: {})", bucket);
            } else {
                logger.error("❌ CRITICAL: S3 environment variables MISSING - S3 services DISABLED");
                logger.error("❌ Required configuration: s3.access-key-id/S3_ACCESS_KEY_ID, s3.secret-access-key/S3_SECRET_ACCESS_KEY, s3.bucket-name/S3_BUCKET");
                logger.error("❌ Current status: S3_ACCESS_KEY_ID={}, S3_SECRET_ACCESS_KEY={}, S3_BUCKET={}",
                    (hasText(accessKeyId) ? "SET" : "MISSING"),
                    (hasText(secretAccessKey) ? "SET" : "MISSING"),
                    (hasText(bucket) ? "SET" : "MISSING"));
                logger.error("❌ ALL COVER UPLOADS TO S3 WILL FAIL SILENTLY");
            }
        }
        
        return hasRequiredVars;
    }

    private static String firstNonBlank(ConditionContext context, String... keys) {
        for (String key : keys) {
            String value = context.getEnvironment().getProperty(key);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
