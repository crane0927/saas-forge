package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordSetupDeliveryRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordSetupNotificationQueryService {
    private final PasswordSetupDeliveryRepository deliveries;
    private final IdentityRepository identities;
    private final Clock clock;

    public PasswordSetupNotificationQueryService(PasswordSetupDeliveryRepository deliveries,
            IdentityRepository identities, Clock clock) {
        this.deliveries = deliveries;
        this.identities = identities;
        this.clock = clock;
    }

    /** 读取不签发 Challenge、不发送邮件；当前凭据决定是否仍适用 Password Setup。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public State get(UUID caller, UUID requestId, UUID identityId) {
        if (identities.findById(identityId).isEmpty()) throw new IllegalArgumentException("Identity not found");
        var delivery = deliveries.find(caller, requestId);
        if (delivery.isPresent() && !delivery.orElseThrow().identityId().equals(identityId)) {
            throw new PasswordSetupDeliveryRequestConflictException();
        }
        var credentials = identities.findCredentials(identityId);
        if (credentials.stream().anyMatch(value -> value.type() == CredentialType.PASSWORD
                && value.isValidAt(clock.instant()))) return State.PASSWORD_READY;
        if (!credentials.isEmpty()) return State.RECOVERY_REQUIRED;
        return delivery.map(value -> switch (value.status()) {
            case PENDING -> State.PENDING;
            case DELIVERED -> State.MAIL_SERVICE_ACCEPTED;
            // 历史已有密码结果不证明当前仍有可用凭据。
            case PASSWORD_READY -> State.RECOVERY_REQUIRED;
        }).orElse(State.NOT_REQUESTED);
    }

    public enum State { NOT_REQUESTED, PENDING, MAIL_SERVICE_ACCEPTED, PASSWORD_READY, RECOVERY_REQUIRED }
}
