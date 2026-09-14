package io.saasforge.iam.application.signing;

/**
 * JWT 签名基础设施的最小出站端口。
 *
 * <p>调用方只传入服务内保存的 KMS Key Version 引用、固定算法和 JWS Signing Input；适配器不得要求
 * IAM 核心了解云厂商项目、区域或请求模型。</p>
 */
public interface JwtSigningPort {

    byte[] sign(String kmsKeyVersionRef, JwtSigningAlgorithm algorithm, JwsSigningInput signingInput);
}
