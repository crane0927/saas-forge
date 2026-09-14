package io.saasforge.entitlement.application.bootstrap;

import java.util.UUID;

/** 服务端保存的原始创建字段；不作为浏览器恢复材料返回。 */
public record PlanDraft(String code, String displayName, UUID quotaDefinitionId, Integer limit) { }
