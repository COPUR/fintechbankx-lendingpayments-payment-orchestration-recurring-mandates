-- svc-pay-recurring-mandates: DPoP proof replay cache (RFC 9449 jti), shared by all pods.
-- Rows expire after the proof's acceptance window and are purged by the service.
CREATE TABLE dpop_proof_jti (
    jkt        VARCHAR(64)  NOT NULL,
    jti        VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_dpop_proof_jti PRIMARY KEY (jkt, jti)
);

CREATE INDEX ix_dpop_proof_jti_expires_at ON dpop_proof_jti (expires_at);

COMMENT ON TABLE dpop_proof_jti IS 'DPoP proof ids already accepted, per key thumbprint (cnf.jkt); replay protection only.';
