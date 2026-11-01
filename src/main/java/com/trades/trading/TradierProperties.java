package com.trades.trading;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Tradier credentials for both environments. {@code environment} is the one active at startup
 * ({@code SANDBOX} by default); it can be switched at runtime through {@link TradierEnvironment}.
 */
@ConfigurationProperties(prefix = "tradier")
public record TradierProperties(String accountId, String token, String environment,
                                String liveAccountId, String liveToken) {
    public static final String SANDBOX = "SANDBOX";
    public static final String LIVE = "LIVE";

    @ConstructorBinding
    public TradierProperties {
        environment = normalize(environment);
    }

    public TradierProperties(String accountId, String token) {
        this(accountId, token, SANDBOX, null, null);
    }

    /** Uses the same credentials for live; intended for tests. */
    TradierProperties(String accountId, String token, String environment) {
        this(accountId, token, environment, accountId, token);
    }

    static String normalize(String environment) {
        String normalized = environment == null || environment.isBlank()
                ? SANDBOX : environment.trim().toUpperCase(Locale.ROOT);
        if (!SANDBOX.equals(normalized) && !LIVE.equals(normalized)) {
            throw new IllegalArgumentException("Tradier environment must be SANDBOX or LIVE.");
        }
        return normalized;
    }
}
