#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Local document-to-PDF bridge. No upload, shell expansion, or replacement of source files."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

OFFICE = {".doc", ".docx", ".ppt", ".pptx", ".odt", ".odp"}
EBOOK = {".epub", ".mobi", ".azw3"}


def executable(explicit: str | None, command: str, windows: str = "") -> str | None:
    if explicit:
        path = Path(explicit).expanduser().resolve(strict=True)
        if not path.is_file():
            raise ValueError(f"工具路径不是文件：{path}")
        return str(path)
    found = shutil.which(command)
    if found:
        return found
    if windows and Path(windows).is_file():
        return windows
    return None


def run(args: list[str], directory: Path, timeout: int = 180) -> None:
    result = subprocess.run(args, cwd=directory, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=timeout, shell=False,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    if result.returncode:
        raise RuntimeError(f"转换工具返回 {result.returncode}：" + result.stdout[-2000:].decode("utf-8", errors="replace"))


def convert(source: Path, target: Path, engines: dict[str, str | None]) -> None:
    source = source.resolve(strict=True)
    if not source.is_file() or source.stat().st_size > 32_000_000:
        raise ValueError("输入须为不超过 32 MB 的文件")
    if target.exists():
        raise FileExistsError(f"目标已存在，未覆盖：{target}")
    suffix = source.suffix.lower()
    with tempfile.TemporaryDirectory(prefix="inkweft-convert-") as scratch:
        directory = Path(scratch)
        # Fixed local name avoids option parsing and converter basename surprises.
        local = directory / ("source" + suffix)
        shutil.copyfile(source, local)
        output = directory / "source.pdf"
        if suffix == ".pdf":
            pass
        elif suffix in OFFICE:
            tool = engines["office"]
            if not tool:
                raise RuntimeError("未找到 LibreOffice。安装后用 --office 指定 soffice.exe，或在 Office 中另存为 PDF。")
            profile = directory / "profile"
            (profile / "user").mkdir(parents=True)
            (profile / "user" / "registrymodifications.xcu").write_text(
                '<?xml version="1.0"?><oor:items xmlns:oor="http://openoffice.org/2001/registry">'
                '<item oor:path="/org.openoffice.Office.Common/Security/Scripting">'
                '<prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item></oor:items>', encoding="utf-8")
            run([tool, f"-env:UserInstallation={profile.as_uri()}", "--headless", "--norestore",
                 "--convert-to", "pdf", "--outdir", str(directory), str(local)], directory)
        elif suffix in EBOOK:
            tool = engines["ebook"]
            if not tool:
                raise RuntimeError("未找到 calibre 的 ebook-convert。EPUB/PalmDOC MOBI 可直接在墨织导入；其他电子书请安装 calibre 后重试。")
            run([tool, str(local), str(output), "--paper-size", "a4", "--pdf-default-font-size", "14"], directory)
        elif suffix == ".caj":
            tool = engines["caj"]
            if not tool:
                raise RuntimeError("请用 --caj2pdf 指定已配置好依赖的 caj2pdf 脚本；不支持的 HN/CAJ 文件可由 CAJViewer 打印到 PDF。")
            command = [sys.executable, tool] if Path(tool).suffix.lower() in {".py", ""} else [tool]
            run(command + ["convert", str(local), "-o", str(output)], directory)
        else:
            raise ValueError(f"不支持的输入格式：{suffix}")
        if not output.is_file():
            raise RuntimeError("转换器没有生成 PDF；请在原软件中检查文件和字体。")
        if output.stat().st_size > 32_000_000:
            raise ValueError("输出超过 32 MB，请拆分文档后重试")
        with output.open("rb") as stream:
            if stream.read(5) != b"%PDF-":
                raise ValueError("转换结果不是 PDF，未交付")
        target.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive create: another process's output cannot be overwritten after the first check.
        try:
            with target.open("xb") as destination, output.open("rb") as original:
                shutil.copyfileobj(original, destination)
        except FileExistsError:
            raise
        except BaseException:
            target.unlink(missing_ok=True)
            raise


def main() -> int:
    parser = argparse.ArgumentParser(description="墨织本地文档转换；源文件保留，生成 PDF 后传到平板导入。")
    parser.add_argument("files", nargs="*", type=Path)
    parser.add_argument("--out-dir", type=Path, default=Path("墨织PDF"))
    parser.add_argument("--office")
    parser.add_argument("--calibre")
    parser.add_argument("--caj2pdf")
    parser.add_argument("--check", action="store_true", help="只检查本机转换器")
    args = parser.parse_args()
    try:
        engines = {
            "office": executable(args.office, "soffice", r"C:\Program Files\LibreOffice\program\soffice.exe"),
            "ebook": executable(args.calibre, "ebook-convert", r"C:\Program Files\Calibre2\ebook-convert.exe"),
            "caj": executable(args.caj2pdf, "caj2pdf"),
        }
        if args.check:
            print(json.dumps(engines, ensure_ascii=False, indent=2))
            return 0
        if not args.files:
            parser.error("请指定至少一个文档，或使用 --check")
        failed = 0
        for file in args.files:
            target = args.out_dir.resolve() / (file.stem + ".pdf")
            try:
                convert(file, target, engines)
                print(f"已生成：{target}\n请核对分页、中文字体、表格与公式后导入墨织。")
            except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                failed += 1
                print(f"未转换 {file}：{error}", file=sys.stderr)
        return 1 if failed else 0
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
