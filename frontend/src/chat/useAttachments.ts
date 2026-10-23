import { useCallback, useEffect, useRef, useState } from "react";
import { errorText, MAX_ATTACHMENT_BYTES, type Attachment } from "../api";

// 输入框里待发的附件（C006-fix）：选中就上传，拿到编号；失败的留一张出错的小卡，写明原因
export interface PendingFile {
  key: number;
  name: string;
  size: number;
  // 图片的本地预览（object URL）；其它文件为 null
  preview: string | null;
  status: "uploading" | "done" | "error";
  error?: string;
  result?: Attachment;
}

export interface AttachmentsState {
  items: PendingFile[];
  add: (files: File[]) => void;
  remove: (key: number) => void;
  clear: () => void;
  uploading: boolean;
  // 已上传好的，发送时带上
  ready: Attachment[];
}

// upload 由页面给：Session 页传到这个 Session，申请工作台传到这个 Job
export function useAttachments(upload: (file: File) => Promise<Attachment>): AttachmentsState {
  const [items, setItems] = useState<PendingFile[]>([]);
  const nextKey = useRef(1);
  const previews = useRef(new Map<number, string>());

  useEffect(() => {
    const urls = previews.current;
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, []);

  const patch = (key: number, change: Partial<PendingFile>) =>
    setItems((old) => old.map((item) => (item.key === key ? { ...item, ...change } : item)));

  const release = (key: number) => {
    const url = previews.current.get(key);
    if (url) {
      URL.revokeObjectURL(url);
      previews.current.delete(key);
    }
  };

  const add = useCallback((files: File[]) => {
    for (const file of files) {
      const key = nextKey.current++;
      const name = file.name || "粘贴的图片";
      if (file.size > MAX_ATTACHMENT_BYTES) {
        setItems((old) => [...old, { key, name, size: file.size, preview: null, status: "error",
          error: "超过 20 MB，不能附" }]);
        continue;
      }
      let preview: string | null = null;
      if (file.type.startsWith("image/")) {
        preview = URL.createObjectURL(file);
        previews.current.set(key, preview);
      }
      setItems((old) => [...old, { key, name, size: file.size, preview, status: "uploading" }]);
      upload(file).then(
        (result) => patch(key, { status: "done", result }),
        (e: unknown) => patch(key, { status: "error", error: errorText(e) }),
      );
    }
  }, [upload]);

  const remove = useCallback((key: number) => {
    release(key);
    setItems((old) => old.filter((item) => item.key !== key));
  }, []);

  const clear = useCallback(() => {
    previews.current.forEach((url) => URL.revokeObjectURL(url));
    previews.current.clear();
    setItems([]);
  }, []);

  return {
    items,
    add,
    remove,
    clear,
    uploading: items.some((item) => item.status === "uploading"),
    ready: items.flatMap((item) => (item.status === "done" && item.result ? [item.result] : [])),
  };
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
