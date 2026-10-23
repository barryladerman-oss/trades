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
