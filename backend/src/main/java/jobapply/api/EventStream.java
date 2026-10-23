package jobapply.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import jobapply.domain.AgentRun;
import jobapply.workflow.LiveFeed;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 按步推送（C006-agent 第 4 节）：把一个 Session / Job 的 {@link LiveFeed} 接到一条 {@code text/event-stream} 上。
 * 事件：{@code activity}（现在在做什么，停下时是 {@code null}）、{@code step}（一步完成）、{@code message}（模型回了文字）、
 * {@code status}（运行状态）。连接断开、超时或出错时取消订阅；前端会重连，连不上时退回轮询。
 */
final class EventStream {

    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private EventStream() {
    }

    /** 开一条推送：先发一次当前活动，之后有变化就发。 */
    static SseEmitter open(LiveFeed feed, String id) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Runnable unsubscribe = feed.subscribe(id, new LiveFeed.Listener() {
            @Override
            public void activity(LiveFeed.Activity activity) {
                send(emitter, "activity", Views.activity(activity));
            }

            @Override
            public void step(AgentRun.ToolCall call, AgentRun.ToolResult result) {
                send(emitter, "step", Views.step(call, result));
            }

            @Override
            public void thinking(String text) {
                send(emitter, "step", Views.thinking(text));
            }

            @Override
            public void message(String text) {
                send(emitter, "message", Map.of("text", text));
            }

            @Override
            public void status(String status) {
                send(emitter, "status", Map.of("status", status));
            }
        });
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(error -> unsubscribe.run());
        send(emitter, "activity", Views.activity(feed.activity(id)));
        return emitter;
    }

    /** 发不出去（连接断了）时抛出，{@link LiveFeed} 就不再通知这一条。 */
    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(name);
            emitter.send(data == null ? event.data("null") : event.data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
            throw new UncheckedIOException(new IOException("推送断开了", e));
        }
    }
}
