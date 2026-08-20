package io.saasforge.iam.domain.session;

public interface AccessTokenIssuanceRepository {
    void create(AccessTokenIssuance issuance);
}
