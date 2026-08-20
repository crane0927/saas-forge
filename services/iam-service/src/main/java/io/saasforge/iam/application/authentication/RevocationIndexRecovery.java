package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;

public final class RevocationIndexRecovery implements InitializingBean {
    private final RevocationIndex index;
    private final AccessTokenIssuanceRepository issuances;
    private final Clock clock;

    public RevocationIndexRecovery(
            RevocationIndex index, AccessTokenIssuanceRepository issuances, Clock clock) {
        this.index = index;
        this.issuances = issuances;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        recover();
    }

    /** Ready 必须先失效；任一步失败都会让验证方继续 fail closed。 */
    @Scheduled(fixedDelayString = "${security.revocation-index.recovery-delay:PT5S}")
    public void recoverIfNeeded() {
        if (!index.isReady()) {
            recover();
        }
    }

    public void recover() {
        index.markNotReady();
        var now = clock.instant();
        index.rebuild(issuances.findUnexpiredRevocations(now), now);
    }
}
