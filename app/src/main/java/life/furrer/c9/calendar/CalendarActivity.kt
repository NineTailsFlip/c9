package life.furrer.c9.calendar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.CalendarContract
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import life.furrer.nine.core.settings.openNineSettings
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.LongPressKey
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.Async
import life.furrer.nine.core.ui.DatePickerActivity
import life.furrer.nine.core.ui.DatePickerMarkers
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.util.KeyRouter
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.PermissionScreen
import life.furrer.c9.calendar.reminders.ReminderPermission
import life.furrer.c9.calendar.reminders.Reminders
import life.furrer.c9.settings.CalendarSelectionActivity
import life.furrer.nine.core.util.ProviderWatcher
import life.furrer.nine.core.util.Reloader

private const val REQUEST_CALENDAR_PERMISSION = 100
private const val REQUEST_DATE_PICKER = 101
private const val HEADER_SIDE_PADDING_DP = Dimens.SIDE_PADDING_DP

private enum class ViewMode { DAY, THREE_DAY, WEEK, AGENDA }

/** §7.3: "rolling, unbounded" — approximated as a wide-but-bounded window rather than a truly
 *  unbounded query, the same trade-off the calendar's own Search screen (EventSearchActivity)
 *  makes for the same reason. Paged by week like 3-Day/Week are paged by their own span. */
private const val AGENDA_DAYS_BEFORE = 7L
private const val AGENDA_DAYS_AFTER = 60L

/** Hosts the calendar screens (§5.1): owns the focused date, the Day/3-Day view mode, the
 *  header, the hint bar, and the `*` menu, and delegates row content/selection entirely to
 *  whichever [CalendarView] (DayView/MultiDayView) is currently active. */
class CalendarActivity : NineActivity() {

    private var viewMode = ViewMode.DAY
    private var focusedDate: LocalDate = LocalDate.now()
    private var currentView: CalendarView? = null
    /** So a DAVx⁵ sync landing in the background refreshes what's on screen (§5.3). */
    private val providerWatcher = ProviderWatcher(this, CalendarContract.CONTENT_URI) { loadEvents() }

    /** Another app's VIEW (an event, or a date), held until calendar permission is there — the
     *  permission screen comes first and the request is carried out on the onResume after it. */
    private var pendingRequest: CalendarIntentRequest? = null
    /** Started by that request rather than by the user, so handing it on to the event's own
     *  screen should leave Back there returning to the caller, not to a calendar never asked for. */
    private var startedForEvent = false

    // Star tap-vs-hold and 0 tap-vs-hold both wait for key-up or the hold — a bare tap can't be
    // acted on at key-down time. `*` goes through core's [LongPressKey] like every other screen's;
    // 0 still uses the framework's own onKeyLongPress.
    private val starKey = LongPressKey(::openMenu) { openNineSettings("calendar") }
    private var pendingZeroKeyCode: Int? = null
    private var zeroLongPressHandled = false

    private lateinit var dateHeader: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var permissionScreen: PermissionScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(HEADER_SIDE_PADDING_DP), dp(10), dp(HEADER_SIDE_PADDING_DP), dp(8))
        }
        dateHeader = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
        }
        header.addView(dateHeader, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            TextClock(this).apply {
                textSize = 13f
                setTextColor(Palette.GRAY_MUTED)
                // 24h only, no AM/PM — always, not read from the system setting.
                format24Hour = "HH:mm"
                format12Hour = null
            },
        )

        contentContainer = FrameLayout(this)
        hintBar = HintBar(this)
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(contentContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }

        menuSheet = MenuSheet(this)
        permissionScreen = PermissionScreen(this).apply {
            onGrant = { requestCalendarPermission() }
            onLeave = { finish() }
        }
        val root = FrameLayout(this).apply {
            addView(
                mainColumn,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(
                menuSheet,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(
                permissionScreen,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        setContentView(root)

        switchView(ViewMode.DAY)

        // savedInstanceState: a restored instance already carried its request out.
        if (savedInstanceState == null) {
            pendingRequest = CalendarIntents.parse(intent)
            startedForEvent = pendingRequest is CalendarIntentRequest.ViewEvent
        }

        if (EventStore.hasPermission(this) && EventStore.hasWritePermission(this)) {
            loadEvents(landAt = true)
        } else {
            // §9.13's "first run" screen — shown here rather than requesting permission straight
            // away, and shown again from onRequestPermissionsResult on denial, so the same screen
            // covers both "explain before the very first ask" and "explain again and offer to
            // retry after a denial" without a separate persisted flag.
            permissionScreen.show(
                "C9 Calendar needs access to your Calendar to show and edit your events.\n\n" +
                    "C9 has no network permission — it never talks to a server. Syncing (if you use it) is handled separately, e.g. by DAVx⁵.",
            )
        }
    }

    /** singleTop: a VIEW arriving while this screen is already on top lands here instead. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CalendarIntents.parse(intent)?.let { pendingRequest = it }
    }

    /** Carries out [pendingRequest] (only ever called with permission granted) — returns whether
     *  it reloaded the events itself, so the caller can skip its own plain reload. */
    private fun consumePendingRequest(): Boolean {
        val request = pendingRequest ?: return false
        pendingRequest = null
        when (request) {
            is CalendarIntentRequest.ViewTime -> {
                focusedDate = Instant.ofEpochMilli(request.millis).atZone(ZoneId.systemDefault()).toLocalDate()
                switchView(ViewMode.DAY)
                loadEvents(landAt = true)
                return true
            }
            is CalendarIntentRequest.ViewEvent -> {
                startActivity(
                    Intent(this, EventDetailActivity::class.java).apply {
                        putExtra(EventDetailActivity.EXTRA_EVENT_ID, request.eventId)
                        request.instanceStartMillis?.let { putExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, it) }
                    },
                )
                if (startedForEvent) {
                    finish()
                    return true
                }
                // Already open in its own right: Back from the event comes back here, to its day.
                val instanceStart = request.instanceStartMillis ?: return false
                focusedDate = Instant.ofEpochMilli(instanceStart).atZone(ZoneId.systemDefault()).toLocalDate()
                switchView(ViewMode.DAY)
                loadEvents(landAt = true)
                return true
            }
            // INSERT/EDIT are EventEditActivity's own intent filters, never routed here.
            else -> return false
        }
    }

    // Requested together: on this device (confirmed via adb) granting READ_CALENDAR alone does
    // NOT also grant WRITE_CALENDAR, even though they share a permission group on some Android
    // versions — every write would otherwise silently fail with no explanation beyond a generic
    // "couldn't save" toast.
    private fun requestCalendarPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            REQUEST_CALENDAR_PERMISSION,
        )
    }

    override fun onResume() {
        super.onResume()
        // Registering before READ_CALENDAR is actually granted throws a SecurityException
        // rather than failing quietly like a query does (see M1) — onResume fires again once
        // the permission dialog resolves, so this just waits for that pass.
        if (EventStore.hasPermission(this) && EventStore.hasWritePermission(this)) {
            permissionScreen.hide()
            providerWatcher.start()
            ReminderPermission.maybeRequest(this)
            // Calendar or notification access may have only just been granted.
            Reminders.ensureRunning(this)
            // Toggling calendar visibility in CalendarSelectionActivity changes a SharedPreferences
            // value, not the provider itself, so the ContentObserver never fires for it — reload
            // on every resume to pick that up too (preserving the selection, since most resumes
            // are just "came back from a screen that changed nothing calendar-related").
            if (!consumePendingRequest()) loadEvents()
        } else if (!permissionScreen.isShowing) {
            // Covers permission revoked via system Settings while this Activity was backgrounded
            // — onCreate's own explainer only runs once, so this is the other path back to it.
            permissionScreen.show(
                "C9 Calendar needs access to your Calendar to show and edit your events.\n\n" +
                    "C9 has no network permission — it never talks to a server. Syncing (if you use it) is handled separately, e.g. by DAVx⁵.",
            )
        }
    }

    override fun onPause() {
        super.onPause()
        providerWatcher.stop()
        starKey.reset()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR_PERMISSION) {
            if (EventStore.hasPermission(this) && EventStore.hasWritePermission(this)) {
                permissionScreen.hide()
                loadEvents(landAt = true)
            } else {
                permissionScreen.show(
                    "C9 Calendar still needs access to your Calendar to show and edit your events.\n\n" +
                        "C9 has no network permission — it never talks to a server. Syncing (if you use it) is handled separately, e.g. by DAVx⁵.",
                )
            }
        }
    }

    /** Sets [DatePickerMarkers.loader] to mark days with an event, then opens the shared
     *  [DatePickerActivity] — the loader is read and cleared by that Activity's own onCreate, so
     *  it only ever applies to this one launch. */
    private fun openDatePicker() {
        DatePickerMarkers.loader = { context, start, endExclusive ->
            if (!EventStore.hasPermission(context)) {
                emptySet()
            } else {
                val zone = ZoneId.systemDefault()
                val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMillis = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
                val selectedIds = CalendarStore.getSelectedCalendarIds(context)
                EventStore.queryRange(context, startMillis, endMillis, selectedIds)
                    .flatMap { CalendarRows.datesOf(it, zone) }
                    .filter { it >= start && it < endExclusive }
                    .toSet()
            }
        }
        startActivityForResult(Intent(this, DatePickerActivity::class.java), REQUEST_DATE_PICKER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DATE_PICKER && resultCode == RESULT_OK && data != null) {
            val epochDay = data.getLongExtra(DatePickerActivity.EXTRA_EPOCH_DAY, -1L)
            if (epochDay >= 0) {
                focusedDate = LocalDate.ofEpochDay(epochDay)
                switchView(ViewMode.DAY)
                loadEvents(landAt = true)
            }
        }
    }

    private fun switchView(mode: ViewMode) {
        viewMode = mode
        val view: CalendarView = when (mode) {
            ViewMode.DAY -> DayView(this)
            ViewMode.THREE_DAY -> MultiDayView(this)
            ViewMode.WEEK -> MultiDayView(this, daySpan = 7L, compact = true)
            ViewMode.AGENDA -> AgendaView(this)
        }
        currentView = view
        contentContainer.removeAllViews()
        contentContainer.addView(
            view.rootView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun rangeForCurrentView(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        if (viewMode == ViewMode.AGENDA) {
            val start = focusedDate.minusDays(AGENDA_DAYS_BEFORE).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = focusedDate.plusDays(AGENDA_DAYS_AFTER).atStartOfDay(zone).toInstant().toEpochMilli()
            return start to end
        }
        val days = when (viewMode) {
            ViewMode.DAY -> 1L
            ViewMode.THREE_DAY -> 3L
            ViewMode.WEEK -> 7L
            ViewMode.AGENDA -> error("handled above")
        }
        val start = focusedDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = focusedDate.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private val pendingLandAt = PendingLandAt()

    /** The range the next load reads, set on the main thread by [loadEvents]. The background
     *  read must not call [rangeForCurrentView] itself: [viewMode] and [focusedDate] change on the
     *  main thread meanwhile, and a view switch between its two reads of [viewMode] hit the
     *  `error("handled above")` branch, an uncaught exception on the worker thread. */
    @Volatile
    private var loadRange: Pair<Long, Long> = 0L to 0L

    // Coalesced rather than a plain Async.run per trigger: onResume, the provider watcher, paging
    // and post-write reloads all land here, and a CalDAV sync fires the watcher many times over.
    // Every change to the range comes with a loadEvents of its own, so a load started after the
    // change follows: a result already in flight when the page turned is still rendered (into the
    // new window, as before) and a fresh load for the new page follows straight away.
    private val eventsLoader = Reloader(
        background = {
            val (start, end) = loadRange
            EventStore.queryRange(this, start, end, CalendarStore.getSelectedCalendarIds(this))
        },
        onResult = ::onEventsLoaded,
    )

    private fun onEventsLoaded(events: List<EventEntry>) {
        renderCurrentView(events, pendingLandAt.take(stale = eventsLoader.isStale))
    }

    /** [landAt] null preserves whatever selection the view's own data-changed clamping lands on
     *  (a background sync refresh shouldn't jump the user's place); true/false forces the first/
     *  last selectable row (paging, jumping to today, rolling into an adjacent day/page).
     *
     *  Requests are coalesced ([eventsLoader]), so [landAt] waits in [pendingLandAt] for the
     *  render that reflects it — see [PendingLandAt]. */
    private fun loadEvents(landAt: Boolean? = null) {
        pendingLandAt.request(landAt)
        loadRange = rangeForCurrentView()
        eventsLoader.request()
    }

    private fun renderCurrentView(events: List<EventEntry>, landAt: Boolean?) {
        val zone = ZoneId.systemDefault()
        val (rangeStart, rangeEnd) = rangeForCurrentView()
        val window = Instant.ofEpochMilli(rangeStart).atZone(zone).toLocalDate()..
            Instant.ofEpochMilli(rangeEnd - 1).atZone(zone).toLocalDate()
        val eventsByDay = CalendarRows.groupByDay(events, window, zone)
        currentView?.render(focusedDate, eventsByDay, System.currentTimeMillis())
        if (landAt != null) currentView?.selectEdge(atStart = landAt)
        updateHeader()
    }

    private fun updateHeader() {
        dateHeader.text = when (viewMode) {
            ViewMode.DAY -> {
                val dow = focusedDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val month = focusedDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                "$dow ${focusedDate.dayOfMonth} $month"
            }
            ViewMode.THREE_DAY -> {
                val end = focusedDate.plusDays(2)
                val startMonth = focusedDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val endMonth = end.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                "${focusedDate.dayOfMonth} $startMonth – ${end.dayOfMonth} $endMonth"
            }
            ViewMode.WEEK -> {
                val end = focusedDate.plusDays(6)
                val startMonth = focusedDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val endMonth = end.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                "${focusedDate.dayOfMonth} $startMonth – ${end.dayOfMonth} $endMonth"
            }
            ViewMode.AGENDA -> "Agenda"
        }
        // "# view <mode>" names the mode # switches TO (not the one you're already in) — folds
        // "what # does" into one segment instead of a separate arrow-glyph paging hint, since the
        // ← → arrows render too thin/faint to read on this display.
        hintBar.setText(
            when (viewMode) {
                ViewMode.DAY -> "* menu · 0 today · # view 3 days"
                ViewMode.THREE_DAY -> "* menu · 0 today · # view week"
                ViewMode.WEEK -> "* menu · 0 today · # view agenda"
                ViewMode.AGENDA -> "* menu · 0 today · # view day"
            },
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (permissionScreen.handleKeyDown(keyCode)) return true
        if (menuSheet.handleKeyDown(keyCode, event)) return true

        if (keyCode == KeyEvent.KEYCODE_STAR) {
            starKey.onDown(event.repeatCount)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_0) {
            event.startTracking()
            pendingZeroKeyCode = keyCode
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_POUND) {
            // §7.3: "Cycle Day → 3-Day → Week → Agenda".
            val next = when (viewMode) {
                ViewMode.DAY -> ViewMode.THREE_DAY
                ViewMode.THREE_DAY -> ViewMode.WEEK
                ViewMode.WEEK -> ViewMode.AGENDA
                ViewMode.AGENDA -> ViewMode.DAY
            }
            switchView(next)
            loadEvents(landAt = true)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            page(-1)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            page(1)
            return true
        }
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            event.number.digitToIntOrNull()?.let { currentView?.jumpToDisplayIndex(it) }
            return true
        }
        KeyRouter.map(keyCode, event.number)?.let { action ->
            when (val result = currentView?.onKey(action)) {
                CalendarViewResult.RollBackward -> {
                    focusedDate = focusedDate.minusDays(1)
                    loadEvents(landAt = false)
                }
                CalendarViewResult.RollForward -> {
                    focusedDate = focusedDate.plusDays(1)
                    loadEvents(landAt = true)
                }
                is CalendarViewResult.OpenDay -> {
                    focusedDate = result.date
                    switchView(ViewMode.DAY)
                    loadEvents(landAt = true)
                }
                is CalendarViewResult.OpenEvent -> {
                    startActivity(
                        Intent(this, EventDetailActivity::class.java).apply {
                            putExtra(EventDetailActivity.EXTRA_EVENT_ID, result.eventId)
                            putExtra(EventDetailActivity.EXTRA_INSTANCE_START_MILLIS, result.instanceStartMillis)
                        },
                    )
                }
                else -> {}
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            starKey.onUp()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_0) {
            if (pendingZeroKeyCode == keyCode && !zeroLongPressHandled) {
                focusedDate = LocalDate.now()
                loadEvents(landAt = true)
            }
            zeroLongPressHandled = false
            pendingZeroKeyCode = null
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_0) {
            zeroLongPressHandled = true
            openDatePicker()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun page(direction: Int) {
        // §7.3: "Day ±1 day, 3-Day ±3 days, Week ±1 week, Agenda ±1 week".
        val step = when (viewMode) {
            ViewMode.DAY -> 1L
            ViewMode.THREE_DAY -> 3L
            ViewMode.WEEK, ViewMode.AGENDA -> 7L
        }
        focusedDate = focusedDate.plusDays(direction * step)
        loadEvents(landAt = true)
    }

    /** Items name their target (§9.6) — Edit/Delete only appear when the selection is actually
     *  an event, not a header/gap/empty-day placeholder. */
    private fun openMenu() {
        val selected = currentView?.selectedEvent()
        val items = mutableListOf(
            MenuItem("New event") { openEditor(eventId = null) },
        )
        if (selected != null) {
            items.add(MenuItem("Edit \"${selected.title}\"") { openEditor(selected.eventId, selected.startMillis) })
            items.add(MenuItem("Delete \"${selected.title}\"") { confirmDelete(selected) })
        }
        items.add(MenuItem("Go to date") { openDatePicker() })
        items.add(MenuItem("Search") { startActivity(Intent(this, EventSearchActivity::class.java)) })
        items.add(MenuItem("Calendars") { startActivity(Intent(this, CalendarSelectionActivity::class.java)) })
        items.add(
            MenuItem("Sync now") {
                Async.run(
                    background = { CalendarStore.requestSync(this) },
                    onResult = { Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show() },
                )
            },
        )
        items.add(MenuItem("Settings") { openNineSettings("calendar") })
        menuSheet.show(items)
    }

    private fun openEditor(eventId: Long?, instanceStartMillis: Long = 0L) {
        val intent = Intent(this, EventEditActivity::class.java)
        if (eventId != null) {
            intent.putExtra(EventEditActivity.EXTRA_EVENT_ID, eventId)
            intent.putExtra(EventEditActivity.EXTRA_INSTANCE_START_MILLIS, instanceStartMillis)
            intent.putExtra(EventEditActivity.EXTRA_EDIT_SCOPE, EditScope.ALL_INSTANCES.name)
        } else {
            // §7.x: a new event defaults to whichever day the user is actually looking at (the
            // selected row's day in a multi-day view, or the view's own day in Day view) rather
            // than always today — selectedDate() falls back to focusedDate itself if there's no
            // resolvable selection, which in turn defaults to today.
            val targetDate = currentView?.selectedDate() ?: focusedDate
            intent.putExtra(EventEditActivity.EXTRA_INITIAL_DATE, targetDate.toEpochDay())
        }
        startActivity(intent)
    }

    private fun confirmDelete(event: EventEntry) {
        if (event.rrule != null) {
            menuSheet.show(
                listOf(
                    MenuItem("This event") { performDelete(event.eventId, event.startMillis, EditScope.THIS_INSTANCE) },
                    MenuItem("All events") { performDelete(event.eventId, event.startMillis, EditScope.ALL_INSTANCES) },
                ),
            )
        } else {
            performDelete(event.eventId, event.startMillis, EditScope.ALL_INSTANCES)
        }
    }

    private fun performDelete(eventId: Long, instanceStartMillis: Long, scope: EditScope) {
        Async.run(
            background = { EventStore.deleteEvent(this, eventId, instanceStartMillis, scope) },
            onResult = { success ->
                if (!success) Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show()
                loadEvents()
            },
        )
    }
}

/** [CalendarActivity.loadEvents]' `landAt` across coalesced loads: the selection jump a load was
 *  asked for, held until a render that reflects it.
 *
 *  - The newest true/false wins: it follows the newest navigation (paging left, then rolling
 *    back a day, lands where the roll says).
 *  - A null request — a sync refresh arriving meanwhile — never erases a pending true/false, or
 *    a page turn racing a sync would lose its jump.
 *  - A stale result (one a fresh load follows, see [life.furrer.nine.core.util.Reloader.isStale])
 *    renders without the jump and leaves it pending: it may predate the navigation that asked
 *    for it, so the fresh load right behind it is the one that lands. */
internal class PendingLandAt {
    private var pending: Boolean? = null

    fun request(landAt: Boolean?) {
        if (landAt != null) pending = landAt
    }

    /** The jump to apply to a result now, consuming it unless the result is [stale]. */
    fun take(stale: Boolean): Boolean? {
        if (stale) return null
        return pending.also { pending = null }
    }
}
