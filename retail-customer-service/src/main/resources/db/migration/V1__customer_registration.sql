CREATE TABLE customer_account (
    customer_id uuid PRIMARY KEY,
    email varchar(254) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    CONSTRAINT customer_email_unique UNIQUE (email)
);

CREATE TABLE customer_profile (
    customer_id uuid PRIMARY KEY REFERENCES customer_account(customer_id),
    given_name varchar(80) NOT NULL,
    family_name varchar(80) NOT NULL
);

CREATE TABLE customer_preferences (
    customer_id uuid PRIMARY KEY REFERENCES customer_account(customer_id),
    locale varchar(35) NOT NULL DEFAULT 'en',
    marketing_opt_in boolean NOT NULL DEFAULT false,
    personalization_consent boolean NOT NULL DEFAULT false
);

CREATE TABLE customer_audit (
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES customer_account(customer_id),
    event_type varchar(32) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE INDEX customer_audit_customer_idx ON customer_audit (customer_id, audit_id);
