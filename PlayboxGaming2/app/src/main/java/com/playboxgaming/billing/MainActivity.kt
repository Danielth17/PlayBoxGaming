package com.playboxgaming.billing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Background = Color(0xFF0C1019)
private val Panel = Color(0xFF151D2C)
private val Input = Color(0xFF0F1520)
private val Outline = Color(0xFF2C384B)
private val White = Color(0xFFF4F7FD)
private val Muted = Color(0xFF9DAABD)
private val Mint = Color(0xFF35D6A0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Background,
                    surface = Panel,
                    onSurface = White,
                    primary = Mint
                )
            ) {
                BillingScreen()
            }
        }
    }
}
private data class GamingSession(
    val id: String,
    val name: String,
    val hourlyPaise: Long,
    val start: Long,
    val end: Long = 0L,
    val paidAt: Long = 0L,
    val extrasPaise: Long = 0L,
    val isQuickSale: Boolean = false,
    val discountPaise: Long = 0L
) {
    fun charge(now: Long): Long {
        val elapsed = ((if (end == 0L) now else end) - start).coerceAtLeast(0L)
        return BigDecimal.valueOf(hourlyPaise)
            .multiply(BigDecimal.valueOf(elapsed))
            .divide(BigDecimal.valueOf(3_600_000L), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    fun total(now: Long): Long =
        (charge(now) - discountPaise).coerceAtLeast(0L) + extrasPaise
}

private fun money(paise: Long): String =
    "₹" + BigDecimal.valueOf(paise, 2).toPlainString()

private fun localTimeAt(millis: Long) =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

private fun clock(millis: Long): String =
    localTimeAt(millis).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun elapsedLabel(start: Long, end: Long): String {
    val minutes = ((end - start).coerceAtLeast(0L)) / 60_000L
    return "${minutes / 60}h ${minutes % 60}m"
}

private fun readSessions(raw: String): List<GamingSession> {
    val data = JSONArray(raw)
    return List(data.length()) { index ->
        val item = data.getJSONObject(index)
        GamingSession(
            item.getString("id"),
            item.getString("name"),
            item.getLong("rate"),
            item.getLong("start"),
            item.getLong("end"),
            item.getLong("paid"),
            item.optLong("extras", 0L),
            item.optBoolean("quickSale", false),
            item.optLong("discount", 0L)
        )
    }
}

private fun encodeSessions(sessions: List<GamingSession>): String =
    JSONArray().apply {
        sessions.forEach { session ->
            put(JSONObject().apply {
                put("id", session.id)
                put("name", session.name)
                put("rate", session.hourlyPaise)
                put("start", session.start)
                put("end", session.end)
                put("paid", session.paidAt)
                put("extras", session.extrasPaise)
                put("discount", session.discountPaise)
                put("quickSale", session.isQuickSale)
            })
        }
    }.toString()

private enum class DiscountMode(val label: String) {
    NONE("None"),
    AMOUNT("Amount (₹)"),
    PERCENT("Percentage (%)"),
    TIME("Free play time")
}

private fun billingDecimal(text: String): BigDecimal? {
    val input = text.trim().replace(',', '.')
    return if (input.matches(Regex("""[0-9]{1,6}(\.[0-9]{1,2})?"""))) {
        BigDecimal(input)
    } else {
        null
    }
}

@Composable
private fun BillingScreen() {
    val context = LocalContext.current
    val storage = remember(context) {
        context.getSharedPreferences("playbox_billing", Context.MODE_PRIVATE)
    }
    val loaded = remember(storage) {
        runCatching { readSessions(storage.getString("sessions", "[]") ?: "[]") }
    }
    var sessions by remember(loaded) { mutableStateOf(loaded.getOrDefault(emptyList())) }
    var name by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("120") }
    var chosenStart by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<GamingSession?>(null) }
    var stopTarget by remember { mutableStateOf<GamingSession?>(null) }
    var stopTime by remember { mutableStateOf(0L) }
    var discountMode by remember { mutableStateOf(DiscountMode.NONE) }
    var discountInput by remember { mutableStateOf("") }
    var freeHours by remember { mutableStateOf("") }
    var freeMinutes by remember { mutableStateOf("") }
    var extrasInput by remember { mutableStateOf("") }
    var showQuickSale by remember { mutableStateOf(false) }
    var quickSaleInput by remember { mutableStateOf("") }
    var quickSaleError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    fun showMessage(text: String, error: Boolean = false) {
        message = text
        isError = error
    }

    fun save(next: List<GamingSession>): Boolean {
        if (loaded.isFailure) {
            showMessage("Saved sessions could not be read. Changes were not made.", true)
            return false
        }
        if (!storage.edit().putString("sessions", encodeSessions(next)).commit()) {
            showMessage("Could not save changes. Please try again.", true)
            return false
        }
        sessions = next
        now = System.currentTimeMillis()
        showMessage("Saved locally ✓")
        return true
    }

    val active = sessions.filter { it.end == 0L }
    val history = sessions.filter { it.end != 0L }.sortedByDescending { it.end }
    val today = localTimeAt(now).toLocalDate()
    val collection = history.filter {
        it.paidAt != 0L && localTimeAt(it.paidAt).toLocalDate() == today
    }.sumOf { it.total(now) }

    if (showQuickSale) {
        val input = quickSaleInput.trim().replace(',', '.')
        val amountPaise = if (input.matches(Regex("""\d{1,6}(\.\d{1,2})?"""))) {
            BigDecimal(input).movePointRight(2).longValueExact()
        } else {
            0L
        }

        AlertDialog(
            onDismissRequest = { showQuickSale = false },
            title = { Text("Quick sale") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the total amount received.")
                    FieldLabel("Sale amount (₹)")
                    BillingField(
                        quickSaleInput,
                        {
                            quickSaleInput = it
                            quickSaleError = ""
                        },
                        "e.g. 50.00",
                        KeyboardType.Decimal
                    )
                    if (quickSaleError.isNotEmpty()) {
                        Text(quickSaleError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = amountPaise > 0L,
                    onClick = {
                        val paid = System.currentTimeMillis()
                        val sale = GamingSession(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "Quick sale",
                            hourlyPaise = 0L,
                            start = paid,
                            end = paid,
                            paidAt = paid,
                            extrasPaise = amountPaise,
                            isQuickSale = true
                        )
                        if (save(listOf(sale) + sessions)) {
                            showQuickSale = false
                        } else {
                            quickSaleError = message
                        }
                    }
                ) {
                    Text("Record payment")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickSale = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val stopping = stopTarget
    if (stopping != null) {
        val extras: Long? = if (extrasInput.isBlank()) {
            0L
        } else {
            billingDecimal(extrasInput)?.movePointRight(2)?.longValueExact()
        }

        val gaming = stopping.charge(stopTime)

        val discount: Long? = when (discountMode) {
            DiscountMode.NONE -> 0L

            DiscountMode.AMOUNT -> {
                billingDecimal(discountInput)
                    ?.movePointRight(2)
                    ?.longValueExact()
                    ?.takeIf { it <= gaming }
            }

            DiscountMode.PERCENT -> {
                billingDecimal(discountInput)
                    ?.takeIf { it <= BigDecimal.valueOf(100L) }
                    ?.let { percent ->
                        BigDecimal.valueOf(gaming)
                            .multiply(percent)
                            .movePointLeft(2)
                            .setScale(0, RoundingMode.HALF_UP)
                            .longValueExact()
                    }
            }

            DiscountMode.TIME -> {
                val hours = freeHours.trim().ifEmpty { "0" }.toLongOrNull()
                val minutes = freeMinutes.trim().ifEmpty { "0" }.toLongOrNull()
                val elapsed = stopTime - stopping.start

                if (hours == null || minutes == null ||
                    hours !in 0L..(elapsed / 3_600_000L) ||
                    minutes !in 0L..59L
                ) {
                    null
                } else {
                    val freeMillis = hours * 3_600_000L + minutes * 60_000L
                    if (freeMillis > elapsed) {
                        null
                    } else {
                        gaming - stopping.charge(stopTime - freeMillis)
                    }
                }
            }
        }

        var menuOpen by remember { mutableStateOf(false) }
        var finishError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { stopTarget = null },
            title = { Text("Finish session") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Gaming: ${money(gaming)}")

                    FieldLabel("Discount (gaming only)")
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }) {
                            Text("${discountMode.label} ▾")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DiscountMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        discountMode = mode
                                        discountInput = ""
                                        menuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    when (discountMode) {
                        DiscountMode.NONE -> Unit

                        DiscountMode.AMOUNT, DiscountMode.PERCENT -> {
                            BillingField(
                                discountInput,
                                { discountInput = it },
                                if (discountMode == DiscountMode.AMOUNT) "e.g. 50" else "e.g. 10",
                                KeyboardType.Decimal
                            )
                        }

                        DiscountMode.TIME -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    FieldLabel("Hours")
                                    BillingField(freeHours, { freeHours = it }, "0", KeyboardType.Number)
                                }
                                Column(Modifier.weight(1f)) {
                                    FieldLabel("Minutes")
                                    BillingField(freeMinutes, { freeMinutes = it }, "0", KeyboardType.Number)
                                }
                            }
                        }
                    }

                    if (discount == null) {
                        val error = when (discountMode) {
                            DiscountMode.AMOUNT -> "Enter an amount no greater than the gaming charge."
                            DiscountMode.PERCENT -> "Enter a percentage from 0 to 100."
                            DiscountMode.TIME -> "Enter whole hours and minutes (0–59). Free time cannot exceed play time."
                            DiscountMode.NONE -> ""
                        }
                        Text(error, color = MaterialTheme.colorScheme.error)
                    } else if (discount > 0L) {
                        Text("Discount: −${money(discount)}")
                    }

                    FieldLabel("Extras (₹)")
                    BillingField(extrasInput, { extrasInput = it }, "0.00", KeyboardType.Decimal)

                    if (extras == null) {
                        Text("Enter an extras amount like 50 or 50.25.",
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (extras != null && discount != null) {
                        Text(
                            "Total: ${money(gaming - discount + extras)}",
                            color = Mint,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (finishError.isNotEmpty()) {
                        Text(finishError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = extras != null && discount != null,
                    onClick = {
                        if (extras != null && discount != null) {
                            val saved = save(sessions.map {
                                if (it.id == stopping.id) {
                                    it.copy(
                                        end = stopTime,
                                        extrasPaise = extras,
                                        discountPaise = discount
                                    )
                                } else {
                                    it
                                }
                            })
                            if (saved) stopTarget = null else finishError = message
                        }
                    }
                ) { Text("Stop & save") }
            },
            dismissButton = {
                TextButton(onClick = { stopTarget = null }) { Text("Cancel") }
            }
        )
    }

    val pendingDelete = deleteTarget
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this session?") },
            text = {
                Text("This removes ${pendingDelete.name}'s record and any payment from the collection total.")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (save(sessions.filterNot { it.id == pendingDelete.id })) {
                        deleteTarget = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF141E32), Background, Background)))
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("🎮 PLAYBOX GAMING", color = White, fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold)
                Text("SESSION & BILLING MANAGER", color = Muted, fontSize = 10.sp,
                    letterSpacing = 1.sp)
            }
        }

        if (loaded.isFailure) {
            Text("Could not read saved sessions. Changes are disabled.",
                color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Today's collection", money(collection), Modifier.weight(1f))
            SummaryCard("Active sessions", active.size.toString(), Modifier.weight(1f))
        }

        SectionTitle("+ Add new session")
        BillingCard {
            FieldLabel("Customer name")
            BillingField(name, { name = it }, "e.g. Mansoor")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Hourly rate (₹)")
                    Spacer(Modifier.height(6.dp))
                    BillingField(rate, { rate = it }, "120", KeyboardType.Decimal)
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("Start time")
                    Spacer(Modifier.height(6.dp))
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(shape)
                            .background(Input)
                            .border(1.dp, Outline, shape)
                            .clickable(role = Role.Button) {
                                val initial = localTimeAt(chosenStart ?: System.currentTimeMillis())
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        chosenStart = LocalDate.now()
                                            .atTime(hour, minute)
                                            .atZone(ZoneId.systemDefault())
                                            .toInstant()
                                            .toEpochMilli()
                                    },
                                    initial.hour,
                                    initial.minute,
                                    true
                                ).show()
                            }
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(clock(chosenStart ?: now), Modifier.align(Alignment.CenterStart),
                            color = White)
                        Text("⌄", Modifier.align(Alignment.CenterEnd), color = White)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPreview("Use current time", Modifier.weight(1f), onClick = {
                    chosenStart = null
                    message = ""
                })
                ActionPreview("Save session", Modifier.weight(1f), gradient = true, onClick = {
                    val customer = name.trim()
                    val rateText = rate.trim().replace(',', '.')
                    val paise = if (rateText.matches(Regex("""\d{1,6}(\.\d{1,2})?"""))) {
                        BigDecimal(rateText).movePointRight(2).toLong()
                    } else {
                        0L
                    }
                    val current = System.currentTimeMillis()
                    val start = chosenStart ?: current
                    when {
                        customer.isEmpty() -> showMessage("Enter the customer name.", true)
                        paise <= 0L -> showMessage("Enter a positive rate with up to two decimals.", true)
                        start > current -> showMessage("Start time cannot be later than now.", true)
                        else -> {
                            val session = GamingSession(
                                java.util.UUID.randomUUID().toString(), customer, paise, start
                            )
                            if (save(listOf(session) + sessions)) {
                                name = ""
                                chosenStart = null
                            }
                        }
                    }
                })
            }
            if (message.isNotEmpty()) {
                Text(message, color = if (isError) MaterialTheme.colorScheme.error else Mint,
                    fontSize = 12.sp)
            }
        }

        ActionPreview(
            "+ Quick sale",
            modifier = Modifier.fillMaxWidth(),
            gradient = true,
            onClick = {
                quickSaleInput = ""
                quickSaleError = ""
                showQuickSale = true
            }
        )

        SectionTitle("Active sessions")
        if (active.isEmpty()) {
            BillingCard { FieldLabel("No active sessions yet.") }
        }
        active.forEach { session ->
            BillingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(session.name, color = White, fontSize = 19.sp,
                            fontWeight = FontWeight.Bold)
                        FieldLabel("Started ${clock(session.start)} · ${money(session.hourlyPaise)}/hour")
                    }
                    StatusBadge("ACTIVE")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Current amount")
                        Text(money(session.charge(now)), color = Mint, fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold)
                        FieldLabel("${elapsedLabel(session.start, now)} elapsed")
                    }
                    ActionPreview("Calculate & Stop", gradient = true, onClick = {
                        stopTime = System.currentTimeMillis().coerceAtLeast(session.start)
                        extrasInput = ""
                        discountMode = DiscountMode.NONE
                        discountInput = ""
                        freeHours = ""
                        freeMinutes = ""
                        stopTarget = session
                    })
                }
            }
        }

        SectionTitle("Session & sales history")
        if (history.isEmpty()) {
            BillingCard { FieldLabel("Stopped sessions and quick sales will appear here.") }
        }
        history.forEach { session ->
            BillingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(session.name, color = White, fontSize = 19.sp,
                            fontWeight = FontWeight.Bold)
                        val date = localTimeAt(session.start)
                            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        if (session.isQuickSale) {
                            FieldLabel("$date · ${clock(session.start)}")
                            FieldLabel(if (session.paidAt != 0L) "Paid" else "Payment reversed")
                        } else {
                            FieldLabel("$date · ${clock(session.start)}–${clock(session.end)}")
                            FieldLabel("${elapsedLabel(session.start, session.end)} · ${money(session.hourlyPaise)}/hr")

                            if (session.discountPaise > 0L || session.extrasPaise > 0L) {
                                val breakdown = buildString {
                                    append("Gaming ${money(session.charge(now))}")
                                    if (session.discountPaise > 0L) {
                                        append(" − discount ${money(session.discountPaise)}")
                                    }
                                    if (session.extrasPaise > 0L) {
                                        append(" + extras ${money(session.extrasPaise)}")
                                    }
                                }
                                FieldLabel(breakdown)
                            }
                        }
                    }
                    Text(
                        money(session.total(now)),
                        color = White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val paid = session.paidAt != 0L
                    ActionPreview(
                        if (paid) "Undo paid" else "Mark paid",
                        gradient = !paid,
                        onClick = {
                            val paymentTime = if (paid) 0L else System.currentTimeMillis()
                            save(sessions.map {
                                if (it.id == session.id) it.copy(paidAt = paymentTime) else it
                            })
                        }
                    )
                    ActionPreview("Delete", danger = true, onClick = {
                        deleteTarget = session
                    })
                }
            }
        }
    }
}

@Composable
private fun BillingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(17.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Panel, shape)
            .border(1.dp, Outline, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) {
    BillingCard(modifier) {
        FieldLabel(label)
        Text(value, color = White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Muted, fontSize = 12.sp)
}

@Composable
private fun StatusBadge(text: String) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        modifier = Modifier
            .background(Color(0xFF123329), shape)
            .border(1.dp, Color(0xFF235B4C), shape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        color = Mint,
        fontSize = 10.sp
    )
}

@Composable
private fun BillingField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(hint, color = Muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = White,
            unfocusedTextColor = White,
            focusedBorderColor = Mint,
            unfocusedBorderColor = Outline,
            focusedContainerColor = Input,
            unfocusedContainerColor = Input
        )
    )
}

@Composable
private fun ActionPreview(
    label: String,
    modifier: Modifier = Modifier,
    gradient: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(11.dp)
    val background = when {
        gradient -> Modifier.background(
            Brush.horizontalGradient(listOf(Color(0xFF4279F5), Color(0xFF7653F7))),
            shape
        )
        danger -> Modifier
            .background(Color(0xFF351E2A), shape)
            .border(1.dp, Color(0xFF653447), shape)
        else -> Modifier
            .background(Color(0xFF182233), shape)
            .border(1.dp, Outline, shape)
    }

    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(background)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (danger) Color(0xFFF7A0B2) else White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}