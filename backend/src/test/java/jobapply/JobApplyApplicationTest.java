package jobapply;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.spring.ai.serializer.std.SpringAIStateSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JobApplyApplicationTest {

    @Test
    void contextStartsWithoutModelCredentials() {
        assertNotNull(StateGraph.class);
        assertNotNull(SpringAIStateSerializer.class);
        assertNotNull(ChatClient.class);
    }
}
