package com.metavirtuoso.conjunction.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.core.onboarding.BirthTextParser
import com.metavirtuoso.conjunction.data.PlaceResult
import com.metavirtuoso.conjunction.data.PlaceSearchException
import com.metavirtuoso.conjunction.data.Places
import com.metavirtuoso.conjunction.ui.components.CelestialSheet
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldSwitchRow
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.OrnateTextField
import com.metavirtuoso.conjunction.ui.components.OrreryLoader
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import com.metavirtuoso.conjunction.ui.theme.rememberGoldShimmer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Birth-place lookup (platform geocoder + offline time zones); a seam for tests and screenshots. */
fun interface PlaceLookup {
    suspend fun search(query: String, date: LocalDate, time: LocalTime?): List<PlaceResult>
}

/** The real lookup: android.location.Geocoder, then the offline time-zone grid. */
fun defaultPlaceLookup(container: AppContainer): PlaceLookup = PlaceLookup { query, date, time ->
    val (grid, table) = withContext(Dispatchers.IO) { container.timeZonesOrNull to container.zoneTable }
    Places.search(container.context, query, grid, table, date, time)
}

internal val chipDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * A confirm chip (SPEC 5d.5): filled = gold-leaf border with the value; empty = outline "Add …".
 * [highlight] marks an ambiguous numeric date; [working] paints a faint shimmer while the
 * on-device model may still fill it (read in the draw phase only).
 */
@Composable
fun ConfirmChip(value: String?, placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier, highlight: Boolean = false, working: Boolean = false) {
    val shape = CutCornerShape(8.dp)
    val phase = rememberGoldShimmer(2400)
    Text(
        value ?: placeholder,
        style = MaterialTheme.typography.titleSmall,
        color = if (value != null) Palette.Moonstone else Palette.GoldPale.copy(alpha = 0.8f),
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(if (value != null) SolidColor(Palette.InkViolet.copy(alpha = 0.9f)) else SolidColor(Palette.Midnight.copy(alpha = 0.8f)), shape)
            .border(if (highlight) 2.dp else 1.dp, if (highlight) SolidColor(Palette.RoseCopper) else if (value != null) GoldBrushes.leaf else SolidColor(Palette.GoldDeep), shape)
            .then(
                if (working) Modifier.graphicsLayer { alpha = 0.99f }.drawWithContent {
                    drawContent()
                    drawRect(GoldBrushes.shimmer(phase.value, 300f), alpha = 0.25f, blendMode = BlendMode.SrcAtop)
                } else Modifier,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    )
}

/** Date / time / place chips with their pickers (onboarding screen 2 and Profile › Birth). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BirthChips(
    date: LocalDate?,
    time: LocalTime?,
    timeUnknown: Boolean,
    place: PlaceResult?,
    placeQuery: String?,
    placeCandidates: List<PlaceResult>,
    onDate: (LocalDate) -> Unit,
    onTime: (LocalTime?, Boolean) -> Unit,
    onPlace: (PlaceResult) -> Unit,
    lookup: PlaceLookup,
    container: AppContainer,
    ambiguousDate: Boolean = false,
    working: Boolean = false,
    searchingPlace: Boolean = false,
) {
    var picking by remember { mutableStateOf<String?>(null) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfirmChip(date?.format(chipDateFormat), stringResource(R.string.onb_chip_add_date), { picking = "date" }, highlight = ambiguousDate && date != null, working = working && date == null)
        ConfirmChip(
            when {
                timeUnknown -> stringResource(R.string.onb_chip_time_unknown)
                time != null -> time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                else -> null
            },
            stringResource(R.string.onb_chip_add_time), { picking = "time" }, working = working && time == null && !timeUnknown,
        )
        ConfirmChip(place?.name, stringResource(R.string.onb_chip_add_place), { picking = "place" }, working = (working || searchingPlace) && place == null)
    }
    when (picking) {
        "date" -> BirthDatePickerDialog(date, onPicked = { onDate(it); picking = null }, onDismiss = { picking = null })
        "time" -> BirthTimeDialog(time, timeUnknown, onDone = { t, unknown -> onTime(t, unknown); picking = null }, onDismiss = { picking = null })
        "place" -> PlaceSheet(container, lookup, date ?: LocalDate.of(1995, 1, 1), time, placeQuery ?: place?.name.orEmpty(), placeCandidates, onChosen = { onPlace(it); picking = null }, onDismiss = { picking = null })
    }
}

/**
 * Birth date picker: nothing pre-selected (it opens at 1995-01-01, SPEC 5d.1.2), 1900-01-01 to
 * today. The 18+ rule is checked on Begin, not by limiting the picker (a neutral age screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDatePickerDialog(initial: LocalDate?, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
        initialDisplayedMonthMillis = (initial ?: LocalDate.of(1995, 1, 1)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        yearRange = 1900..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = BirthTextParser.isAcceptableDate(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate(), today)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) { Text("OK", color = Palette.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.MoonstoneDim) } },
        colors = DatePickerDefaults.colors(containerColor = Palette.InkViolet),
    ) {
        DatePicker(state = state, colors = DatePickerDefaults.colors(containerColor = Palette.InkViolet, selectedDayContainerColor = Palette.Gold, selectedDayContentColor = Palette.Midnight, selectedYearContainerColor = Palette.Gold, selectedYearContentColor = Palette.Midnight))
    }
}

/** Time picker with the "I don't know my birth time" switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthTimeDialog(time: LocalTime?, unknown: Boolean, onDone: (LocalTime?, Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontKnow by remember { mutableStateOf(unknown) }
    val state = rememberTimePickerState(initialHour = time?.hour ?: 12, initialMinute = time?.minute ?: 0, is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkViolet,
        confirmButton = { TextButton(onClick = { if (dontKnow) onDone(null, true) else onDone(LocalTime.of(state.hour, state.minute), false) }) { Text("OK", color = Palette.Gold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.MoonstoneDim) } },
        text = {
            Column {
                if (!dontKnow) {
                    TimePicker(
                        state = state,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = Palette.Midnight, selectorColor = Palette.Gold,
                            timeSelectorSelectedContainerColor = Palette.Gold, timeSelectorSelectedContentColor = Palette.Midnight,
                            timeSelectorUnselectedContainerColor = Palette.Dusk, timeSelectorUnselectedContentColor = Palette.Moonstone,
                            periodSelectorSelectedContainerColor = Palette.Gold, periodSelectorSelectedContentColor = Palette.Midnight,
                        ),
                    )
                }
                GoldSwitchRow(dontKnow, { dontKnow = it }, "I don't know my birth time", "Your chart skips the Ascendant, houses and the hour pillar. Add it later in Profile.")
            }
        },
    )
}

/** Place search sheet: up to 5 results ("Change"), or coordinates. */
@Composable
internal fun PlaceSheet(
    container: AppContainer,
    lookup: PlaceLookup,
    date: LocalDate,
    time: LocalTime?,
    initialQuery: String,
    initialResults: List<PlaceResult>,
    onChosen: (PlaceResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(initialResults.take(5)) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var latText by rememberSaveable { mutableStateOf("") }
    var lonText by rememberSaveable { mutableStateOf("") }
    fun search() {
        focus.clearFocus()
        searching = true; error = null
        scope.launch {
            try {
                results = lookup.search(query, date, time).take(5)
                if (results.isEmpty()) error = "No places found. Try adding the country."
            } catch (e: PlaceSearchException) {
                error = e.message
            } catch (e: Exception) {
                error = "Place search failed. Check your connection or enter coordinates."
            } finally {
                searching = false
            }
        }
    }
    CelestialSheet(onDismiss = onDismiss) {
        GoldText("Where you were born", style = MaterialTheme.typography.titleLarge, heading = true)
        OrnateTextField(
            query, { query = it }, label = "City or town of birth",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !searching) search() }),
        )
        GoldButton(if (searching) "Searching…" else "Search", enabled = query.isNotBlank() && !searching, onClick = ::search, modifier = Modifier.fillMaxWidth())
        if (searching) OrreryLoader(size = 40.dp)
        error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodyMedium) }
        results.forEach { place ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onChosen(place) }.padding(vertical = 8.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, color = Palette.Moonstone)
                Text("${place.zoneId} · ${place.offsetLabel}", style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim)
            }
        }
        GhostButton(if (manual) "Hide coordinates" else "Enter coordinates instead", onClick = { manual = !manual }, modifier = Modifier.fillMaxWidth())
        if (manual) {
            OrnateTextField(latText, { latText = it }, label = "Latitude (−90 to 90, north positive)", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OrnateTextField(lonText, { lonText = it }, label = "Longitude (−180 to 180, east positive)", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            GhostButton("Use these coordinates", modifier = Modifier.fillMaxWidth(), onClick = {
                val lat = latText.replace(',', '.').toDoubleOrNull()
                val lon = lonText.replace(',', '.').toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    error = "Enter a latitude between −90 and 90 and a longitude between −180 and 180."
                } else scope.launch {
                    val (grid, table) = withContext(Dispatchers.IO) { container.timeZonesOrNull to container.zoneTable }
                    val label = "%.4f, %.4f".format(Locale.ROOT, lat, lon)
                    onChosen(Places.resolve(query.ifBlank { label }, lat, lon, grid, table, date, time))
                }
            })
        }
        FinePrint(stringResource(R.string.birth_privacy_note))
    }
}
