import { useLayoutEffect, useRef, useState } from "react";
import { Icon, Spinner } from "./icons";
import { formatSize, type AttachmentsState, type PendingFile } from "./useAttachments";

const MAX_HEIGHT = 8 * 25; // 约 8 行

// 底部输入框：随内容长高；Enter 发送，Shift+Enter 换行，输入法组字时 Enter 不发送。
// 运行中也能发（C010）：发出的话插进这次运行，Agent 下一步看到；旁边留着停止，给了 onInterrupt 时还有「打断并发送」。
// 给了 attach 才能附文件（C006-fix）：回形针选、拖进来、粘贴截图；上传中不能发送；只有附件没有文字也能发
export function Composer({
  value, onChange, onSend, onStop, onInterrupt, running, disabled, placeholder, attach, allowEmpty, blocked, lockedReason,
  sendLabel = "发送（Enter）",
}: {
  value: string;
  onChange: (text: string) => void;
  onSend: () => void;
  onStop: () => void;
  // 先停下这次运行，再按这条消息开始（C010）
  onInterrupt?: () => void;
  running: boolean;
  disabled?: boolean;
  placeholder?: string;
  attach?: AttachmentsState;
  // 没有文字也没有附件时也能发（申请工作台的「回复，继续」）
  allowEmpty?: boolean;
  // 页面自己的条件没满足（例如必填问题没答），页面负责写原因
  blocked?: boolean;
  // 现在不能输入：输入框禁用，占位文字写原因
  lockedReason?: string | null;
  sendLabel?: string;
}) {
  const box = useRef<HTMLTextAreaElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  useLayoutEffect(() => {
    const el = box.current;
    if (el) {
      el.style.height = "auto";
      el.style.height = `${Math.min(el.scrollHeight, MAX_HEIGHT)}px`;
    }
  }, [value]);

  const locked = !!lockedReason;
  const hasContent = value.trim().length > 0 || (attach?.ready.length ?? 0) > 0;
  const canSend = !disabled && !locked && !blocked && !attach?.uploading && (allowEmpty || hasContent);
  const canAttach = !!attach && !locked;

  return (
    <div className={`composer${dragging ? " dragging" : ""}${locked ? " locked" : ""}`}
      onDragOver={(e) => {
        if (canAttach && e.dataTransfer.types.includes("Files")) {
          e.preventDefault();
          setDragging(true);
        }
      }}
      onDragLeave={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
          setDragging(false);
        }
      }}
      onDrop={(e) => {
        if (!canAttach) {
          return;
        }
        e.preventDefault();
        setDragging(false);
        attach.add(Array.from(e.dataTransfer.files));
      }}>
      {attach && attach.items.length > 0 && (
        <div className="attachrow">
          {attach.items.map((item) => <AttachChip key={item.key} item={item} onRemove={() => attach.remove(item.key)} />)}
        </div>
      )}
      <div className="composerrow">
        {attach && (
          <>
            <button className="iconbtn clipbtn" title="附文件或图片（也可以拖进来、粘贴截图）" aria-label="附文件"
              disabled={!canAttach} onClick={() => picker.current?.click()}>
              <Icon name="clip" size={17} />
            </button>
            <input ref={picker} type="file" multiple hidden
              onChange={(e) => {
                attach.add(Array.from(e.target.files ?? []));
                e.target.value = "";
              }} />
          </>
        )}
        <textarea ref={box} rows={1} value={value} placeholder={lockedReason ?? placeholder} disabled={locked}
          onChange={(e) => onChange(e.target.value)}
          onPaste={(e) => {
            const files = Array.from(e.clipboardData.files);
            if (canAttach && files.length > 0) {
              e.preventDefault();
              attach.add(files);
            }
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing && e.keyCode !== 229) {
              e.preventDefault();
              if (canSend) {
                onSend();
              }
            }
          }} />
        {running && onInterrupt && canSend && (
          <button className="textbtn" title="停下现在做的，马上按这条做" onClick={onInterrupt}>打断并发送</button>
        )}
        {running && <button className="sendbtn stop" title="停止" aria-label="停止" onClick={onStop}><Icon name="stop" size={14} /></button>}
        <button className="sendbtn" title={attach?.uploading ? "附件还在上传" : running ? "插话（Enter）：Agent 下一步会看到" : sendLabel}
          aria-label={running ? "插话" : sendLabel} disabled={!canSend} onClick={onSend}>
          <Icon name="send" size={16} />
        </button>
      </div>
    </div>
  );
}

function AttachChip({ item, onRemove }: { item: PendingFile; onRemove: () => void }) {
  return (
    <div className={`attchip ${item.status}`} title={item.error ? `${item.name}：${item.error}` : item.name}>
      {item.preview
        ? <img src={item.preview} alt={item.name} />
        : <span className="attchip-icon"><Icon name="file" size={16} /></span>}
      <span className="attchip-t">
        <span className="attchip-name">{item.name}</span>
        <span className="attchip-meta">
          {item.status === "uploading" ? <><Spinner /> 上传中</> : item.status === "error" ? item.error : formatSize(item.size)}
        </span>
      </span>
      <button className="iconbtn" aria-label={`移除 ${item.name}`} title="移除" onClick={onRemove}>
        <Icon name="cross" size={12} />
      </button>
    </div>
  );
}
