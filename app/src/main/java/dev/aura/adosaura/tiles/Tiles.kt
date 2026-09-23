package dev.aura.adosaura.tiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aura.adosaura.config.AppEntry
import dev.aura.adosaura.config.AppsConfig
import dev.aura.adosaura.config.Labels
import dev.aura.adosaura.config.TileSpec
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import dev.aura.adosaura.ui.Axis
import dev.aura.adosaura.ui.Pane
import dev.aura.adosaura.ui.freeStorageGb
import dev.aura.adosaura.ui.rememberBatteryPercent
import dev.aura.adosaura.ui.rememberTick
import dev.aura.adosaura.ui.uptimeShort
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One tile, drawn inside its [Pane]. Everything here is text — there is no
 * icon anywhere in the launcher, on purpose.
 */
@Composable
fun TileView(
    spec: TileSpec,
    apps: AppsConfig,
    isActive: Boolean = false,
    onSelect: (() -> Unit)? = null,
    onLaunch: (String) -> Unit = {},
    onResizeWidth: ((Int) -> Unit)? = null,
    onResizeHeight: ((Int) -> Unit)? = null,
    onResizeEnd: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Pane(
        title = titleOf(spec),
        isActive = isActive,
        modifier = modifier.fillMaxSize(),
        onResizeWidth = onResizeWidth,
        onResizeHeight = onResizeHeight,
        onResizeEnd = onResizeEnd,
        onSelect = onSelect,
        onTitleLongPress = onEdit,
    ) {
        when (TileType.of(spec.type)) {
            TileType.CLOCK -> ClockTile()
            TileType.CALENDAR -> CalendarTile(compact = false)
            TileType.CLOCKCAL -> ClockCalTile()
            TileType.APPS -> AppListTile(
                group = spec.arg ?: dev.aura.adosaura.config.AppsConfigParser.DEFAULT_GROUP,
                entries = apps.entries,
                onLaunch = onLaunch,
            )

            TileType.STATS -> StatsTile()
            TileType.PERF -> PerfTile(modifier)
            TileType.WEATHER -> WeatherTile(modifier)
            null -> UnknownTile(spec.type)
        }
    }
}

// --------------------------------------------------------------------- clock

@Composable
fun ClockTile() {
    val tick = rememberTick()
    val now = Instant.ofEpochMilli(tick).atZone(ZoneId.systemDefault())

    Column(Modifier.fillMaxSize()) {
        Text(
            text = now.toLocalTime().format(HOUR_MINUTE),
            color = Palette.current.fgBright,
            fontSize = Metrics.clockSize,
            fontWeight = FontWeight.Bold,
            fontFamily = AdosauraMono,
        )
        Text(
            text = now.toLocalDate().format(DATE_LINE),
            color = Palette.current.muted,
            fontSize = Metrics.smallSize,
            fontFamily = AdosauraMono,
        )
    }
}

@Composable
fun ClockCalTile() {
    val tick = rememberTick()
    val now = Instant.ofEpochMilli(tick).atZone(ZoneId.systemDefault())

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = now.toLocalTime().format(HOUR_MINUTE),
                color = Palette.current.fgBright,
                fontSize = Metrics.clockSize,
                fontWeight = FontWeight.Bold,
                fontFamily = AdosauraMono,
            )
            Text(
                text = now.toLocalTime().format(SECOND),
                color = Palette.current.accent,
                fontSize = Metrics.bodySize,
                fontFamily = AdosauraMono,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Text(
            text = now.toLocalDate().format(DATE_LINE),
            color = Palette.current.muted,
            fontSize = Metrics.smallSize,
            fontFamily = AdosauraMono,
        )
        Spacer(Modifier.padding(4.dp))
        // The big clock leaves little room: if the whole month does not fit,
        // show just this week's row rather than a header with nothing under it.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val rowHeight = with(LocalDensity.current) { (Metrics.smallSize * 1.5f).toDp() }
            val month = YearMonth.from(now)
            val weeks = (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7
            val rowsThatFit = (maxHeight / rowHeight).toInt()
            MonthGrid(
                month = month,
                today = now.toLocalDate(),
                weekOnly = rowsThatFit < weeks + 1,
                showHeader = rowsThatFit >= 2 || rowsThatFit >= weeks + 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------ calendar

@Composable
fun CalendarTile(compact: Boolean) {
    val tick = rememberTick()
    val now = Instant.ofEpochMilli(tick).atZone(ZoneId.systemDefault())

    Column(Modifier.fillMaxSize()) {
        if (!compact) {
            Text(
                text = now.toLocalDate().format(MONTH_LINE),
                color = Palette.current.fg,
                fontSize = Metrics.bodySize,
                fontWeight = FontWeight.Bold,
                fontFamily = AdosauraMono,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        MonthGrid(
            month = YearMonth.from(now),
            today = now.toLocalDate(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A plain month grid: seven text columns, no boxes, today in the accent
 * colour. Week starts on Monday, the way the calendar on the laptop does.
 */
@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    modifier: Modifier = Modifier,
    weekOnly: Boolean = false,
    showHeader: Boolean = true,
) {
    val firstOfMonth = month.atDay(1)
    val leadingBlanks = firstOfMonth.dayOfWeek.value - DayOfWeek.MONDAY.value
    val daysInMonth = month.lengthOfMonth()

    Column(modifier) {
        if (showHeader) Row(Modifier.fillMaxWidth()) {
            WEEKDAYS.forEach { day ->
                Text(
                    text = day,
                    color = Palette.current.muted,
                    fontSize = Metrics.titleSize,
                    fontFamily = AdosauraMono,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        var day = 1 - leadingBlanks
        val todayRow = day + (today.dayOfMonth - day) / 7 * 7
        while (day <= daysInMonth) {
            if (weekOnly && day != todayRow) {
                day += 7
                continue
            }
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val cell = day + column
                    if (cell in 1..daysInMonth) {
                        val isToday = month.atDay(cell) == today
                        Text(
                            text = cell.toString(),
                            color = if (isToday) Palette.current.accentBright else Palette.current.fg,
                            fontSize = Metrics.smallSize,
                            fontFamily = AdosauraMono,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            day += 7
        }
    }
}

// ---------------------------------------------------------------------- apps

/**
 * A titled list of apps from one group in apps.conf. Text rows only: a name,
 * and a `?` when the package is in the file but not installed any more.
 */
@Composable
fun AppListTile(
    group: String,
    entries: List<AppEntry>,
    onLaunch: (String) -> Unit,
) {
    val rows = entries.filter { group in it.groups || group == it.pkg }
    val names = remember(rows) { Labels.display(rows) }
    val cellPx = rememberTextMeasurer()
        .measure("M", TextStyle(fontSize = Metrics.bodySize, fontFamily = AdosauraMono))
        .size.width
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = if (cellPx > 0) constraints.maxWidth / cellPx else Int.MAX_VALUE
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = "no apps in \"$group\"",
                    color = Palette.current.dim,
                    fontSize = Metrics.smallSize,
                    fontFamily = AdosauraMono,
                )
                return@Column
            }

            rows.forEach { entry ->
                Text(
                    text = Labels.split(names[entry.pkg] ?: entry.label, cols).joinToString("\n"),
                    color = Palette.current.fg,
                    fontSize = Metrics.bodySize,
                    fontFamily = AdosauraMono,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLaunch(entry.pkg) }
                        .padding(vertical = 3.dp),
                )
            }
        }
    }
}

// --------------------------------------------------------------------- stats

@Composable
fun StatsTile() {
    rememberTick()  // uptime advances every minute or so; a tick keeps it fresh
    val battery = rememberBatteryPercent()
    val storage = freeStorageGb()

    Column(Modifier.fillMaxSize()) {
        StatRow("battery", if (battery >= 0) "$battery%" else "?")
        StatRow("storage", if (storage >= 0) "${storage}g" else "?")
        StatRow("uptime", uptimeShort())
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Palette.current.dim,
            fontSize = Metrics.smallSize,
            fontFamily = AdosauraMono,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = Palette.current.fg,
            fontSize = Metrics.smallSize,
            fontFamily = AdosauraMono,
        )
    }
}

// ------------------------------------------------------------------- unknown

/** A tile type layout.conf named but the app has never heard of. */
@Composable
fun UnknownTile(type: String) {
    Text(
        text = "? $type",
        color = Palette.current.red,
        fontSize = Metrics.bodySize,
        fontFamily = AdosauraMono,
    )
}

// ------------------------------------------------------------------ formats

private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
private val SECOND = DateTimeFormatter.ofPattern("ss")
private val DATE_LINE = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val MONTH_LINE = DateTimeFormatter.ofPattern("MMMM yyyy")
private val WEEKDAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
