ALTER TABLE products
    ADD COLUMN sale_starts_at TIMESTAMPTZ,
    ADD COLUMN sale_ends_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_products_sale_window CHECK (
        sale_starts_at IS NULL OR sale_ends_at IS NULL OR sale_starts_at < sale_ends_at
    );
