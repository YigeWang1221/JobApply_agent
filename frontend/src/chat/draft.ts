// 对话输入框的草稿按 Session 存在浏览器本地；存不了（隐私模式等）就算了，不影响发送
function draftKey(id: string) {
  return `jobapply.draft.${id}`;
}

export function readDraft(id: string): string {
  try {
    return window.localStorage.getItem(draftKey(id)) ?? "";
  } catch {
    return "";
  }
}

// 也用来给新建的 Session 预先填好第一句（C007「该查看了」）
export function writeDraft(id: string, text: string) {
  try {
    if (text) {
      window.localStorage.setItem(draftKey(id), text);
    } else {
      window.localStorage.removeItem(draftKey(id));
    }
  } catch {
    // 本地存储不可用
  }
}
