CREATE TABLE products (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    sku TEXT NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    price_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,

    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT chk_products_sku CHECK (btrim(sku) <> ''),
    CONSTRAINT chk_products_name CHECK (btrim(name) <> ''),
    CONSTRAINT chk_products_price_minor CHECK (price_minor > 0)
);

CREATE TABLE stocks (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL,
    on_hand BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ,

    CONSTRAINT pk_stocks PRIMARY KEY (id),
    CONSTRAINT uq_stocks_product UNIQUE (product_id),
    CONSTRAINT fk_stocks_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_stocks_on_hand CHECK (on_hand >= 0),
    CONSTRAINT chk_stocks_reserved CHECK (reserved >= 0),
    CONSTRAINT chk_stocks_available CHECK (reserved <= on_hand)
);

CREATE TABLE reservations (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    request_hash TEXT NOT NULL,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,

    CONSTRAINT pk_reservations PRIMARY KEY (id),
    CONSTRAINT uq_reservations_order UNIQUE (order_id),
    CONSTRAINT chk_reservations_status CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED', 'REJECTED'))
);

CREATE TABLE reservation_items (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    reservation_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_reservation_items PRIMARY KEY (id),
    CONSTRAINT uq_reservation_items_reservation_product UNIQUE (reservation_id, product_id),
    CONSTRAINT fk_reservation_items_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id),
    CONSTRAINT fk_reservation_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_reservation_items_quantity CHECK (quantity > 0)
);
