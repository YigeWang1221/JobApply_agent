// 读取对话时的简单骨架
export function ChatSkeleton() {
  return (
    <div className="skeleton" aria-label="读取中">
      <div className="skel w40" />
      <div className="skel w70 right" />
      <div className="skel w90" />
      <div className="skel w80" />
      <div className="skel w60" />
    </div>
  );
}
