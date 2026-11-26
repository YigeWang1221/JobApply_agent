package jobapply.tools;

import java.time.Clock;
import jobapply.config.HarnessProperties;
import jobapply.domain.SubRun;
import jobapply.memory.ExperienceIndex;
import jobapply.persistence.JobLedger;
import jobapply.persistence.StorageLayout;

/**
 * 工具和插件共用的服务（应用里一份）：storage、时钟、用户的 Chrome、网页读取与搜索、经验检索、台账事件、申请线程、
 * 给 Session 写通知。不是每个 Mode 都用得上全部，用不上的可以是 null。
 *
 * @param ledgerEvents 台账每次写完时通知（事件日志的 {@code changed}）；为 null 时不通知
 * @param applications Session 的 {@code start_application} 交给的申请线程；为 null 时不能开始申请
 * @param limits       打转提醒的阈值和默认上限；为 null 时用默认值
 * @param subruns      Subagent 开始、结束时通知（事件 {@code subrun}）；为 null 时不通知
 */
public record AgentServices(StorageLayout layout, Clock clock, BrowserSession browser, WebFetch fetch, WebSearch search,
                            ExperienceIndex experience, JobLedger.Listener ledgerEvents,
                            ResearchTools.Applications applications, SessionNote sessionNote,
                            HarnessProperties limits, SubRun.Listener subruns) {

    public AgentServices {
        ledgerEvents = ledgerEvents == null ? JobLedger.Listener.NONE : ledgerEvents;
        sessionNote = sessionNote == null ? SessionNote.NONE : sessionNote;
        limits = limits == null ? HarnessProperties.defaults() : limits;
        subruns = subruns == null ? SubRun.Listener.NONE : subruns;
    }

    /** 台账（写完通知 {@link #ledgerEvents}）。 */
    public JobLedger ledger() {
        return new JobLedger(layout, clock, ledgerEvents);
    }
}
