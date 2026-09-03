#!/usr/bin/env python3
"""统计源码注释比例，用于核对课程「注释数量多于代码的 1/3」这一验收要点。

只做行级统计，不做语法分析：一行只要以注释符号开头（或位于块注释内部）就算注释行，
空行两边都不算。行尾注释不单独计入注释行——这样得出的数字偏保守，
不会因为大量 `code(); // 说明` 而虚高。

用法：python3 scripts/comment-ratio.py
"""
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent

# 每组是「分组名 → (根目录, 后缀集合, 块注释起止, 行注释前缀)」。
GROUPS = [
    ("后端主源码", "backend/src/main/java", {".java"}, [("/*", "*/")], ("//",)),
    ("后端测试", "backend/src/test/java", {".java"}, [("/*", "*/")], ("//",)),
    ("前端脚本与组件", "frontend/src", {".ts", ".vue"}, [("/*", "*/"), ("<!--", "-->")], ("//",)),
]
# 样式单独一组：只有块注释，没有行注释。
STYLE = ("样式", "frontend/src", {".css"}, [("/*", "*/")], ())


def count(path, blocks, line_prefixes):
    """返回 (注释行, 代码行)。块注释的起止标记可以有多组，用于 Vue 里的 <!-- --> 。"""
    comments = code = 0
    inside = None
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line:
            continue
        if inside:
            comments += 1
            if inside in line:
                inside = None
            continue
        opened = next((b for b in blocks if line.startswith(b[0])), None)
        if opened:
            comments += 1
            # 单行内闭合的块注释不进入 inside 状态。
            if opened[1] not in line[len(opened[0]):]:
                inside = opened[1]
            continue
        if any(line.startswith(prefix) for prefix in line_prefixes):
            comments += 1
            continue
        code += 1
    return comments, code


def collect(root, suffixes, blocks, line_prefixes):
    comments = code = 0
    for path in sorted((REPO / root).rglob("*")):
        if path.is_file() and path.suffix in suffixes:
            file_comments, file_code = count(path, blocks, line_prefixes)
            comments += file_comments
            code += file_code
    return comments, code


def main():
    rows = []
    total_comments = total_code = 0
    for name, root, suffixes, blocks, line_prefixes in GROUPS + [STYLE]:
        comments, code = collect(root, suffixes, blocks, line_prefixes)
        rows.append((name, comments, code))
        total_comments += comments
        total_code += code
    rows.append(("合计", total_comments, total_code))

    width = max(len(name) for name, _, _ in rows)
    print(f"{'分组'.ljust(width)}  注释行   代码行   注释/代码")
    for name, comments, code in rows:
        ratio = comments / code if code else 0
        print(f"{name.ljust(width)}  {comments:>6}   {code:>6}   {ratio:>7.1%}")
    ratio = total_comments / total_code if total_code else 0
    print()
    print(f"课程要求注释多于代码的 1/3（33.3%）：{'达标' if ratio > 1 / 3 else '未达标'}")
    return 0 if ratio > 1 / 3 else 1


if __name__ == "__main__":
    sys.exit(main())
