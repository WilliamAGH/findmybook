/**
 * Spring {@link org.springframework.context.annotation.Condition} that gates S3-dependent beans
 * on the presence of required credentials and bucket configuration.
 */
package net.findmybook.config;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class S3EnvironmentCondition implements Condition {

    private static final Logger logger = LoggerFactory.getLogger(S3EnvironmentCondition.class);
    private static final AtomicBoolean messageLogged = new AtomicBoolean(false);
    private static final String STATUS_MISSING = "MISSING";
    private static final String STATUS_SET = "SET";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String accessKeyId = firstNonBlank(context, "s3.access-key-id", "S3_ACCESS_KEY_ID");
        String secretAccessKey = firstNonBlank(context, "s3.secret-access-key", "S3_SECRET_ACCESS_KEY");
        String bucket = firstNonBlank(context, "s3.bucket-name", "S3_BUCKET");
        
        boolean hasRequiredVars = hasText(accessKeyId)
            && hasText(secretAccessKey)
            && hasText(bucket);
        
        // Only log the message once to avoid spam during startup
        if (messageLogged.compareAndSet(false, true)) {
            if (hasRequiredVars) {
                logger.info("S3 environment variables detected - enabling S3 services (bucket: {})", bucket);
            } else {
                logger.error("S3 environment variables MISSING - S3 services DISABLED");
                logger.error("Required: s3.access-key-id/S3_ACCESS_KEY_ID, s3.secret-access-key/S3_SECRET_ACCESS_KEY, s3.bucket-name/S3_BUCKET");
                logger.error("Current status: S3_ACCESS_KEY_ID={}, S3_SECRET_ACCESS_KEY={}, S3_BUCKET={}",
                    (hasText(accessKeyId) ? STATUS_SET : STATUS_MISSING),
                    (hasText(secretAccessKey) ? STATUS_SET : STATUS_MISSING),
                    (hasText(bucket) ? STATUS_SET : STATUS_MISSING));
                logger.error("All cover uploads to S3 will fail");
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
