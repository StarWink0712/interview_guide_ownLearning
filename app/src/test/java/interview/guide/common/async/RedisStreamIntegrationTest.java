package interview.guide.common.async;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.testsupport.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Redis Stream 容器集成测试")
class RedisStreamIntegrationTest extends BaseIntegrationTest {

  private static final String STREAM_PREFIX = "it:redis-stream:";

  @Autowired
  private RedisService redisService;

  @AfterEach
  void cleanup() {
    redisService.deleteByPattern(STREAM_PREFIX + "*");
  }

  @Test
  @DisplayName("支持创建消费者组、写入、读取并 ACK 消息")
  void createGroupAddReadAndAckMessage() {
    String streamKey = uniqueStreamKey();
    String groupName = "integration-group";
    String consumerName = "consumer-a";
    AtomicReference<StreamMessageId> consumedMessageId = new AtomicReference<>();

    redisService.createStreamGroup(streamKey, groupName);
    String messageId = redisService.streamAdd(streamKey, Map.of(
        "taskId", "task-001",
        "payload", "hello-stream"
    ));

    boolean consumed = redisService.streamConsumeMessages(
        streamKey,
        groupName,
        consumerName,
        1,
        1_000,
        (id, data) -> {
          consumedMessageId.set(id);
          assertThat(data)
              .containsEntry("taskId", "task-001")
              .containsEntry("payload", "hello-stream");
          redisService.streamAck(streamKey, groupName, id);
        }
    );

    assertThat(messageId).isNotBlank();
    assertThat(consumed).isTrue();
    assertThat(consumedMessageId.get()).isNotNull();
    assertThat(redisService.streamLen(streamKey)).isEqualTo(1);
  }

  @Test
  @DisplayName("pending 消息可被其他消费者 auto claim 后 ACK")
  void pendingMessageCanBeClaimedAndAcknowledged() throws Exception {
    String streamKey = uniqueStreamKey();
    String groupName = "integration-group";

    redisService.createStreamGroup(streamKey, groupName);
    redisService.streamAdd(streamKey, Map.of("taskId", "pending-001"));

    Map<StreamMessageId, Map<String, String>> pendingMessages =
        redisService.streamReadGroup(streamKey, groupName, "consumer-a", 1);

    assertThat(pendingMessages).hasSize(1);

    Thread.sleep(20);
    Map<StreamMessageId, Map<String, String>> claimedMessages =
        redisService.streamAutoClaim(streamKey, groupName, "consumer-b", 1, 10);

    assertThat(claimedMessages).hasSize(1);
    assertThat(claimedMessages.values().iterator().next())
        .containsEntry("taskId", "pending-001");

    StreamMessageId claimedId = claimedMessages.keySet().iterator().next();
    redisService.streamAck(streamKey, groupName, claimedId);
  }

  private String uniqueStreamKey() {
    return STREAM_PREFIX + UUID.randomUUID();
  }
}
