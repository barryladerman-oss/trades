package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/trades")
@Validated
public class TradeController {
    private final TradePreviewService service;
    private final TradeQuoteService quoteService;
    private final IvPercentileService ivPercentileService;
    private final TradeOrderService orderService;
    private final TradingJournalService journalService;

    public TradeController(TradePreviewService service, TradeQuoteService quoteService,
                           IvPercentileService ivPercentileService, TradeOrderService orderService,
                           TradingJournalService journalService) {
        this.service = service;
        this.quoteService = quoteService;
        this.ivPercentileService = ivPercentileService;
        this.orderService = orderService;
        this.journalService = journalService;
    }

    @PostMapping("/journal/import")
    public java.util.Map<String, Integer> importJournal() {
        return java.util.Map.of("legsImported", journalService.importOrders());
    }

    @GetMapping("/orders/all")
    public List<TradeOrderRecord> allOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        return orderService.between(start, end);
    }

    @GetMapping("/orders")
    public List<TradeOrderRecord> orders(
            @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String symbol) {
        return orderService.forSymbol(symbol);
    }

    @GetMapping("/symbols")
    public List<SymbolSuggestion> symbols(
            @RequestParam("q") @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String query) {
        return quoteService.symbols(query);
    }

    @GetMapping("/expirations")
    public List<LocalDate> expirations(
            @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String symbol) {
        return quoteService.expirations(symbol);
    }

    @GetMapping("/strikes")
    public List<BigDecimal> strikes(
            @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NotNull LocalDate expiration,
            @RequestParam @NotNull OptionType optionType) {
        if (expiration.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Option expiration cannot be in the past.");
        }
        return quoteService.strikes(symbol, expiration, optionType);
    }

    @GetMapping("/underlying-price")
    public BigDecimal underlyingPrice(
            @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String symbol) {
        return quoteService.underlyingPrice(symbol);
    }

    @GetMapping("/underlying-quote")
    public UnderlyingQuote underlyingQuote(
            @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String symbol) {
        return quoteService.underlyingQuote(symbol);
    }

    @GetMapping("/underlying-timesales")
    public List<UnderlyingBar> underlyingTimesales(
            @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String symbol,
            @RequestParam(defaultValue = "15") int interval) {
        return quoteService.underlyingTimesales(symbol, interval);
    }

    @GetMapping("/iv-percentile")
    public IvPercentileService.IvPercentile ivPercentile(
            @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String symbol) {
        return ivPercentileService.percentile(symbol);
    }

    @PostMapping("/quote")
    public TradeQuoteService.PositionQuote quote(@Valid @RequestBody TradeQuoteRequest request) {
        return quoteService.quote(request);
    }

    @PostMapping("/preview")
    public TradePreviewService.PreviewResult preview(@Valid @RequestBody TradeRequest trade) {
        return service.preview(trade);
    }

    @PostMapping("/submit")
    public JsonNode submit(@Valid @RequestBody SubmitRequest request) {
        return service.submit(request.previewId(), request.environment(), request.confirmed());
    }

    public record SubmitRequest(
            @NotBlank String previewId,
            @NotBlank String environment,
            @NotNull Boolean confirmed
    ) {
    }
}
