-- Promotions as stores see them: what goes on the shelf label, which products, which stores, when.
-- Table and column names follow Spring Data JDBC's defaults (class Promotion -> table promotion,
-- validFrom -> valid_from), so the entity classes need no mapping annotations.

CREATE TABLE promotion (
    id           uuid         PRIMARY KEY,
    title        text         NOT NULL,  -- product (group) as printed, e.g. 'Alle kipfilet'
    label        text         NOT NULL,  -- mechanism as printed on the shelf label, e.g. '1+1 gratis'
    kind         text         NOT NULL,
    percent      numeric(5, 2),          -- percent_off: e.g. 25.00
    price_cents  integer,                -- promo_price: per pack; x_for_price: bundle total
    buy          smallint,               -- buy_x_pay_y: packs you get; x_for_price: packs in the bundle
    pay          smallint,               -- buy_x_pay_y: packs you pay for
    valid_from   date         NOT NULL,  -- first day, inclusive
    valid_until  date         NOT NULL,  -- last day, inclusive
    status       text         NOT NULL DEFAULT 'SCHEDULED',
    version      integer      NOT NULL DEFAULT 0,  -- optimistic locking (@Version)
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT promotion_kind_valid CHECK (
        kind IN ('percent_off', 'promo_price', 'buy_x_pay_y', 'x_for_price', 'second_half_price', 'other')
    ),
    -- Only states someone decides. Whether a promotion is running follows from the dates,
    -- so it isn't stored and can't go stale.
    CONSTRAINT promotion_status_valid CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    CONSTRAINT promotion_dates_ordered CHECK (valid_until >= valid_from),
    -- Each kind needs its own fields. The IS NOT NULL checks matter: a CHECK whose result is
    -- NULL passes, so `percent > 0` alone would let a percent_off promotion without a percent through.
    CONSTRAINT promotion_kind_fields CHECK (
        CASE kind
            WHEN 'percent_off' THEN percent IS NOT NULL AND percent > 0 AND percent < 100
            WHEN 'promo_price' THEN price_cents IS NOT NULL AND price_cents > 0
            WHEN 'buy_x_pay_y' THEN buy IS NOT NULL AND pay IS NOT NULL AND pay > 0 AND buy > pay
            WHEN 'x_for_price' THEN buy IS NOT NULL AND price_cents IS NOT NULL AND buy >= 1 AND price_cents > 0
            ELSE true
        END
    )
);

-- Stores and products are owned by other systems, so these hold their ids without foreign keys.
-- "At least one store / product" is a rule of the Promotion aggregate, enforced in code.

CREATE TABLE promotion_store (
    promotion_id  uuid  NOT NULL REFERENCES promotion (id) ON DELETE CASCADE,
    store_id      text  NOT NULL,
    PRIMARY KEY (promotion_id, store_id)
);

CREATE TABLE promotion_product (
    promotion_id  uuid  NOT NULL REFERENCES promotion (id) ON DELETE CASCADE,
    product_id    text  NOT NULL,
    PRIMARY KEY (promotion_id, product_id)
);
