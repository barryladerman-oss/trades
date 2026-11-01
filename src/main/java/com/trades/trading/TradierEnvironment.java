package com.trades.trading;

import org.springframework.stereotype.Component;

/** The Tradier environment currently in use, with its credentials. Switchable at runtime. */
@Component
public class TradierEnvironment {
    static final String SANDBOX_URL = "https://sandbox.tradier.com/v1";
    static final String LIVE_URL = "https://api.tradier.com/v1";

    private final TradierProperties config;
    private volatile String current;

    public TradierEnvironment(TradierProperties config) {
        this.config = config;
        if (TradierProperties.LIVE.equals(config.environment()) && !liveAvailable()) {
            throw new IllegalStateException(
                    "Starting in LIVE requires TRADIER_LIVE_ACCESS_TOKEN and TRADIER_LIVE_ACCOUNT_ID.");
        }
        this.current = config.environment();
    }

    public String environment() {
        return current;
    }

    public boolean live() {
        return TradierProperties.LIVE.equals(current);
    }

    public String token() {
        return live() ? config.liveToken() : config.token();
    }

    public String accountId() {
        return live() ? config.liveAccountId() : config.accountId();
    }

    public String baseUrl() {
        return live() ? LIVE_URL : SANDBOX_URL;
    }

    /** Whether live credentials (token and account number) are configured. */
    public boolean liveAvailable() {
        return notBlank(config.liveToken()) && notBlank(config.liveAccountId());
    }

    public synchronized void switchTo(String requested) {
        String target = TradierProperties.normalize(requested);
        if (TradierProperties.LIVE.equals(target) && !liveAvailable()) {
            throw new IllegalArgumentException(
                    "Live trading is not configured. Set TRADIER_LIVE_ACCESS_TOKEN and TRADIER_LIVE_ACCOUNT_ID "
                            + "and restart the app.");
        }
        current = target;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
