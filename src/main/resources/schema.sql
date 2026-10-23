CREATE TABLE IF NOT EXISTS ivTrackedSymbols (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS ivDailySamples (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ivTrackedSymbolsFk INT NOT NULL,
    observationDate DATE NOT NULL,
    impliedVolatility DECIMAL(10, 6) NOT NULL,
    CONSTRAINT uqIvDailySamplesTrackedSymbolDate
        UNIQUE (ivTrackedSymbolsFk, observationDate),
    CONSTRAINT fkIvDailySamplesIvTrackedSymbols
        FOREIGN KEY (ivTrackedSymbolsFk) REFERENCES ivTrackedSymbols(pk)
        ON DELETE CASCADE
);

CREATE OR REPLACE VIEW ivHistory AS
SELECT
    tracked.pk AS symbolPk,
    tracked.symbol,
    sample.pk AS samplePk,
    sample.ivTrackedSymbolsFk,
    sample.observationDate,
    sample.impliedVolatility
FROM ivTrackedSymbols AS tracked
INNER JOIN ivDailySamples AS sample
    ON sample.ivTrackedSymbolsFk = tracked.pk;

CREATE TABLE IF NOT EXISTS spreadTypes (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    description VARCHAR(60) NOT NULL
);

CREATE TABLE IF NOT EXISTS orderTypes (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(10) NOT NULL UNIQUE,
    description VARCHAR(60) NOT NULL
);

CREATE TABLE IF NOT EXISTS optionTypes (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(4) NOT NULL UNIQUE,
    description VARCHAR(60) NOT NULL
);

CREATE TABLE IF NOT EXISTS openingActions (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    description VARCHAR(60) NOT NULL
);

CREATE TABLE IF NOT EXISTS orderStatuses (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(10) NOT NULL UNIQUE,
    description VARCHAR(60) NOT NULL
);

CREATE TABLE IF NOT EXISTS tradeEnvironments (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    description VARCHAR(60) NOT NULL
);

CREATE TABLE IF NOT EXISTS orderEventTypes (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    description VARCHAR(60) NOT NULL
);

INSERT IGNORE INTO spreadTypes (code, description) VALUES
    ('SINGLE_LEG', 'Single option'),
    ('VERTICAL', 'Vertical spread'),
    ('IRON_CONDOR', 'Iron condor'),
    ('BUTTERFLY', 'Butterfly'),
    ('CALENDAR', 'Calendar spread'),
    ('DIAGONAL', 'Diagonal spread'),
    ('DOUBLE_DIAGONAL', 'Double diagonal spread'),
    ('IRON_BUTTERFLY', 'Iron butterfly'),
    ('STRADDLE', 'Straddle'),
    ('STRANGLE', 'Strangle'),
    ('CUSTOM', 'Other or unrecognized combination');
INSERT IGNORE INTO orderTypes (code, description) VALUES
    ('DEBIT', 'Net debit'), ('CREDIT', 'Net credit'), ('EVEN', 'Even');
INSERT IGNORE INTO optionTypes (code, description) VALUES ('CALL', 'Call'), ('PUT', 'Put');
INSERT IGNORE INTO openingActions (code, description) VALUES
    ('BUY_TO_OPEN', 'Buy to open'), ('SELL_TO_OPEN', 'Sell to open'),
    ('BUY_TO_CLOSE', 'Buy to close'), ('SELL_TO_CLOSE', 'Sell to close');
INSERT IGNORE INTO orderStatuses (code, description) VALUES
    ('OPEN', 'Open'), ('FILLED', 'Filled'), ('CANCELLED', 'Cancelled');
INSERT IGNORE INTO tradeEnvironments (code, description) VALUES ('SANDBOX', 'Tradier sandbox');
INSERT IGNORE INTO orderEventTypes (code, description) VALUES
    ('PREVIEW', 'Order preview'), ('SUBMIT', 'Order submission'),
    ('CANCEL', 'Order cancellation'), ('CLOSE', 'Order close'), ('STATUS', 'Order status poll');

CREATE TABLE IF NOT EXISTS tradeOrders (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ivTrackedSymbolsFk INT NOT NULL,
    spreadTypesFk INT NOT NULL,
    orderTypesFk INT NOT NULL,
    orderStatusesFk INT NOT NULL,
    tradeEnvironmentsFk INT NOT NULL,
    tradierOrderId VARCHAR(40) NULL,
    tradierPartnerId VARCHAR(60) NULL,
    limitPrice DECIMAL(12, 4) NOT NULL,
    submittedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelledAt TIMESTAMP NULL,
    CONSTRAINT fkTradeOrdersIvTrackedSymbols
        FOREIGN KEY (ivTrackedSymbolsFk) REFERENCES ivTrackedSymbols(pk),
    CONSTRAINT fkTradeOrdersSpreadTypes FOREIGN KEY (spreadTypesFk) REFERENCES spreadTypes(pk),
    CONSTRAINT fkTradeOrdersOrderTypes FOREIGN KEY (orderTypesFk) REFERENCES orderTypes(pk),
    CONSTRAINT fkTradeOrdersOrderStatuses FOREIGN KEY (orderStatusesFk) REFERENCES orderStatuses(pk),
    CONSTRAINT fkTradeOrdersTradeEnvironments
        FOREIGN KEY (tradeEnvironmentsFk) REFERENCES tradeEnvironments(pk)
);

ALTER TABLE tradeOrders ADD COLUMN IF NOT EXISTS tradierStatus VARCHAR(30) NULL;
ALTER TABLE tradeOrders ADD COLUMN IF NOT EXISTS filledAt TIMESTAMP NULL;
ALTER TABLE tradeOrders ADD COLUMN IF NOT EXISTS statusCheckedAt TIMESTAMP NULL;

CREATE TABLE IF NOT EXISTS tradeOrderLegs (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tradeOrdersFk INT NOT NULL,
    legIndex INT NOT NULL,
    optionTypesFk INT NOT NULL,
    openingActionsFk INT NOT NULL,
    expiration DATE NOT NULL,
    strike DECIMAL(12, 3) NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT uqTradeOrderLegsOrderIndex UNIQUE (tradeOrdersFk, legIndex),
    CONSTRAINT fkTradeOrderLegsTradeOrders
        FOREIGN KEY (tradeOrdersFk) REFERENCES tradeOrders(pk)
        ON DELETE CASCADE,
    CONSTRAINT fkTradeOrderLegsOptionTypes FOREIGN KEY (optionTypesFk) REFERENCES optionTypes(pk),
    CONSTRAINT fkTradeOrderLegsOpeningActions FOREIGN KEY (openingActionsFk) REFERENCES openingActions(pk)
);

CREATE TABLE IF NOT EXISTS tradeOrderEvents (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ivTrackedSymbolsFk INT NOT NULL,
    tradeOrdersFk INT NULL,
    orderEventTypesFk INT NOT NULL,
    previewId VARCHAR(40) NULL,
    succeeded BOOLEAN NOT NULL,
    endpoint VARCHAR(200) NOT NULL,
    tradierOrderId VARCHAR(40) NULL,
    tradierPartnerId VARCHAR(60) NULL,
    requestJson TEXT NULL,
    responseJson TEXT NULL,
    sentAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fkTradeOrderEventsIvTrackedSymbols
        FOREIGN KEY (ivTrackedSymbolsFk) REFERENCES ivTrackedSymbols(pk),
    CONSTRAINT fkTradeOrderEventsTradeOrders
        FOREIGN KEY (tradeOrdersFk) REFERENCES tradeOrders(pk)
        ON DELETE CASCADE,
    CONSTRAINT fkTradeOrderEventsOrderEventTypes
        FOREIGN KEY (orderEventTypesFk) REFERENCES orderEventTypes(pk)
);

CREATE OR REPLACE VIEW tradeOrderSummary AS
SELECT
    o.pk AS orderPk,
    s.pk AS symbolPk,
    s.symbol,
    st.code AS spreadType,
    st.description AS spreadName,
    ot.code AS orderType,
    os.code AS status,
    env.code AS environment,
    o.limitPrice,
    o.tradierOrderId,
    o.tradierPartnerId,
    o.submittedAt,
    o.tradierStatus,
    o.filledAt,
    o.cancelledAt,
    (SELECT COUNT(*) FROM tradeOrderLegs l WHERE l.tradeOrdersFk = o.pk) AS legCount,
    (SELECT COUNT(*) FROM tradeOrderEvents e WHERE e.tradeOrdersFk = o.pk) AS eventCount
FROM tradeOrders AS o
INNER JOIN ivTrackedSymbols AS s ON s.pk = o.ivTrackedSymbolsFk
INNER JOIN spreadTypes AS st ON st.pk = o.spreadTypesFk
INNER JOIN orderTypes AS ot ON ot.pk = o.orderTypesFk
INNER JOIN orderStatuses AS os ON os.pk = o.orderStatusesFk
INNER JOIN tradeEnvironments AS env ON env.pk = o.tradeEnvironmentsFk;

CREATE OR REPLACE VIEW tradeOrderLegDetails AS
SELECT
    o.orderPk,
    o.symbol,
    o.spreadType,
    o.spreadName,
    o.orderType,
    o.status,
    o.submittedAt,
    l.legIndex,
    opt.code AS optionType,
    act.code AS action,
    l.expiration,
    l.strike,
    l.quantity
FROM tradeOrderSummary AS o
INNER JOIN tradeOrderLegs AS l ON l.tradeOrdersFk = o.orderPk
INNER JOIN optionTypes AS opt ON opt.pk = l.optionTypesFk
INNER JOIN openingActions AS act ON act.pk = l.openingActionsFk;

CREATE OR REPLACE VIEW tradeOrderEventLog AS
SELECT
    e.pk AS eventPk,
    s.symbol,
    o.orderPk,
    o.spreadType,
    o.spreadName,
    et.code AS eventType,
    e.previewId,
    e.succeeded,
    e.endpoint,
    e.tradierOrderId,
    e.tradierPartnerId,
    e.requestJson,
    e.responseJson,
    e.sentAt
FROM tradeOrderEvents AS e
INNER JOIN ivTrackedSymbols AS s ON s.pk = e.ivTrackedSymbolsFk
INNER JOIN orderEventTypes AS et ON et.pk = e.orderEventTypesFk
LEFT JOIN tradeOrderSummary AS o ON o.orderPk = e.tradeOrdersFk
WHERE et.code NOT IN ('PREVIEW', 'STATUS');

CREATE OR REPLACE VIEW symbolSpreadSummary AS
SELECT
    symbol,
    spreadType,
    spreadName,
    COUNT(*) AS orderCount,
    SUM(CASE WHEN status = 'OPEN' THEN 1 ELSE 0 END) AS openCount,
    SUM(CASE WHEN status = 'FILLED' THEN 1 ELSE 0 END) AS filledCount,
    SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelledCount,
    MIN(submittedAt) AS firstSubmittedAt,
    MAX(submittedAt) AS lastSubmittedAt
FROM tradeOrderSummary
GROUP BY symbol, spreadType, spreadName;

CREATE TABLE IF NOT EXISTS tradingJournalLegs (
    pk INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tradierOrderId VARCHAR(40) NOT NULL,
    tradierLegId VARCHAR(40) NULL,
    legIndex INT NOT NULL,
    ivTrackedSymbolsFk INT NOT NULL,
    spreadTypesFk INT NOT NULL,
    optionTypesFk INT NULL,
    openingActionsFk INT NULL,
    tradeEnvironmentsFk INT NOT NULL,
    orderClass VARCHAR(20) NOT NULL,
    orderKind VARCHAR(20) NULL,
    orderStatus VARCHAR(30) NULL,
    orderDuration VARCHAR(10) NULL,
    orderQuantity DECIMAL(16, 4) NULL,
    orderPrice DECIMAL(14, 4) NULL,
    orderAvgFillPrice DECIMAL(14, 4) NULL,
    orderExecQuantity DECIMAL(16, 4) NULL,
    orderCreatedAt TIMESTAMP NULL,
    orderTransactionAt TIMESTAMP NULL,
    side VARCHAR(20) NULL,
    optionSymbol VARCHAR(30) NULL,
    expiration DATE NULL,
    strike DECIMAL(12, 3) NULL,
    legStatus VARCHAR(30) NULL,
    legQuantity DECIMAL(16, 4) NULL,
    legAvgFillPrice DECIMAL(14, 4) NULL,
    legExecQuantity DECIMAL(16, 4) NULL,
    legRemainingQuantity DECIMAL(16, 4) NULL,
    legJson TEXT NOT NULL,
    importedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uqTradingJournalLegsOrderLeg UNIQUE (tradierOrderId, legIndex),
    CONSTRAINT fkTradingJournalLegsIvTrackedSymbols
        FOREIGN KEY (ivTrackedSymbolsFk) REFERENCES ivTrackedSymbols(pk),
    CONSTRAINT fkTradingJournalLegsSpreadTypes FOREIGN KEY (spreadTypesFk) REFERENCES spreadTypes(pk),
    CONSTRAINT fkTradingJournalLegsOptionTypes FOREIGN KEY (optionTypesFk) REFERENCES optionTypes(pk),
    CONSTRAINT fkTradingJournalLegsOpeningActions FOREIGN KEY (openingActionsFk) REFERENCES openingActions(pk),
    CONSTRAINT fkTradingJournalLegsTradeEnvironments
        FOREIGN KEY (tradeEnvironmentsFk) REFERENCES tradeEnvironments(pk)
);

CREATE OR REPLACE VIEW tradingJournalLegDetails AS
SELECT
    l.tradierOrderId,
    l.legIndex,
    l.tradierLegId,
    s.symbol,
    st.code AS spreadType,
    st.description AS spreadName,
    l.orderClass,
    l.orderStatus,
    l.side,
    opt.code AS optionType,
    act.code AS action,
    l.optionSymbol,
    l.expiration,
    l.strike,
    l.legStatus,
    l.legQuantity,
    l.legAvgFillPrice,
    l.legExecQuantity,
    l.legRemainingQuantity,
    l.orderCreatedAt
FROM tradingJournalLegs AS l
INNER JOIN ivTrackedSymbols AS s ON s.pk = l.ivTrackedSymbolsFk
INNER JOIN spreadTypes AS st ON st.pk = l.spreadTypesFk
LEFT JOIN optionTypes AS opt ON opt.pk = l.optionTypesFk
LEFT JOIN openingActions AS act ON act.pk = l.openingActionsFk;

CREATE OR REPLACE VIEW tradingJournalOrders AS
SELECT
    l.tradierOrderId,
    s.symbol,
    st.code AS spreadType,
    st.description AS spreadName,
    MAX(l.orderClass) AS orderClass,
    MAX(l.orderKind) AS orderKind,
    MAX(l.orderStatus) AS orderStatus,
    MAX(l.orderQuantity) AS orderQuantity,
    MAX(l.orderPrice) AS orderPrice,
    MAX(l.orderAvgFillPrice) AS orderAvgFillPrice,
    MAX(l.orderExecQuantity) AS orderExecQuantity,
    MAX(l.orderCreatedAt) AS orderCreatedAt,
    MAX(l.orderTransactionAt) AS orderTransactionAt,
    COUNT(*) AS legCount,
    MIN(l.expiration) AS firstExpiration,
    MAX(l.expiration) AS lastExpiration
FROM tradingJournalLegs AS l
INNER JOIN ivTrackedSymbols AS s ON s.pk = l.ivTrackedSymbolsFk
INNER JOIN spreadTypes AS st ON st.pk = l.spreadTypesFk
GROUP BY l.tradierOrderId, s.symbol, st.code, st.description;
