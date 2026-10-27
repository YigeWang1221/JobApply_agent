package jobapply.tools;

import java.time.Instant;

/**
 * 浏览器连接的状态（C006-fix-browser 第 1 节）：全应用只有这一份，在 {@link BrowserSession} 里。两个 Agent 和界面都读它，
 * 各自决定怎么应对。
 *
 * @param state      {@link #NOT_CONNECTED} / {@link #CONNECTING} / {@link #CONNECTED} / {@link #STALLED} /
 *                   {@link #DISCONNECTED}
 * @param reason     断开或卡住的原因；其它状态为 null
 * @param since      进入这个状态的时间
 * @param generation 第几次连上（每次连上加一，从 1 开始；还没连上过为 0）。快照里的 ref 只在同一次连接里有效
 */
public record BrowserState(String state, String reason, Instant since, int generation) {

    public static final String NOT_CONNECTED = "not_connected";
    public static final String CONNECTING = "connecting";
    public static final String CONNECTED = "connected";
    public static final String DISCONNECTED = "disconnected";
    /**
     * 连着，但 MCP 在调用超时内没回话（C011-fix）：多半是 Chrome 里有页面没响应。连接和标签页记录都留着，之后的调用照常
     * 发出，哪一次答话了就回到 {@link #CONNECTED}；用户点「重新连接」时先问一次，答不了才重建。
     */
    public static final String STALLED = "stalled";

    public boolean connected() {
        return CONNECTED.equals(state);
    }

    public boolean disconnected() {
        return DISCONNECTED.equals(state);
    }

    public boolean stalled() {
        return STALLED.equals(state);
    }
}
