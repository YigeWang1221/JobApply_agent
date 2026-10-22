package jobapply.workflow.graphs;

import static jobapply.workflow.nodes.NodeIds.CHECK_PAGE;
import static jobapply.workflow.nodes.NodeIds.DONE;
import static jobapply.workflow.nodes.NodeIds.FINISH;
import static jobapply.workflow.nodes.NodeIds.OPEN_PAGE;
import static jobapply.workflow.nodes.NodeIds.RECONNECT;
import static jobapply.workflow.nodes.NodeIds.WAIT_IDENTITY;
import static jobapply.workflow.nodes.NodeIds.WAIT_LOGIN;
import static jobapply.workflow.nodes.NodeIds.WAIT_RECONNECT;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.persistence.SiteRegistry;
import jobapply.tools.BrowserSession;
import jobapply.workflow.nodes.CheckPageNode;
import jobapply.workflow.nodes.FinishNode;
import jobapply.workflow.nodes.OpenPageNode;
import jobapply.workflow.nodes.PrepStateSync;
import jobapply.workflow.nodes.ReconnectNode;
import jobapply.workflow.nodes.WaitIdentityNode;
import jobapply.workflow.state.LoginHandoffState;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

/**
 * 登录接力图（thread id = job id）。
 *
 * <pre>
 * START → open_page（新开标签页）→ [wait_login] → check_page ─┬→ [wait_login]（仍在登录页）
 *                                                ├→ finish → [done]（身份匹配）
 *                                                ├→ open_page（连接是新建立的，例如应用重启后）
 *                                                └→ [wait_identity] ─┬→ finish
 *                                                                    └→ [wait_login]（账号不对）
 * 浏览器节点断开 → [wait_reconnect] → reconnect → open_page（再新开一个岗位页）
 * </pre>
 * 方括号是等待点：图在执行它之前中断，Checkpoint 存到 sqlite，靠用户的信号恢复。图里没有任何写表单或提交的节点。
 */
public final class LoginHandoffGraph {

    public static final ObjectStreamStateSerializer<LoginHandoffState> SERIALIZER =
            new ObjectStreamStateSerializer<>(LoginHandoffState::new);

    /** 停在这些节点之前，表示在等用户。 */
    public static final Set<String> WAIT_POINTS = Set.of(WAIT_LOGIN, WAIT_IDENTITY, WAIT_RECONNECT, DONE);

    private LoginHandoffGraph() {
    }

    public static CompiledGraph<LoginHandoffState> compile(BrowserSession browser, PrepStateSync prep,
            SiteRegistry sites, BaseCheckpointSaver saver) {
        try {
            return new StateGraph<>(SERIALIZER)
                    .addNode(OPEN_PAGE, node_async(new OpenPageNode(browser, prep)))
                    .addNode(WAIT_LOGIN, node_async(state -> Map.of()))
                    .addNode(CHECK_PAGE, node_async(new CheckPageNode(browser, prep)))
                    .addNode(WAIT_IDENTITY, node_async(new WaitIdentityNode(prep)))
                    .addNode(WAIT_RECONNECT, node_async(state -> Map.of()))
                    .addNode(RECONNECT, node_async(new ReconnectNode(browser)))
                    .addNode(FINISH, node_async(new FinishNode(sites, prep)))
                    .addNode(DONE, node_async(state -> Map.of()))
                    .addEdge(StateGraph.START, OPEN_PAGE)
                    .addConditionalEdges(OPEN_PAGE, byNext(), targets(WAIT_LOGIN, WAIT_RECONNECT))
                    .addEdge(WAIT_LOGIN, CHECK_PAGE)
                    .addConditionalEdges(CHECK_PAGE, byNext(),
                            targets(WAIT_LOGIN, WAIT_IDENTITY, FINISH, WAIT_RECONNECT, OPEN_PAGE))
                    .addConditionalEdges(WAIT_IDENTITY, byNext(), targets(FINISH, WAIT_LOGIN))
                    .addEdge(WAIT_RECONNECT, RECONNECT)
                    .addConditionalEdges(RECONNECT, byNext(), targets(OPEN_PAGE, WAIT_RECONNECT))
                    .addEdge(FINISH, DONE)
                    .addEdge(DONE, StateGraph.END)
                    .compile(CompileConfig.builder()
                            .checkpointSaver(saver)
                            .interruptsBefore(List.copyOf(WAIT_POINTS))
                            .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("登录接力图定义有误", e);
        }
    }

    private static AsyncEdgeAction<LoginHandoffState> byNext() {
        return edge_async(state -> state.text(LoginHandoffState.NEXT));
    }

    private static Map<String, String> targets(String... nodes) {
        Map<String, String> targets = new LinkedHashMap<>();
        for (String node : nodes) {
            targets.put(node, node);
        }
        return targets;
    }
}
