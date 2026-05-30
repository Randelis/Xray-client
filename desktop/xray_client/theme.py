"""Visual theme: colour palette + global stylesheet. Design only."""
from __future__ import annotations

# ── palette ─────────────────────────────────────────────────────────────────
BG = "#0C0D11"
HERO_TOP = "#1A1E29"
HERO_BOTTOM = "#111319"
CARD = "#14161D"
CARD_BORDER = "#232732"
STROKE = "#2A2F3B"
STROKE_HOVER = "#343A49"

TEXT = "#ECEEF3"
TEXT_DIM = "#868C9B"
TEXT_FAINT = "#5A6070"

ACCENT = "#1FD27A"          # green
ACCENT_2 = "#16C2C9"        # cyan
AMBER = "#FFA033"
RED = "#FF5A6E"
SELECT = "#1E2740"

# state -> (accent colour, short status word)
STATUS = {
    "IDLE":     (TEXT_DIM, "Disconnected"),
    "STARTING": (AMBER, "Connecting"),
    "RUNNING":  (ACCENT, "Connected"),
    "STOPPING": (AMBER, "Disconnecting"),
    "ERROR":    (RED, "Error"),
}

# state -> big call-to-action caption under the power button
ACTION = {
    "IDLE":     "Tap to connect",
    "STARTING": "Connecting…",
    "RUNNING":  "Connected",
    "STOPPING": "Disconnecting…",
    "ERROR":    "Connection failed",
}


def ping_color(ms: float) -> str:
    if ms < 100:
        return "#22D58A"
    if ms < 300:
        return "#FFB23E"
    return "#FF6076"


STYLESHEET = f"""
* {{
    font-family: "Segoe UI", "Inter", "SF Pro Display", "Helvetica Neue", sans-serif;
    font-size: 13px;
}}
QWidget#root {{ background: {BG}; }}
QDialog {{ background: {BG}; }}

QFrame#hero {{
    background: qlineargradient(x1:0, y1:0, x2:1, y2:1,
                stop:0 {HERO_TOP}, stop:1 {HERO_BOTTOM});
    border: 1px solid {CARD_BORDER};
    border-radius: 20px;
}}
QFrame#card {{
    background: {CARD};
    border: 1px solid {CARD_BORDER};
    border-radius: 16px;
}}

QLabel {{ color: {TEXT}; background: transparent; }}
QLabel#title    {{ font-size: 21px; font-weight: 800; color: {TEXT}; letter-spacing: 0.3px; }}
QLabel#subtitle {{ font-size: 11px; color: {TEXT_DIM}; }}
QLabel#status   {{ font-size: 12px; font-weight: 700; }}
QLabel#action   {{ font-size: 17px; font-weight: 700; color: {TEXT}; }}
QLabel#active   {{ font-size: 12px; color: {TEXT_DIM}; }}
QLabel#hint     {{ color: {TEXT_FAINT}; font-size: 11px; }}
QLabel#empty    {{ color: {TEXT_FAINT}; font-size: 13px; }}
QLabel#modecap  {{ color: {TEXT_DIM}; font-size: 12px; }}

QPushButton {{
    background: #1B1E27;
    border: 1px solid {STROKE};
    border-radius: 11px;
    padding: 9px 16px;
    color: #D7DAE2;
}}
QPushButton:hover    {{ background: #222633; border-color: {STROKE_HOVER}; }}
QPushButton:pressed  {{ background: #181B23; }}
QPushButton:disabled {{ color: #565B68; border-color: #20242E; }}

QComboBox {{
    background: #1B1E27; border: 1px solid {STROKE}; border-radius: 11px;
    padding: 9px 12px; color: #D7DAE2; min-width: 240px;
}}
QComboBox:hover {{ border-color: {STROKE_HOVER}; }}
QComboBox::drop-down {{ border: none; width: 26px; }}
QComboBox::down-arrow {{
    image: none; width: 0; height: 0;
    border-left: 5px solid transparent; border-right: 5px solid transparent;
    border-top: 6px solid {TEXT_DIM}; margin-right: 10px;
}}
QComboBox QAbstractItemView {{
    background: #191C24; border: 1px solid {STROKE}; border-radius: 10px;
    selection-background-color: {SELECT}; color: #D7DAE2; outline: none; padding: 4px;
}}

QTableWidget, QTableView {{ background: transparent; border: none; gridline-color: transparent; }}
QTableWidget::item {{ border: none; color: #D7DAE2; }}
QTableWidget::item:selected {{ background: {SELECT}; color: #FFFFFF; }}
QHeaderView {{ background: transparent; border: none; }}
QHeaderView::section {{
    background: {CARD}; border: none; border-bottom: 1px solid {CARD_BORDER};
    padding: 8px 10px; color: #6B7280; font-size: 10px; font-weight: 700;
}}
QTableCornerButton::section {{ background: transparent; border: none; }}

QScrollBar:vertical {{ background: transparent; width: 10px; margin: 4px 2px; }}
QScrollBar::handle:vertical {{ background: {STROKE}; border-radius: 5px; min-height: 32px; }}
QScrollBar::handle:vertical:hover {{ background: {STROKE_HOVER}; }}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {{ background: transparent; }}

QPlainTextEdit {{
    background: {CARD}; border: 1px solid {STROKE}; border-radius: 12px;
    padding: 10px; color: {TEXT}; selection-background-color: {SELECT};
}}
QStatusBar {{ background: transparent; color: {TEXT_DIM}; }}
QStatusBar::item {{ border: none; }}
QToolTip {{ background: #191C24; color: #D7DAE2; border: 1px solid {STROKE}; padding: 4px; }}

QDialogButtonBox QPushButton {{ min-width: 90px; }}
"""
