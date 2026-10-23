package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Component
public class TradierSandboxClient implements TradierGateway {
    private static final String SANDBOX_URL = "https://sandbox.tradier.com/v1";
    private static final DateTimeFormatter OPTION_EXPIRATION = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter TIMESALES_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId MARKET_TIME_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    private final RestClient restClient;
    private final TradierProperties properties;
    private final Clock clock;

    @Autowired
    public TradierSandboxClient(RestClient.Builder builder, TradierProperties properties) {
        this(builder, properties, Clock.system(MARKET_TIME_ZONE));
    }

    TradierSandboxClient(RestClient.Builder builder, TradierProperties properties, Clock clock) {
        this.restClient = builder.baseUrl(SANDBOX_URL).build();
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<SymbolSuggestion> symbols(String query) {
        requireApiToken();
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/markets/lookup")
                        .queryParam("q", query.toUpperCase(Locale.ROOT))
                        .build())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode securityNode = response == null ? null : response.path("securities").path("security");
        if (securityNode == null || securityNode.isMissingNode() || securityNode.isNull()) {
            return List.of();
        }
        List<JsonNode> securities = new ArrayList<>();
        if (securityNode.isArray()) {
            securityNode.forEach(securities::add);
        } else if (securityNode.isObject()) {
            securities.add(securityNode);
        } else {
            throw new TradierRequestException("Tradier returned an invalid symbol lookup response.");
        }
        return securities.stream()
                .map(this::toSymbolSuggestion)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator
                        .comparing((SymbolSuggestion suggestion) -> !suggestion.symbol().equalsIgnoreCase(query))
                        .thenComparingInt(suggestion -> suggestion.symbol().length()))
                .limit(50)
                .toList();
    }

    @Override
    public List<LocalDate> expirations(String symbol) {
        requireApiToken();
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/markets/options/expirations")
                        .queryParam("symbol", symbol.toUpperCase(Locale.ROOT))
                        .queryParam("includeAllRoots", "false")
                        .queryParam("strikes", "false")
                        .build())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode dateNode = response == null ? null : response.path("expirations").path("date");
        if (dateNode == null || dateNode.isMissingNode() || dateNode.isNull()) {
            throw new TradierRequestException("Tradier returned no option expirations for this symbol.");
        }

        TreeSet<LocalDate> expirations = new TreeSet<>();
        if (dateNode.isArray()) {
            dateNode.forEach(node -> addExpiration(node, expirations));
        } else if (dateNode.isTextual()) {
            addExpiration(dateNode, expirations);
        } else {
            throw new TradierRequestException("Tradier returned an invalid option expiration response.");
        }
        expirations.removeIf(expiration -> expiration.isBefore(LocalDate.now()));
        if (expirations.isEmpty()) {
            throw new TradierRequestException("Tradier returned no current or future option expirations for this symbol.");
        }
        return List.copyOf(expirations);
    }

    @Override
    public List<BigDecimal> strikes(String symbol, LocalDate expiration, OptionType optionType) {
        requireApiToken();
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/markets/options/chains")
                        .queryParam("symbol", symbol.toUpperCase(Locale.ROOT))
                        .queryParam("expiration", expiration)
                        .queryParam("greeks", "false")
                        .build())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode optionNode = response == null ? null : response.path("options").path("option");
        if (optionNode == null || optionNode.isMissingNode() || optionNode.isNull()) {
            throw new TradierRequestException("Tradier returned no options for the selected expiration.");
        }

        TreeSet<BigDecimal> strikes = new TreeSet<>();
        if (optionNode.isArray()) {
            optionNode.forEach(node -> addStrike(node, optionType, strikes));
        } else if (optionNode.isObject()) {
            addStrike(optionNode, optionType, strikes);
        } else {
            throw new TradierRequestException("Tradier returned an invalid option chain response.");
        }
        if (strikes.isEmpty()) {
            throw new TradierRequestException("Tradier returned no strikes for the selected option type and expiration.");
        }
        return List.copyOf(strikes);
    }

    @Override
    public BigDecimal underlyingPrice(String symbol) {
        requireApiToken();
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/markets/quotes")
                        .queryParam("symbols", symbol.toUpperCase(Locale.ROOT))
                        .build())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode quoteNode = response == null ? null : response.path("quotes").path("quote");
        if (quoteNode != null && quoteNode.isArray()) {
            quoteNode = quoteNode.isEmpty() ? null : quoteNode.get(0);
        }
        if (quoteNode == null || !quoteNode.isObject()) {
            throw new TradierRequestException("Tradier returned no underlying quote.");
        }
        JsonNode lastNode = quoteNode.path("last");
        if (lastNode.isNumber() && lastNode.decimalValue().signum() > 0) {
            return lastNode.decimalValue();
        }
        JsonNode bidNode = quoteNode.path("bid");
        JsonNode askNode = quoteNode.path("ask");
        if (bidNode.isNumber() && askNode.isNumber()
                && bidNode.decimalValue().signum() > 0
                && askNode.decimalValue().compareTo(bidNode.decimalValue()) >= 0) {
            return bidNode.decimalValue().add(askNode.decimalValue())
                    .divide(BigDecimal.valueOf(2), 6, java.math.RoundingMode.HALF_UP);
        }
        throw new TradierRequestException("Tradier returned no usable underlying price.");
    }

    @Override
    public UnderlyingQuote underlyingQuote(String symbol) {
        requireApiToken();
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/markets/quotes")
                        .queryParam("symbols", symbol.toUpperCase(Locale.ROOT))
                        .build())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode quoteNode = response == null ? null : response.path("quotes").path("quote");
        if (quoteNode != null && quoteNode.isArray()) {
            quoteNode = quoteNode.isEmpty() ? null : quoteNode.get(0);
        }
        if (quoteNode == null || !quoteNode.isObject()) {
            throw new TradierRequestException("Tradier returned no underlying quote.");
        }
        return new UnderlyingQuote(decimalOrNull(quoteNode.path("bid")),
                decimalOrNull(quoteNode.path("ask")), decimalOrNull(quoteNode.path("last")));
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    @Override
    public BigDecimal underlyingThirtyDayAtmIv(String symbol) {
        requireApiToken();
        BigDecimal underlying = underlyingPrice(symbol);
        LocalDate today = LocalDate.now(clock);
        LocalDate expiration = expirations(symbol).stream()
                .min(java.util.Comparator.comparingLong(date ->
                        Math.abs(ChronoUnit.DAYS.between(today, date) - 30)))
                .orElseThrow(() -> new TradierRequestException(
                        "Tradier returned no expirations for the underlying IV calculation."));
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/markets/options/chains")
                        .queryParam("symbol", symbol.toUpperCase(Locale.ROOT))
                        .queryParam("expiration", expiration)
                        .queryParam("greeks", "true")
                        .build())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode optionNode = response == null ? null : response.path("options").path("option");
        if (optionNode == null || optionNode.isMissingNode() || optionNode.isNull()) {
            throw new TradierRequestException("Tradier returned no option chain for the underlying IV calculation.");
        }
        List<JsonNode> options = new ArrayList<>();
        if (optionNode.isArray()) {
            optionNode.forEach(options::add);
        } else if (optionNode.isObject()) {
            options.add(optionNode);
        } else {
            throw new TradierRequestException("Tradier returned an invalid option chain response.");
        }
        JsonNode nearestWithIv = options.stream()
                .filter(node -> node.path("strike").isNumber()
                        && optionalGreek(node.path("greeks"), "mid_iv") != null
                        && optionalGreek(node.path("greeks"), "mid_iv").signum() > 0)
                .min(java.util.Comparator.comparing(node ->
                        node.path("strike").decimalValue().subtract(underlying).abs()))
                .orElseThrow(() -> new TradierRequestException(
                        "Tradier returned no usable ATM implied volatility for the underlying."));
        BigDecimal strike = nearestWithIv.path("strike").decimalValue();
        List<BigDecimal> atmVolatilities = options.stream()
                .filter(node -> node.path("strike").isNumber()
                        && node.path("strike").decimalValue().compareTo(strike) == 0)
                .map(node -> optionalGreek(node.path("greeks"), "mid_iv"))
                .filter(iv -> iv != null && iv.signum() > 0)
                .toList();
        return atmVolatilities.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(atmVolatilities.size()), 6, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public List<UnderlyingBar> underlyingTimesales(String symbol) {
        requireApiToken();
        LocalDateTime now = LocalDateTime.now(clock);
        if (!now.toLocalTime().isAfter(MARKET_OPEN)) {
            return List.of();
        }
        LocalDate date = now.toLocalDate();
        LocalDateTime start = date.atTime(MARKET_OPEN);
        LocalDateTime end = now.isAfter(date.atTime(MARKET_CLOSE))
                ? date.atTime(MARKET_CLOSE) : now.withSecond(0).withNano(0);
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/markets/timesales")
                        .queryParam("symbol", symbol.toUpperCase(Locale.ROOT))
                        .queryParam("interval", "1min")
                        .queryParam("start", TIMESALES_DATETIME.format(start))
                        .queryParam("end", TIMESALES_DATETIME.format(end))
                        .build())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode dataNode = response == null ? null : response.path("series").path("data");
        if (dataNode == null || dataNode.isMissingNode() || dataNode.isNull()) {
            return List.of();
        }
        List<JsonNode> bars = new ArrayList<>();
        if (dataNode.isArray()) {
            dataNode.forEach(bars::add);
        } else if (dataNode.isObject()) {
            bars.add(dataNode);
        } else {
            throw new TradierRequestException("Tradier returned an invalid intraday timesales response.");
        }
        return bars.stream().map(this::toUnderlyingBar).toList();
    }

    @Override
    public List<OptionQuote> quotes(TradeRequest trade) {
        requireApiToken();
        String optionSymbols = trade.legs().stream()
                .map(leg -> optionSymbol(trade.symbol(), leg))
                .distinct()
                .reduce((first, second) -> first + "," + second)
                .orElseThrow(() -> new IllegalArgumentException("At least one option leg is required."));
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/markets/quotes")
                        .queryParam("symbols", optionSymbols)
                        .queryParam("greeks", "true")
                        .build())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode quoteNode = response == null ? null : response.path("quotes").path("quote");
        List<OptionQuote> quotes = new ArrayList<>();
        if (quoteNode == null || quoteNode.isMissingNode() || quoteNode.isNull()) {
            throw new TradierRequestException("Tradier returned no option quotes.");
        }
        if (quoteNode.isArray()) {
            quoteNode.forEach(node -> quotes.add(toQuote(node)));
        } else if (quoteNode.isObject()) {
            quotes.add(toQuote(quoteNode));
        } else {
            throw new TradierRequestException("Tradier returned an invalid option quote response.");
        }
        return quotes;
    }

    @Override
    public JsonNode preview(TradeRequest trade) {
        return place(trade, true);
    }

    @Override
    public java.util.Map<String, List<String>> previewRequest(TradeRequest trade) {
        return new java.util.LinkedHashMap<>(toForm(trade, true));
    }

    @Override
    public JsonNode accountOrders() {
        requireOrderCredentials();
        JsonNode response = restClient.get()
                .uri("/accounts/{accountId}/orders?includeTags=true", properties.accountId())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.has("errors")) {
            throw new TradierRequestException("Tradier did not return the account orders.");
        }
        return response;
    }

    @Override
    public JsonNode orderStatus(String orderId) {
        requireOrderCredentials();
        JsonNode response = restClient.get()
                .uri("/accounts/{accountId}/orders/{orderId}", properties.accountId(), orderId)
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.has("errors") || !response.has("order")) {
            throw new TradierRequestException("Tradier did not return the order status.");
        }
        return response;
    }

    @Override
    public JsonNode submit(TradeRequest trade) {
        return place(trade, false);
    }

    private JsonNode place(TradeRequest trade, boolean preview) {
        requireOrderCredentials();

        MultiValueMap<String, String> form = toForm(trade, preview);
        JsonNode response = restClient.post()
                .uri("/accounts/{accountId}/orders", properties.accountId())
                .headers(headers -> headers.setBearerAuth(Objects.requireNonNull(properties.token())))
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_FORM_URLENCODED))
                .accept(MediaType.APPLICATION_JSON)
                .body(Objects.requireNonNull(form))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.has("errors")) {
            throw new TradierRequestException("Tradier did not accept the sandbox order request.");
        }
        return response;
    }

    private MultiValueMap<String, String> toForm(TradeRequest trade, boolean preview) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (trade.spreadType() == SpreadType.SINGLE_LEG) {
            OptionLeg leg = trade.legs().get(0);
            form.add("class", "option");
            form.add("symbol", trade.symbol().toUpperCase(Locale.ROOT));
            form.add("option_symbol", optionSymbol(trade.symbol(), leg));
            form.add("side", leg.action().tradierValue());
            form.add("quantity", Integer.toString(leg.quantity()));
            form.add("type", "limit");
            form.add("duration", "day");
            form.add("price", trade.limitPrice().toPlainString());
            form.add("preview", Boolean.toString(preview));
            return form;
        }
        form.add("class", "multileg");
        form.add("symbol", trade.symbol().toUpperCase(Locale.ROOT));
        form.add("type", trade.orderType().name().toLowerCase(Locale.ROOT));
        form.add("duration", "day");
        form.add("price", trade.limitPrice().toPlainString());
        form.add("preview", Boolean.toString(preview));
        int index = 0;
        for (OptionLeg leg : trade.legs()) {
            form.add("option_symbol[" + index + "]", optionSymbol(trade.symbol(), leg));
            form.add("side[" + index + "]", leg.action().tradierValue());
            form.add("quantity[" + index + "]", Integer.toString(leg.quantity()));
            index++;
        }
        return form;
    }

    private void requireApiToken() {
        if (properties.token() == null || properties.token().isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Tradier sandbox market data is not configured. Set TRADIER_ACCESS_TOKEN.");
        }
    }

    private void requireOrderCredentials() {
        requireApiToken();
        if (properties.accountId() == null || properties.accountId().isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Tradier sandbox order submission is not configured. Set TRADIER_ACCOUNT_ID.");
        }
        if (!properties.accountId().matches("[A-Za-z0-9-]+")) {
            throw new IllegalStateException("TRADIER_ACCOUNT_ID contains unsupported characters.");
        }
    }

    private void addExpiration(JsonNode node, TreeSet<LocalDate> expirations) {
        if (!node.isTextual()) {
            throw new TradierRequestException("Tradier returned an invalid option expiration.");
        }
        try {
            expirations.add(LocalDate.parse(node.asText()));
        } catch (java.time.DateTimeException exception) {
            throw new TradierRequestException("Tradier returned an invalid option expiration.");
        }
    }

    private SymbolSuggestion toSymbolSuggestion(JsonNode node) {
        String symbol = node.path("symbol").asText("").toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z][A-Z0-9.-]{0,9}")) {
            return null;
        }
        String description = node.path("description").asText(symbol);
        String type = node.path("type").asText("");
        return new SymbolSuggestion(symbol, description.isBlank() ? symbol : description, type);
    }

    private OptionQuote toQuote(JsonNode node) {
        String symbol = node.path("symbol").asText("");
        JsonNode bidNode = node.path("bid");
        JsonNode askNode = node.path("ask");
        if (symbol.isBlank() || !bidNode.isNumber() || !askNode.isNumber()) {
            throw new TradierRequestException("Tradier returned an option quote without a valid bid and ask.");
        }
        BigDecimal bid = bidNode.decimalValue();
        BigDecimal ask = askNode.decimalValue();
        if (bid.signum() < 0 || ask.signum() <= 0 || bid.compareTo(ask) > 0) {
            throw new TradierRequestException("Tradier returned an unusable or crossed option market.");
        }
        JsonNode openInterestNode = node.path("open_interest");
        Integer openInterest = null;
        if (!openInterestNode.isMissingNode() && !openInterestNode.isNull()) {
            if (!openInterestNode.canConvertToInt() || openInterestNode.intValue() < 0) {
                throw new TradierRequestException("Tradier returned an invalid option open interest.");
            }
            openInterest = openInterestNode.intValue();
        }
        JsonNode greeksNode = node.path("greeks");
        BigDecimal delta = optionalGreek(greeksNode, "delta");
        BigDecimal gamma = optionalGreek(greeksNode, "gamma");
        BigDecimal theta = optionalGreek(greeksNode, "theta");
        BigDecimal vega = optionalGreek(greeksNode, "vega");
        BigDecimal rho = optionalGreek(greeksNode, "rho");
        BigDecimal impliedVolatility = optionalGreek(greeksNode, "mid_iv");
        if (delta != null && delta.abs().compareTo(BigDecimal.ONE) > 0) {
            throw new TradierRequestException("Tradier returned an invalid option delta.");
        }
        if (gamma != null && gamma.signum() < 0) {
            throw new TradierRequestException("Tradier returned an invalid option gamma.");
        }
        if (impliedVolatility != null && impliedVolatility.signum() < 0) {
            throw new TradierRequestException("Tradier returned an invalid option implied volatility.");
        }
        if (impliedVolatility != null && impliedVolatility.signum() == 0) {
            impliedVolatility = null;
        }
        return new OptionQuote(symbol, bid, ask, openInterest, delta, gamma, theta, vega, rho,
                impliedVolatility);
    }

    private UnderlyingBar toUnderlyingBar(JsonNode node) {
        String time = node.path("time").asText("");
        JsonNode openNode = node.path("open");
        JsonNode highNode = node.path("high");
        JsonNode lowNode = node.path("low");
        JsonNode closeNode = node.path("close");
        JsonNode volumeNode = node.path("volume");
        if (time.isBlank() || !openNode.isNumber() || !highNode.isNumber()
                || !lowNode.isNumber() || !closeNode.isNumber()
                || !volumeNode.isIntegralNumber() || !volumeNode.canConvertToLong()
                || volumeNode.longValue() < 0) {
            throw new TradierRequestException("Tradier returned an invalid intraday price bar.");
        }
        BigDecimal open = openNode.decimalValue();
        BigDecimal high = highNode.decimalValue();
        BigDecimal low = lowNode.decimalValue();
        BigDecimal close = closeNode.decimalValue();
        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0
                || high.compareTo(low) < 0 || open.compareTo(low) < 0 || open.compareTo(high) > 0
                || close.compareTo(low) < 0 || close.compareTo(high) > 0) {
            throw new TradierRequestException("Tradier returned an unusable intraday price bar.");
        }
        return new UnderlyingBar(time, open, high, low, close, volumeNode.longValue());
    }

    private BigDecimal optionalGreek(JsonNode greeksNode, String name) {
        JsonNode value = greeksNode.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new TradierRequestException("Tradier returned an invalid option " + name + ".");
        }
        return value.decimalValue();
    }

    private void addStrike(JsonNode node, OptionType optionType, TreeSet<BigDecimal> strikes) {
        if (!optionType.name().equalsIgnoreCase(node.path("option_type").asText())) {
            return;
        }
        JsonNode strikeNode = node.path("strike");
        if (!strikeNode.isNumber() || strikeNode.decimalValue().signum() <= 0) {
            throw new TradierRequestException("Tradier returned an option without a valid strike.");
        }
        strikes.add(strikeNode.decimalValue());
    }

    static String optionSymbol(String symbol, OptionLeg leg) {
        long strikeMilli = leg.strike().movePointRight(3).longValueExact();
        String root = symbol.toUpperCase(Locale.ROOT);
        if (root.length() > 6 || strikeMilli > 99_999_999) {
            throw new IllegalArgumentException("Underlying symbol or strike cannot be represented as an OCC option symbol.");
        }
        return "%s%s%s%08d".formatted(root, leg.expiration().format(OPTION_EXPIRATION),
                leg.optionType() == OptionType.CALL ? "C" : "P", strikeMilli);
    }
}
