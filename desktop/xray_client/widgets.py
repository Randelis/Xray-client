"""Custom-painted widgets for a more polished look. Design only — no app logic."""
from __future__ import annotations

from PySide6.QtCore import QPointF, QRectF, QSize, Qt, QTimer
from PySide6.QtGui import (
    QColor, QFont, QFontMetrics, QLinearGradient, QPainter, QPen, QRadialGradient,
)
from PySide6.QtWidgets import (
    QApplication, QGraphicsDropShadowEffect, QPushButton, QStyle,
    QStyledItemDelegate, QStyleOptionViewItem, QWidget,
)

from . import theme

# state key -> (gradient c1, c2, glyph colour, glow colour or None, animated?)
_BTN = {
    "IDLE":     ("#2B313E", "#3A4150", "#9AA0AD", None, False),
    "STARTING": ("#FFC062", "#FF8A1E", "#1A1205", theme.AMBER, True),
    "RUNNING":  ("#28DC86", "#15C2C9", "#04130C", theme.ACCENT, False),
    "STOPPING": ("#FFC062", "#FF8A1E", "#1A1205", theme.AMBER, True),
    "ERROR":    ("#FF7384", "#E0294E", "#FFFFFF", theme.RED, False),
}


def make_shadow(widget: QWidget, blur: int = 28, dy: int = 10, alpha: int = 150):
    effect = QGraphicsDropShadowEffect(widget)
    effect.setBlurRadius(blur)
    effect.setXOffset(0)
    effect.setYOffset(dy)
    effect.setColor(QColor(0, 0, 0, alpha))
    widget.setGraphicsEffect(effect)
    return effect


def _rgba(hex_color: str, alpha: int) -> QColor:
    c = QColor(hex_color)
    c.setAlpha(alpha)
    return c


def _scaled_font(base: QFont, delta_px: int, weight: QFont.Weight | None = None) -> QFont:
    """Return a copy of `base` resized by `delta_px`, working whether the base
    font was defined in points or pixels (our stylesheet uses pixels, so
    pointSizeF() is -1 — using it directly would yield invalid sizes)."""
    f = QFont(base)
    if base.pixelSize() > 0:
        f.setPixelSize(max(1, base.pixelSize() + delta_px))
    elif base.pointSizeF() > 0:
        f.setPointSizeF(max(1.0, base.pointSizeF() + delta_px))
    else:
        f.setPixelSize(max(1, 13 + delta_px))
    if weight is not None:
        f.setWeight(weight)
    return f


class StatusDot(QWidget):
    """A small glowing status indicator with an optional pulse."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedSize(16, 16)
        self._color = QColor(theme.TEXT_DIM)
        self._pulse = False
        self._phase = 1.0
        self._dir = -1
        self._timer = QTimer(self)
        self._timer.setInterval(40)
        self._timer.timeout.connect(self._tick)

    def set_state(self, color_hex: str, pulse: bool):
        self._color = QColor(color_hex)
        self._pulse = pulse
        if pulse and not self._timer.isActive():
            self._timer.start()
        elif not pulse:
            self._timer.stop()
            self._phase = 1.0
        self.update()

    def _tick(self):
        self._phase += self._dir * 0.08
        if self._phase <= 0.35:
            self._phase, self._dir = 0.35, 1
        elif self._phase >= 1.0:
            self._phase, self._dir = 1.0, -1
        self.update()

    def paintEvent(self, _event):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        c = self.rect().center()
        center = QPointF(c.x() + 0.5, c.y() + 0.5)
        glow_alpha = int(150 * self._phase)
        halo = QRadialGradient(center, 8)
        halo.setColorAt(0.0, _rgba(self._color.name(), glow_alpha))
        halo.setColorAt(1.0, _rgba(self._color.name(), 0))
        p.setPen(Qt.NoPen)
        p.setBrush(halo)
        p.drawEllipse(center, 8, 8)
        p.setBrush(self._color)
        p.drawEllipse(center, 4, 4)


class PowerButton(QPushButton):
    """A large circular power button with a gradient face, glow and power glyph."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedSize(132, 132)
        self.setCursor(Qt.PointingHandCursor)
        self._key = "IDLE"
        self._phase = 1.0
        self._dir = -1
        self._timer = QTimer(self)
        self._timer.setInterval(33)
        self._timer.timeout.connect(self._tick)

    def sizeHint(self) -> QSize:
        return QSize(132, 132)

    def set_status(self, key: str):
        self._key = key if key in _BTN else "IDLE"
        animated = _BTN[self._key][4]
        if animated and not self._timer.isActive():
            self._timer.start()
        elif not animated:
            self._timer.stop()
            self._phase = 1.0
        self.update()

    def _tick(self):
        self._phase += self._dir * 0.05
        if self._phase <= 0.45:
            self._phase, self._dir = 0.45, 1
        elif self._phase >= 1.0:
            self._phase, self._dir = 1.0, -1
        self.update()

    def paintEvent(self, _event):
        c1, c2, glyph_hex, glow_hex, _anim = _BTN[self._key]
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)

        rect = self.rect()
        side = min(rect.width(), rect.height())
        center = QPointF(rect.center().x() + 0.5, rect.center().y() + 0.5)
        radius = side / 2 - 16
        if self.isDown():
            radius -= 1.5

        # soft outer glow / halo
        if glow_hex:
            halo_r = radius * (1.55 + 0.12 * self._phase)
            halo = QRadialGradient(center, halo_r)
            halo.setColorAt(0.55, _rgba(glow_hex, int(130 * self._phase)))
            halo.setColorAt(1.0, _rgba(glow_hex, 0))
            p.setPen(Qt.NoPen)
            p.setBrush(halo)
            p.drawEllipse(center, halo_r, halo_r)

        # main disc
        grad = QLinearGradient(center.x() - radius, center.y() - radius,
                               center.x() + radius, center.y() + radius)
        grad.setColorAt(0.0, QColor(c1))
        grad.setColorAt(1.0, QColor(c2))
        p.setPen(Qt.NoPen)
        p.setBrush(grad)
        p.drawEllipse(center, radius, radius)

        # glossy top highlight
        gloss = QRadialGradient(QPointF(center.x(), center.y() - radius * 0.5), radius * 1.2)
        gloss.setColorAt(0.0, _rgba("#FFFFFF", 38))
        gloss.setColorAt(0.6, _rgba("#FFFFFF", 0))
        p.setBrush(gloss)
        p.drawEllipse(center, radius, radius)

        # inner ring
        p.setBrush(Qt.NoBrush)
        p.setPen(QPen(_rgba("#FFFFFF", 22), 1))
        p.drawEllipse(center, radius - 1, radius - 1)

        # power glyph
        gr = radius * 0.42
        pen = QPen(QColor(glyph_hex), max(2.0, radius * 0.11))
        pen.setCapStyle(Qt.RoundCap)
        p.setPen(pen)
        arc_rect = QRectF(center.x() - gr, center.y() - gr, gr * 2, gr * 2)
        p.drawArc(arc_rect, 125 * 16, 290 * 16)  # ring with a gap at the top
        p.drawLine(QPointF(center.x(), center.y() - gr * 1.15),
                   QPointF(center.x(), center.y() - gr * 0.05))


class _SelectionMixin:
    """Draws the cell background / selection exactly like the style would, but
    without the default text, so a delegate can render its own content on top."""

    def _draw_background(self, painter, option, index):
        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)  # type: ignore[attr-defined]
        text = opt.text
        opt.text = ""
        widget = opt.widget
        style = widget.style() if widget else QApplication.style()
        style.drawControl(QStyle.CE_ItemViewItem, opt, painter, widget)
        return text, bool(option.state & QStyle.State_Selected)


class NameDelegate(QStyledItemDelegate, _SelectionMixin):
    """Server name in bold with a dim host:port subtitle underneath."""

    HOST_ROLE = Qt.UserRole + 1

    def paint(self, painter, option, index):
        text, selected = self._draw_background(painter, option, index)
        sub = index.data(self.HOST_ROLE)
        painter.save()
        painter.setRenderHint(QPainter.Antialiasing)
        r = option.rect.adjusted(16, 0, -10, 0)

        painter.setFont(_scaled_font(option.font, 1, QFont.DemiBold))
        painter.setPen(QColor("#FFFFFF") if selected else QColor(theme.TEXT))

        if sub:
            painter.drawText(QRectF(r.x(), r.y() + 7, r.width(), r.height() / 2),
                             Qt.AlignLeft | Qt.AlignVCenter, text)
            painter.setFont(_scaled_font(option.font, -2))
            painter.setPen(QColor(theme.TEXT_DIM))
            painter.drawText(QRectF(r.x(), r.y() + r.height() / 2 - 4, r.width(), r.height() / 2),
                             Qt.AlignLeft | Qt.AlignVCenter, str(sub))
        else:
            painter.drawText(r, Qt.AlignLeft | Qt.AlignVCenter, text)
        painter.restore()

    def sizeHint(self, option, index):
        return QSize(super().sizeHint(option, index).width(), 56)


class PingPillDelegate(QStyledItemDelegate, _SelectionMixin):
    """Latency rendered as a coloured rounded pill."""

    def paint(self, painter, option, index):
        text, _selected = self._draw_background(painter, option, index)
        painter.save()
        painter.setRenderHint(QPainter.Antialiasing)

        digits = "".join(ch for ch in text if ch.isdigit())
        if not digits:  # unreachable
            painter.setPen(QColor(theme.TEXT_FAINT))
            painter.drawText(option.rect.adjusted(14, 0, 0, 0),
                             Qt.AlignLeft | Qt.AlignVCenter, text or "—")
            painter.restore()
            return

        color = QColor(theme.ping_color(int(digits)))
        fm = QFontMetrics(option.font)
        tw = fm.horizontalAdvance(text)
        pill_w, pill_h = tw + 22, 24
        x = option.rect.left() + 12
        y = option.rect.center().y() - pill_h / 2 + 0.5
        pill = QRectF(x, y, pill_w, pill_h)

        painter.setPen(Qt.NoPen)
        painter.setBrush(_rgba(color.name(), 42))
        painter.drawRoundedRect(pill, pill_h / 2, pill_h / 2)
        painter.setPen(color)
        painter.drawText(pill, Qt.AlignCenter, text)
        painter.restore()

    def sizeHint(self, option, index):
        return QSize(118, 56)
