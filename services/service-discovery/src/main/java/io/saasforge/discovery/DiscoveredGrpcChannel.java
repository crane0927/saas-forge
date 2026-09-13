package io.saasforge.discovery;

import com.alibaba.nacos.api.naming.pojo.Instance;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;

/**
 * 仅供 local profile 的明文内部 gRPC；每次调用确认注册表，端口取被调用实例的 grpc.port 元数据。
 * 复用当前连接，地址变化时关闭旧连接；无健康实例、发现异常或元数据缺失均失败关闭。
 */
public final class DiscoveredGrpcChannel extends Channel implements AutoCloseable {
    private final NacosServiceEndpoints endpoints;
    private final String serviceId;
    private ManagedChannel current;
    private String currentHost;
    private int currentPort;
    private boolean closed;

    public DiscoveredGrpcChannel(NacosServiceEndpoints endpoints, String serviceId) {
        this.endpoints = endpoints;
        this.serviceId = serviceId;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions options) {
        var limit = io.grpc.Deadline.after(3, TimeUnit.SECONDS);
        var requested = options.getDeadline();
        var deadline = requested == null ? limit : requested.minimum(limit);
        try {
            // 查询在锁外执行，并与实际 RPC 共用调用方的剩余预算。
            Instance instance = endpoints.select(serviceId, deadline.timeRemaining(TimeUnit.NANOSECONDS));
            int port = Integer.parseInt(instance.getMetadata().get("grpc.port"));
            if (port < 1 || port > 65535) throw new IllegalStateException("Invalid grpc.port");
            synchronized (this) {
                if (closed) throw new IllegalStateException("Channel closed");
                if (deadline.isExpired()) throw new IllegalStateException("Call deadline exceeded");
                if (current == null || !instance.getIp().equals(currentHost) || port != currentPort) {
                    if (current != null) current.shutdown();
                    current = ManagedChannelBuilder.forAddress(instance.getIp(), port).usePlaintext().build();
                    currentHost = instance.getIp();
                    currentPort = port;
                }
                return current.newCall(method, options.withDeadline(deadline));
            }
        } catch (RuntimeException exception) {
            throw Status.UNAVAILABLE.withDescription("Service discovery unavailable for " + serviceId)
                    .withCause(exception).asRuntimeException();
        }
    }

    @Override
    public String authority() { return serviceId; }

    @Override
    public synchronized void close() {
        closed = true;
        if (current != null) current.shutdownNow();
    }
}
