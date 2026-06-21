#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
================================================================================
  磁场数据 FFT 频谱分析工具  v2.1
  Magnetic Field FFT Spectrum Analyzer
================================================================================

  输入：Excel (.xlsx/.xls) 或 CSV 磁场源数据文件
  输出：自包含 HTML 交互式报告（Chart.js 图表）
  依赖：numpy, scipy, openpyxl (自动安装)
  Python：≥ 3.9

  用法：
    python magnetic_analyzer.py 文件1.xlsx 文件2.xlsx ...
    python magnetic_analyzer.py *.xlsx
    python magnetic_analyzer.py data/*.xlsx -o report.html

================================================================================
"""

# ============================================================
#  依赖自动安装
# ============================================================
import sys
import subprocess

_REQUIRED = {
    'numpy':      'numpy',
    'scipy':      'scipy',
    'openpyxl':   'openpyxl',
    'matplotlib': 'matplotlib',
    'reportlab':  'reportlab',
}

_missing = []
for _mod, _pkg in _REQUIRED.items():
    try:
        __import__(_mod)
    except ImportError:
        _missing.append(_pkg)

if _missing:
    print(f"[依赖安装] 正在安装: {' '.join(_missing)} ...")
    # 按优先级尝试多个源（国内镜像可能间歇不可用，PyPI.org 作兜底）
    _sources = [
        (['-i', 'https://pypi.org/simple/',
          '--trusted-host', 'pypi.org', '--trusted-host', 'files.pythonhosted.org']),
        (['-i', 'https://mirrors.aliyun.com/pypi/simple/',
          '--trusted-host', 'mirrors.aliyun.com']),
        (['-i', 'https://pypi.tuna.tsinghua.edu.cn/simple',
          '--trusted-host', 'pypi.tuna.tsinghua.edu.cn']),
        [],  # pip 本地默认配置
    ]
    ok = False
    for src in _sources:
        try:
            cmd = [sys.executable, '-m', 'pip', 'install'] + src + _missing
            subprocess.check_call(cmd, timeout=120)
            ok = True
            break
        except Exception:
            continue

    if ok:
        print("[依赖安装] 完成。请重新运行脚本")
    else:
        print(f"\n[依赖安装] 自动安装失败，请手动执行：")
        print(f"  {sys.executable} -m pip install -i https://pypi.org/simple/ {' '.join(_missing)}")
    sys.exit(0 if ok else 1)


import argparse
import csv
import glob
import io
import json
import os
import textwrap
from datetime import datetime

import numpy as np
from scipy.fft import fft, fftfreq
from scipy.signal import welch, find_peaks
import openpyxl


# ============================================================
#  数据读取
# ============================================================

def read_magnetic_data(filepath):
    """
    读取磁场数据文件（.xlsx/.xls/.csv）
    自动识别时间列与磁场总量列，返回时间(相对秒)和总量(uT)
    """
    ext = os.path.splitext(filepath)[1].lower()
    header = []
    data_rows = []

    if ext == '.xlsx':
        wb = openpyxl.load_workbook(filepath, read_only=True, data_only=True)
        ws = wb.active
        rows = list(ws.iter_rows(values_only=True))
        wb.close()
        if len(rows) < 2:
            raise ValueError("至少需要标题行 + 1行数据")
        header = [str(h).strip() if h else '' for h in rows[0]]
        data_rows = rows[1:]
    elif ext == '.xls':
        # .xls 格式 openpyxl 不支持，需 xlrd；若无则提示
        try:
            import xlrd
        except ImportError:
            raise ValueError(".xls 格式需要 xlrd 库，请运行: pip install xlrd")
        wb = xlrd.open_workbook(filepath)
        ws = wb.sheet_by_index(0)
        header = [str(ws.cell_value(0, c)).strip() for c in range(ws.ncols)]
        data_rows = []
        for r in range(1, ws.nrows):
            data_rows.append([ws.cell_value(r, c) for c in range(ws.ncols)])
    elif ext == '.csv':
        # 尝试多种编码：UTF-8 → GBK → GB18030
        for enc in ('utf-8-sig', 'gbk', 'gb18030', 'latin-1'):
            try:
                with open(filepath, 'r', encoding=enc) as f:
                    reader = csv.reader(f)
                    header = [h.strip() for h in next(reader)]
                    data_rows = list(reader)
                break
            except (UnicodeDecodeError, UnicodeError):
                continue
        else:
            raise ValueError(f"无法识别文件编码: {filepath}")
    else:
        raise ValueError(f"不支持的文件格式: {ext}，仅支持 .xlsx .xls .csv")

    # ---- 自动识别列 ----
    time_col = None
    mag_col = None
    for i, h in enumerate(header):
        hl = h.lower()
        if time_col is None and ('时间' in h or 'time' in hl or 'timestamp' in hl):
            time_col = i
        if mag_col is None and ('总量' in h or 'total' in hl or 'magnitude' in hl):
            mag_col = i
    if time_col is None:
        time_col = 0
    if mag_col is None:
        mag_col = len(header) - 1

    # ---- 解析 ----
    times, mags = [], []
    for row in data_rows:
        if row is None:
            continue
        try:
            tv, mv = row[time_col], row[mag_col]
            if tv is None or mv is None:
                continue
            times.append(_parse_time(tv))
            mags.append(float(mv))
        except (ValueError, TypeError, IndexError):
            continue

    if len(times) < 10:
        raise ValueError(f"有效数据点不足 ({len(times)})")

    times = np.array(times)

    # 处理跨午夜时间回绕：检测大的负跳变并修正
    diffs = np.diff(times)
    # 如果存在大于 12 小时的负跳变，说明跨了午夜
    midnight_mask = diffs < -12 * 3600
    if np.any(midnight_mask):
        # 在每个午夜跳变点之后，加上 24 小时（86400 秒）
        cum_correction = np.zeros(len(times))
        for i in np.where(midnight_mask)[0]:
            cum_correction[i + 1:] += 86400.0
        times = times + cum_correction

    times = times - times[0]
    return times, np.array(mags), {
        'filename': os.path.basename(filepath),
        'time_col': header[time_col] if time_col < len(header) else 'auto',
        'mag_col': header[mag_col] if mag_col < len(header) else 'auto',
    }


def _parse_time(val):
    """解析时间值：datetime / '2026-06-21 23:59:42.775.323.894' / '2026-05-27 00:04:21.913' / 'HH:MM:SS.毫秒.纳秒' / 纯数字"""
    from datetime import datetime as dt
    if isinstance(val, dt):
        epoch = dt(2000, 1, 1)
        return (val - epoch).total_seconds()
    if isinstance(val, (int, float)):
        return float(val)
    s = str(val).strip().strip("'\"")

    # 带日期 + 纳秒级: "2026-06-21 23:59:42.775.323.894"
    # 先尝试标准 datetime 格式（毫秒/微秒级）
    for fmt in ('%Y-%m-%d %H:%M:%S.%f', '%Y-%m-%d %H:%M:%S',
                '%Y/%m/%d %H:%M:%S.%f', '%Y/%m/%d %H:%M:%S'):
        try:
            t = dt.strptime(s, fmt)
            epoch = dt(2000, 1, 1)
            return (t - epoch).total_seconds()
        except ValueError:
            continue

    # 带日期 + 纳秒级: "2026-06-21 23:59:42.775.323.894" (strptime 无法处理多段小数)
    if ' ' in s and '-' in s.split(' ')[0]:
        try:
            date_part, time_part = s.split(' ', 1)
            dp = date_part.split('-')
            if len(dp) == 3:
                year, month, day = int(dp[0]), int(dp[1]), int(dp[2])
                tp = time_part.split(':')
                if len(tp) >= 3:
                    h, mi = int(tp[0]), int(tp[1])
                    sp = tp[2].split('.')
                    sec = int(sp[0])
                    frac = int(sp[1]) / 1000.0 if len(sp) >= 2 else 0.0
                    t = dt(year, month, day, h, mi, sec)
                    epoch = dt(2000, 1, 1)
                    return (t - epoch).total_seconds() + frac
        except (ValueError, IndexError):
            pass

    # 纯时间格式: "HH:MM:SS.xxx" 或 "HH:MM:SS.毫秒.纳秒"
    if ':' in s:
        parts = s.split(':')
        if len(parts) >= 3:
            h, m = int(parts[0]), int(parts[1])
            sp = parts[2].split('.')
            sec = int(sp[0])
            # 兼容纳秒级时间戳: SS.毫秒.纳秒 (如 42.775.323.894)
            # 以及标准毫秒级: SS.毫秒 (如 42.775)
            if len(sp) >= 3:
                frac = int(sp[1]) / 1000.0
            elif len(sp) > 1:
                frac = float('0.' + ''.join(sp[1:]))
            else:
                frac = 0.0
            return h * 3600 + m * 60 + sec + frac
    return float(s)


# ============================================================
#  FFT 分析
# ============================================================

def analyze(times, magnitudes):
    """对一条磁场时间序列执行完整FFT频谱分析"""
    n = len(magnitudes)

    # 采样率
    dt = np.mean(np.diff(times))
    fs = 1.0 / dt

    # 去均值
    detrended = magnitudes - np.mean(magnitudes)

    # FFT — 加 Hanning 窗抑制频谱泄漏
    window = np.hanning(n)
    win_gain = np.sum(window) / n          # 窗口相干增益 (Hanning ≈ 0.5)
    yf = fft(detrended * window)
    xf = fftfreq(n, dt)
    mask = xf >= 0
    pos_f = xf[mask]
    pos_a = np.abs(yf[mask]) / n * 2 / win_gain   # 补偿窗口幅度损失
    df_fft = pos_f[1] - pos_f[0] if len(pos_f) > 1 else 0

    # Welch PSD
    nperseg = min(1024, max(64, n // 4))
    f_w, psd = welch(detrended, fs=fs, nperseg=nperseg,
                     noverlap=nperseg // 2, detrend='constant')

    # 有意义频率范围
    noise_start = min(int(5.0 / df_fft), len(pos_a) - 1) if df_fft > 0 else 0
    noise_floor = np.median(pos_a[noise_start:]) if noise_start < len(pos_a) else np.median(pos_a)
    threshold = max(noise_floor * 3, np.max(pos_a) * 0.02)
    sig_start = min(max(1, int(0.05 / df_fft)), len(pos_a) - 1)
    sig_idx = np.where(pos_a[sig_start:] > threshold)[0] + sig_start
    if len(sig_idx) > 0:
        freq_lo = max(0.05, round(float(pos_f[sig_idx[0]]), 1))
        freq_hi = round(float(pos_f[sig_idx[-1]]), 1)
    else:
        freq_lo, freq_hi = 0.1, min(fs / 2, 50.0)

    # 80% 能量集中
    cum_e = np.cumsum(psd)
    total_e = cum_e[-1]
    lo_i = int(np.searchsorted(cum_e, total_e * 0.10))
    hi_i = int(np.searchsorted(cum_e, total_e * 0.90))
    e_lo = round(float(f_w[max(0, lo_i)]), 2)
    e_hi = round(float(f_w[min(len(f_w) - 1, hi_i)]), 2)

    # 主频率
    pk_idx, _ = find_peaks(pos_a[sig_start:], height=threshold,
                           distance=max(3, int(0.1 / df_fft)))
    pk_idx += sig_start
    si = np.argsort(pos_a[pk_idx])[::-1]
    top_peaks = [{'f': round(float(pos_f[pk_idx[i]]), 4),
                  'a': round(float(pos_a[pk_idx[i]]), 4)}
                 for i in si[:5]]

    # 频谱类型
    r = np.sum(psd[f_w <= 1.0]) / total_e if total_e > 0 else 0
    if r > 0.9:
        stype = "极低频主导 (<1Hz)"
    elif r > 0.7:
        stype = "低频主导 (<1Hz)"
    elif r > 0.5:
        stype = "低频为主"
    else:
        stype = "宽带分布"

    return {
        'n': n, 'dur': round(float(times[-1]), 2),
        'dt_ms': round(float(dt * 1000), 2),
        'fs': round(float(fs)),
        'nyq': round(float(fs / 2), 1),
        'f_range': f"{freq_lo}Hz - {freq_hi}Hz",
        'e_range': f"{e_lo}Hz - {e_hi}Hz",
        'e_lo': e_lo, 'e_hi': e_hi,
        'peaks': top_peaks,
        'stype': stype,
        'mu': round(float(np.mean(magnitudes)), 2),
        'std': round(float(np.std(magnitudes)), 2),
        # 绘图数据（采样以减少 HTML 体积）
        '_t': times.tolist(),
        '_m': magnitudes.tolist(),
        '_ff': pos_f.tolist(),
        '_fa': pos_a.tolist(),
        '_wf': f_w.tolist(),
        '_wp': psd.tolist(),
    }


# ============================================================
#  HTML 报告生成
# ============================================================

CSS = """body{font-family:"Segoe UI",Arial,sans-serif;background:#f5f5f5;margin:0;padding:20px;color:#333}
.card{background:#fff;border-radius:12px;padding:24px;margin:0 auto 20px;max-width:1300px;box-shadow:0 2px 8px rgba(0,0,0,0.08)}
h2{font-size:18px;margin:0 0 4px;color:#1a1a2e}
.meta{font-size:13px;color:#888;margin-bottom:16px}
.stats{display:flex;gap:16px;flex-wrap:wrap;margin:12px 0}
.stat-item{background:#f0f4ff;border-radius:8px;padding:10px 16px;flex:1;min-width:140px}
.stat-label{font-size:11px;color:#666;text-transform:uppercase;letter-spacing:0.5px}
.stat-value{font-size:20px;font-weight:700;color:#1a1a2e;margin-top:2px}
.stat-value.highlight{color:#2563eb}
.chart-row{display:flex;gap:16px;margin-top:16px}
.chart-box{flex:1;background:#fafafa;border-radius:8px;padding:12px;min-width:0}
.chart-title{font-size:12px;color:#666;margin-bottom:4px;font-weight:600}
canvas{width:100%!important;max-height:220px}
.summary-table{width:100%;border-collapse:collapse;margin-top:20px;font-size:14px}
.summary-table th{background:#1a1a2e;color:#fff;padding:10px 14px;text-align:left;font-weight:500;white-space:nowrap}
.summary-table td{padding:10px 14px;border-bottom:1px solid #eee;word-break:break-all}
.summary-table tr:hover td{background:#f8faff}"""


def _sample(arr, max_pts=500):
    """均匀采样数组以减少 JSON 体积，保证首尾点都包含"""
    n = len(arr)
    if n <= max_pts:
        return arr
    idx = np.linspace(0, n - 1, max_pts, dtype=int)
    return [arr[int(i)] for i in idx]


def _fmt(v, n=1):
    """格式化为指定位小数的字符串"""
    if isinstance(v, float):
        return f"{v:.{n}f}"
    return str(v)


def generate_html(results, output_path):
    """生成自包含 HTML 报告"""
    today = datetime.now().strftime('%Y-%m-%d')

    parts = [
        '<html><head><meta charset="utf-8">',
        '<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>',
        '<style>', CSS, '</style>',
        '</head><body>',
    ]

    # ---- 汇总表 ----
    n_files = len(results)
    parts.append('<div class="card">')
    parts.append(f'<h2>磁场数据 FFT 频谱分析 - 汇总表</h2>')
    parts.append(f'<p class="meta">共 {n_files} 份文件分析结果 | {today}</p>')
    parts.append('<table class="summary-table">')
    parts.append('<tr><th>#</th><th>文件名</th><th>频率范围</th><th>采样率</th><th>能量集中范围</th><th>数据点</th><th>时长</th></tr>')
    for i, r in enumerate(results):
        fn = r['info']['filename']
        parts.append(
            f'<tr><td>{i+1}</td><td>{fn}</td>'
            f'<td><b>{r["f_range"]}</b></td>'
            f'<td>{r["fs"]}Hz</td>'
            f'<td><b>{r["e_range"]}</b></td>'
            f'<td>{r["n"]}</td>'
            f'<td>{r["dur"]}s</td></tr>'
        )
    parts.append('</table></div>')

    # ---- 逐文件卡片 ----
    chart_defs = []  # Chart.js 初始化代码

    for idx, r in enumerate(results):
        fn = r['info']['filename']

        parts.append('<div class="card">')
        parts.append(f'<h2>文件 {idx+1}: {fn}</h2>')
        parts.append(f'<p class="meta">{r["n"]} 个数据点 | 时长 {r["dur"]}s | '
                     f'总磁场 {r["mu"]}±{r["std"]} uT</p>')

        # 统计卡片
        parts.append('<div class="stats">')
        parts.append(f'<div class="stat-item"><div class="stat-label">频率范围</div>'
                     f'<div class="stat-value highlight">{r["f_range"]}</div></div>')
        parts.append(f'<div class="stat-item"><div class="stat-label">采样率</div>'
                     f'<div class="stat-value highlight">{r["fs"]} Hz</div></div>')
        parts.append(f'<div class="stat-item"><div class="stat-label">能量集中</div>'
                     f'<div class="stat-value highlight">{r["e_range"]}</div></div>')
        parts.append(f'<div class="stat-item"><div class="stat-label">奈奎斯特</div>'
                     f'<div class="stat-value">{r["nyq"]} Hz</div></div>')
        parts.append('</div>')

        # 图表行
        parts.append('<div class="chart-row">')
        parts.append(f'<div class="chart-box"><div class="chart-title">时域波形 - 总磁场 (uT)</div>'
                     f'<canvas id="time_{idx}"></canvas></div>')
        parts.append(f'<div class="chart-box"><div class="chart-title">FFT 幅度谱 (0-15Hz)</div>'
                     f'<canvas id="fft_{idx}"></canvas></div>')
        parts.append(f'<div class="chart-box"><div class="chart-title">Welch 功率谱密度 (0-15Hz)</div>'
                     f'<canvas id="welch_{idx}"></canvas></div>')
        parts.append('</div>')
        parts.append('</div>')

        # ---- 准备绘图数据 (采样) ----
        t = _sample(r['_t'], 300)
        m = _sample(r['_m'], 300)
        # Chart.js 数值轴：用 {x, y} 数据点，保证频率刻度正确
        time_data = json.dumps([{'x': round(t[i], 2), 'y': round(m[i], 3)}
                                for i in range(len(t))])

        ff = np.array(r['_ff'])
        fa = np.array(r['_fa'])
        lim = max(1, int(np.searchsorted(ff, 15)))
        ff_s = _sample(ff[:lim].tolist(), 200)
        fa_s = _sample(fa[:lim].tolist(), 200)
        fft_data = json.dumps([{'x': round(ff_s[i], 3), 'y': round(fa_s[i], 5)}
                               for i in range(len(ff_s))])

        wf = np.array(r['_wf'])
        wp = np.array(r['_wp'])
        lim_w = max(1, int(np.searchsorted(wf, 15)))
        wf_s = _sample(wf[:lim_w].tolist(), 200)
        wp_s = _sample(wp[:lim_w].tolist(), 200)
        welch_data = json.dumps([{'x': round(wf_s[i], 3), 'y': round(wp_s[i], 7)}
                                 for i in range(len(wf_s))])

        colors = ['#2563eb', '#dc2626', '#16a34a', '#9333ea', '#ea580c', '#0891b2']
        c = colors[idx % len(colors)]

        # Chart.js 代码（数值轴，{x,y} 数据点）
        chart_defs.append(f"""
// ---- 文件 {idx+1}: {fn} ----
new Chart(document.getElementById('time_{idx}'),{{
  type:'scatter',data:{{
    datasets:[{{label:'Total B',data:{time_data},borderColor:'{c}',borderWidth:1,pointRadius:0,showLine:true,tension:0.1,fill:false}}]
  }},
  options:{{
    responsive:true,maintainAspectRatio:false,
    plugins:{{legend:{{display:false}}}},
    scales:{{
      x:{{type:'linear',title:{{display:true,text:'Time (s)'}},ticks:{{maxTicksLimit:8}}}},
      y:{{title:{{display:true,text:'uT'}},ticks:{{maxTicksLimit:5}}}}
    }},
    animation:false
  }}
}});

new Chart(document.getElementById('fft_{idx}'),{{
  type:'scatter',data:{{
    datasets:[{{label:'FFT Amplitude',data:{fft_data},borderColor:'{c}',backgroundColor:'{c}20',borderWidth:1.5,pointRadius:0,showLine:true,tension:0.1,fill:true}}]
  }},
  options:{{
    responsive:true,maintainAspectRatio:false,
    plugins:{{legend:{{display:false}}}},
    scales:{{
      x:{{type:'linear',title:{{display:true,text:'Frequency (Hz)'}},min:0,max:15,ticks:{{maxTicksLimit:8}}}},
      y:{{title:{{display:true,text:'Amplitude (uT)'}},ticks:{{maxTicksLimit:5}}}}
    }},
    animation:false
  }}
}});

new Chart(document.getElementById('welch_{idx}'),{{
  type:'scatter',data:{{
    datasets:[{{label:'PSD',data:{welch_data},borderColor:'{c}',backgroundColor:'{c}20',borderWidth:1.5,pointRadius:0,showLine:true,tension:0.1,fill:true}}]
  }},
  options:{{
    responsive:true,maintainAspectRatio:false,
    plugins:{{legend:{{display:false}}}},
    scales:{{
      x:{{type:'linear',title:{{display:true,text:'Frequency (Hz)'}},min:0,max:15,ticks:{{maxTicksLimit:8}}}},
      y:{{title:{{display:true,text:'PSD (uT\\u00b2/Hz)'}},ticks:{{maxTicksLimit:5}}}}
    }},
    animation:false
  }}
}});
""")

    # ---- 脚本段 ----
    parts.append('<script>')
    parts.extend(chart_defs)
    parts.append('</script></body></html>')

    html = '\n'.join(parts)
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(html)

    return output_path


# ============================================================
#  PDF 报告生成
# ============================================================

def generate_pdf(results, output_path):
    """生成 PDF 报告（内嵌 matplotlib 图表）"""
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        from reportlab.lib.pagesizes import A4
        from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer,
                                        Table, Image, PageBreak, TableStyle)
        from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
        from reportlab.lib import colors
        from reportlab.lib.units import cm
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
    except ImportError as e:
        raise ImportError(
            "PDF report needs matplotlib and reportlab."
        ) from e

    # ---- 查找并注册中文 TrueType 字体 ----
    _font_dir = os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts')
    _candidates = [
        ('SimHei',     'simhei.ttf'),
        ('STHeiti',    'stheiti.ttf'),
        ('MicrosoftYaHei', 'msyh.ttf'),
        ('MicrosoftYaHei', 'msyh.ttc'),
        ('SimSun',     'simsun.ttc'),
    ]
    _cjk_name = None
    _cjk_path = None
    for _n, _f in _candidates:
        _p = os.path.join(_font_dir, _f)
        if os.path.isfile(_p):
            _cjk_name, _cjk_path = _n, _p
            break

    if _cjk_path:
        pdfmetrics.registerFont(TTFont(_cjk_name, _cjk_path))
        plt.rcParams['font.sans-serif'] = [_cjk_name, 'DejaVu Sans']
        plt.rcParams['axes.unicode_minus'] = False
        _body_font = _cjk_name
        _title_font = _cjk_name
    else:
        _body_font = 'Helvetica'
        _title_font = 'Helvetica-Bold'

    doc = SimpleDocTemplate(
        output_path, pagesize=A4,
        rightMargin=2*cm, leftMargin=2*cm,
        topMargin=2*cm, bottomMargin=2*cm
    )
    story = []

    # 自定义样式（使用中文字体）
    styles = getSampleStyleSheet()
    styles.add(ParagraphStyle('CJKTitle', fontName=_title_font, fontSize=18,
                               leading=22, spaceAfter=12, alignment=1))
    styles.add(ParagraphStyle('CJKNormal', fontName=_body_font, fontSize=10,
                               leading=14))
    styles.add(ParagraphStyle('CJKHeading', fontName=_title_font, fontSize=13,
                               leading=17, spaceAfter=6, spaceBefore=12))

    story.append(Paragraph("<b>磁场数据 FFT 频谱分析报告</b>", styles['CJKTitle']))
    story.append(Paragraph(
        f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} | 文件数: {len(results)}",
        styles['CJKNormal']
    ))
    story.append(Spacer(1, 0.8*cm))

    # ---- 汇总表 ----
    sd = [['序号', '文件名', '频率范围', '采样率', '能量集中', '数据点', '时长']]
    for i, r in enumerate(results):
        sd.append([
            str(i+1), r['info']['filename'], r['f_range'],
            str(r['fs'])+'Hz', r['e_range'], str(r['n']), str(r['dur'])+'s'
        ])
    t = Table(sd, colWidths=[0.8*cm, 6.5*cm, 3.0*cm, 1.5*cm, 3.0*cm, 1.3*cm, 1.3*cm])
    t.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), colors.HexColor('#1a1a2e')),
        ('TEXTCOLOR', (0,0), (-1,0), colors.whitesmoke),
        ('ALIGN', (0,0), (-1,-1), 'CENTER'),
        ('FONTNAME', (0,0), (-1,0), _title_font),
        ('FONTSIZE', (0,0), (-1,0), 10),
        ('BOTTOMPADDING', (0,0), (-1,0), 8),
        ('BACKGROUND', (0,1), (-1,-1), colors.HexColor('#f8faff')),
        ('GRID', (0,0), (-1,-1), 0.5, colors.grey),
        ('FONTNAME', (0,1), (-1,-1), _body_font),
        ('FONTSIZE', (0,1), (-1,-1), 9),
        ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ]))
    story.append(t)
    story.append(Spacer(1, 0.5*cm))

    for idx, r in enumerate(results):
        fn = r['info']['filename']
        story.append(Paragraph(f"<b>文件 {idx+1}: {fn}</b>", styles['CJKHeading']))
        story.append(Paragraph(
            f"{r['n']} 个数据点 | 时长 {r['dur']}s | 磁场 {r['mu']}±{r['std']} uT | {r['stype']}",
            styles['CJKNormal']
        ))
        story.append(Spacer(1, 0.3*cm))

        sd2 = [
            ['频率范围', '采样率', '能量集中', '奈奎斯特'],
            [r['f_range'], str(r['fs'])+' Hz', r['e_range'], str(r['nyq'])+' Hz']
        ]
        st = Table(sd2, colWidths=[4*cm, 3*cm, 4*cm, 3*cm])
        st.setStyle(TableStyle([
            ('BACKGROUND', (0,0), (-1,0), colors.HexColor('#2563eb')),
            ('TEXTCOLOR', (0,0), (-1,0), colors.whitesmoke),
            ('ALIGN', (0,0), (-1,-1), 'CENTER'),
            ('FONTNAME', (0,0), (-1,0), _title_font),
            ('FONTNAME', (0,1), (-1,-1), _body_font),
            ('GRID', (0,0), (-1,-1), 0.5, colors.grey),
            ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
        ]))
        story.append(st)
        story.append(Spacer(1, 0.3*cm))

        if r['peaks']:
            ps = ' | '.join([f"{p['f']:.2f}Hz ({p['a']:.3f}uT)" for p in r['peaks'][:3]])
            story.append(Paragraph(f"<b>主频:</b> {ps}", styles['CJKNormal']))
            story.append(Spacer(1, 0.3*cm))

        # matplotlib charts
        fig, axes = plt.subplots(1, 3, figsize=(10, 2.8))
        c = plt.cm.tab10(idx % 10)

        t_arr = np.array(r['_t']); m_arr = np.array(r['_m'])
        if len(t_arr) > 500:
            step = len(t_arr)//500; t_arr, m_arr = t_arr[::step], m_arr[::step]
        axes[0].plot(t_arr, m_arr, color=c, linewidth=0.8)
        axes[0].set_title('时域波形'); axes[0].set_xlabel('时间 (s)'); axes[0].set_ylabel('磁场 (uT)')
        axes[0].grid(True, alpha=0.3)

        ff = np.array(r['_ff']); fa = np.array(r['_fa'])
        lim = max(1, int(np.searchsorted(ff, 15)))
        axes[1].plot(ff[:lim], fa[:lim], color=c, linewidth=0.8)
        axes[1].set_title('FFT 幅度谱 (0-15Hz)'); axes[1].set_xlabel('频率 (Hz)'); axes[1].set_ylabel('幅度 (uT)')
        axes[1].grid(True, alpha=0.3)

        wf = np.array(r['_wf']); wp = np.array(r['_wp'])
        lim_w = max(1, int(np.searchsorted(wf, 15)))
        axes[2].plot(wf[:lim_w], wp[:lim_w], color=c, linewidth=0.8)
        axes[2].set_title('Welch 功率谱密度 (0-15Hz)'); axes[2].set_xlabel('频率 (Hz)'); axes[2].set_ylabel('PSD (uT^2/Hz)')
        axes[2].grid(True, alpha=0.3)

        plt.tight_layout()
        buf = io.BytesIO()
        fig.savefig(buf, format='png', dpi=150)
        buf.seek(0)
        plt.close(fig)
        story.append(Image(buf, width=16*cm, height=4.5*cm))
        story.append(Spacer(1, 0.5*cm))
        if idx < len(results)-1:
            story.append(PageBreak())

    doc.build(story)


# ============================================================
#  主程序
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="磁场数据 FFT 频谱分析工具 v2.1 — 输出自包含 HTML 报告",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
        示例:
          python magnetic_analyzer.py data1.xlsx data2.xlsx
          python magnetic_analyzer.py *.xlsx -o report.html
          python magnetic_analyzer.py data/*.csv
        """)
    )
    parser.add_argument('files', nargs='*', help='Excel/CSV 文件路径 (默认: *.csv)')
    parser.add_argument('--output', '-o', default=None,
                        help='输出 HTML 路径 (默认: ./磁场分析报告_日期.html)')

    args = parser.parse_args()

    # 未提供文件时默认分析当前目录 *.csv
    if not args.files:
        args.files = ['*.csv']

    # Windows CMD 不展开通配符，这里手动展开 *.xlsx *.csv 等
    expanded = []
    for pattern in args.files:
        matches = glob.glob(pattern)
        if matches:
            expanded.extend(matches)
        else:
            expanded.append(pattern)  # 保留原样，后续报"文件不存在"
    args.files = sorted(set(expanded))

    if args.output is None:
        date_str = datetime.now().strftime('%Y%m%d_%H%M%S')
        args.output = f'磁场分析报告_{date_str}.html'

    print(f"  磁场数据 FFT 分析工具 v2.1")
    print(f"  Python: {sys.version.split()[0]}")
    print(f"  输入文件: {len(args.files)} 个")
    print()

    results = []
    for i, fp in enumerate(args.files):
        if not os.path.exists(fp):
            print(f"  [{i+1}/{len(args.files)}] ✗ {fp} — 文件不存在，跳过")
            continue
        print(f"  [{i+1}/{len(args.files)}] {os.path.basename(fp)} ...", end=' ')
        try:
            times, mags, info = read_magnetic_data(fp)
            r = analyze(times, mags)
            r['info'] = info
            results.append(r)
            print(f"✓ ({r['n']}点, fs={r['fs']}Hz)")
        except Exception as e:
            print(f"✗ {e}")

    if not results:
        print("\n  错误: 没有成功分析任何文件")
        sys.exit(1)

    # 生成 HTML
    print(f"\n  生成 HTML: {args.output} ...", end=' ')
    generate_html(results, args.output)
    print("OK")

    # 生成 PDF
    pdf_path = os.path.splitext(args.output)[0] + '.pdf'
    print(f"  生成 PDF : {pdf_path} ...", end=' ')
    try:
        generate_pdf(results, pdf_path)
        print("OK")
    except Exception as e:
        print(f"ERROR {e}")

    # 汇总
    print(f"\n{'='*60}")
    print(f"  完成: {len(results)} 份文件")
    print(f"  HTML: {os.path.abspath(args.output)}")
    print(f"  PDF : {os.path.abspath(pdf_path)}")
    print(f"{'='*60}\n")

    # 打印摘要
    for i, r in enumerate(results):
        fn = r['info']['filename']
        print(f"  {i+1}. {fn}")
        print(f"     频率范围: {r['f_range']}    采样率: {r['fs']}Hz    能量集中: {r['e_range']}")
        print(f"     磁场: {r['mu']}±{r['std']} uT    {r['stype']}")
        if r['peaks']:
            pstr = '  '.join([f"{p['f']:.2f}Hz({p['a']:.3f}uT)" for p in r['peaks'][:3]])
            print(f"     主频: {pstr}")
        print()


if __name__ == '__main__':
    main()
