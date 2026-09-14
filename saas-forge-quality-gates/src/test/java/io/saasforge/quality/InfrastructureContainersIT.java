package io.saasforge.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InfrastructureContainersIT {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8.8.1");
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.0.0");
    private static final String REDIS_PASSWORD = "redis-container-password";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withCommand("redis-server", "--appendonly", "yes", "--maxmemory-policy", "noeviction",
                    "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    @Test
    void redisSupportsReadWriteAndExpirationForRegisteredStyleKeys() {
        String key = "sf:test:quality-gates:container-probe:v1:" + UUID.randomUUID();
        String value = "redis-container-probe";

        try (RedisClient client = RedisClient.create(
                        "redis://:" + REDIS_PASSWORD + "@" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
                StatefulRedisConnection<String, String> connection = client.connect()) {
            connection.sync().setex(key, 30, value);

            assertEquals(value, connection.sync().get(key));
            assertTrue(connection.sync().ttl(key) > 0);
        }
    }

    @Test
    void kafkaDeliversAProducedMessageToAnIndependentConsumerGroup() throws Exception {
        String topic = "container-probe-" + UUID.randomUUID();
        String key = "container-probe-key";
        String value = "kafka-container-probe";

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));

            assertEquals(1, records.count());
            ConsumerRecord<String, String> record = records.iterator().next();
            assertEquals(key, record.key());
            assertEquals(value, record.value());
        }
    }

    private static Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return properties;
    }

    private static Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "container-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return properties;
    }
}
