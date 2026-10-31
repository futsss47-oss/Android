package ru.overtime.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import ru.overtime.domain.DayRule
import ru.overtime.domain.MonthOverride
import ru.overtime.domain.OvertimeMode
import ru.overtime.domain.RoundMode
import ru.overtime.domain.Settings
import ru.overtime.domain.StateStore
import ru.overtime.domain.TrackerState
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** Настройки хранятся локально на телефоне в SharedPreferences (JSON). */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("overtime_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<Settings> = state

    fun current(): Settings = state.value

    fun save(s: Settings) {
        prefs.edit().putString(KEY, SettingsJson.encode(s)).apply()
        state.value = s
    }

    private fun load(): Settings {
        val text = prefs.getString(KEY, null) ?: return Settings()
        return runCatching { SettingsJson.decode(text) }.getOrDefault(Settings())
    }

    private companion object {
        const val KEY = "json"
    }
}

object SettingsJson {
    fun encode(s: Settings): String {
        val o = JSONObject()
        val days = JSONObject()
        for (d in DayOfWeek.values()) {
            val r = s.rule(d)
            days.put(
                d.name,
                JSONObject().put("w", r.workday).put("s", r.start.toString()).put("e", r.end.toString()),
            )
        }
        o.put("days", days)
        o.put("mode", s.mode.name)
        o.put("weekendAll", s.weekendAllOvertime)
        o.put("reminder", s.monthReminder)
        o.put("lunchOn", s.lunchEnabled)
        o.put("lunchS", s.lunchStart.toString())
        o.put("lunchE", s.lunchEnd.toString())
        o.put("grace", s.graceAfterShiftMinutes)
        o.put("step", s.roundStepMinutes)
        o.put("roundMode", s.roundMode.name)
        o.put("min", s.minOvertimeMinutes)
        o.put("merge", s.mergeGapMinutes)
        o.put("poll", s.pollIntervalMinutes)
        o.put("enterR", s.enterRadiusM)
        o.put("exitR", s.exitRadiusM)
        o.put("acc", s.maxAccuracyM)
        s.workLat?.let { o.put("lat", it) }
        s.workLon?.let { o.put("lon", it) }
        o.put("formula", s.formula)
        o.put("weekendFormula", s.weekendFormula)
        o.put("salary", s.salary)
        o.put("bonusPct", s.bonusPercent)
        o.put("district", s.districtPercent)
        o.put("tax", s.taxPercent)
        val months = JSONObject()
        for ((key, m) in s.monthOverrides) {
            if (m.isEmpty) continue
            val mj = JSONObject()
            m.norm?.let { mj.put("norm", it) }
            m.bonus?.let { mj.put("bonus", it) }
            m.bonusPercent?.let { mj.put("bonusPct", it) }
            m.workedDays?.let { mj.put("days", it) }
            months.put(key, mj)
        }
        o.put("months", months)
        return o.toString()
    }

    fun decode(text: String): Settings {
        val o = JSONObject(text)
        val d = Settings()
        val daysJson = o.optJSONObject("days")
        val days = DayOfWeek.values().associateWith { day ->
            val j = daysJson?.optJSONObject(day.name)
            if (j == null) d.rule(day) else DayRule(
                workday = j.optBoolean("w", d.rule(day).workday),
                start = LocalTime.parse(j.optString("s", d.rule(day).start.toString())),
                end = LocalTime.parse(j.optString("e", d.rule(day).end.toString())),
            )
        }
        return Settings(
            days = days,
            mode = enumOr(o.optString("mode"), d.mode),
            weekendAllOvertime = o.optBoolean("weekendAll", d.weekendAllOvertime),
            monthReminder = o.optBoolean("reminder", d.monthReminder),
            lunchEnabled = o.optBoolean("lunchOn", d.lunchEnabled),
            lunchStart = LocalTime.parse(o.optString("lunchS", d.lunchStart.toString())),
            lunchEnd = LocalTime.parse(o.optString("lunchE", d.lunchEnd.toString())),
            graceAfterShiftMinutes = o.optInt("grace", d.graceAfterShiftMinutes),
            roundStepMinutes = o.optInt("step", d.roundStepMinutes),
            roundMode = enumOr(o.optString("roundMode"), d.roundMode),
            minOvertimeMinutes = o.optInt("min", d.minOvertimeMinutes),
            mergeGapMinutes = o.optInt("merge", d.mergeGapMinutes),
            pollIntervalMinutes = o.optInt("poll", d.pollIntervalMinutes),
            enterRadiusM = o.optInt("enterR", d.enterRadiusM),
            exitRadiusM = o.optInt("exitR", d.exitRadiusM),
            maxAccuracyM = o.optInt("acc", d.maxAccuracyM),
            workLat = if (o.has("lat")) o.getDouble("lat") else null,
            workLon = if (o.has("lon")) o.getDouble("lon") else null,
            formula = o.optString("formula", d.formula),
            weekendFormula = o.optString("weekendFormula", d.weekendFormula),
            salary = o.optDouble("salary", d.salary),
            bonusPercent = o.optDouble("bonusPct", d.bonusPercent),
            districtPercent = o.optDouble("district", d.districtPercent),
            taxPercent = o.optDouble("tax", d.taxPercent),
            monthOverrides = decodeMonths(o.optJSONObject("months")),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun decodeMonths(json: JSONObject?): Map<String, MonthOverride> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, MonthOverride>()
        for (key in json.keys()) {
            val mj = json.optJSONObject(key) ?: continue
            val m = MonthOverride(
                norm = if (mj.has("norm")) mj.getDouble("norm") else null,
                bonus = if (mj.has("bonus")) mj.getDouble("bonus") else null,
                bonusPercent = if (mj.has("bonusPct")) mj.getDouble("bonusPct") else null,
                workedDays = if (mj.has("days")) mj.getInt("days") else null,
            )
            if (!m.isEmpty) result[key] = m
        }
        return result
    }
}

/** Состояние движка хранится отдельно, потому что система может убить сервис в любой момент. */
class PrefsStateStore(context: Context) : StateStore {
    private val prefs = context.getSharedPreferences("overtime_tracker_state", Context.MODE_PRIVATE)

    override fun load(): TrackerState {
        val text = prefs.getString(KEY, null) ?: return TrackerState()
        return runCatching { decode(text) }.getOrDefault(TrackerState())
    }

    override fun save(state: TrackerState) {
        prefs.edit().putString(KEY, encode(state)).apply()
    }

    private fun encode(s: TrackerState): String {
        val o = JSONObject()
        s.openId?.let { o.put("openId", it) }
        s.openStart?.let { o.put("openStart", it.toString()) }
        s.lastInside?.let { o.put("lastInside", it.toString()) }
        o.put("streak", s.outsideStreak)
        s.presenceSince?.let { o.put("presence", it.toString()) }
        s.lastClosedId?.let { o.put("cId", it) }
        s.lastClosedStart?.let { o.put("cStart", it.toString()) }
        s.lastClosedEnd?.let { o.put("cEnd", it.toString()) }
        return o.toString()
    }

    private fun decode(text: String): TrackerState {
        val o = JSONObject(text)
        fun time(key: String): LocalDateTime? = if (o.has(key)) LocalDateTime.parse(o.getString(key)) else null
        return TrackerState(
            openId = if (o.has("openId")) o.getLong("openId") else null,
            openStart = time("openStart"),
            lastInside = time("lastInside"),
            outsideStreak = o.optInt("streak", 0),
            presenceSince = time("presence"),
            lastClosedId = if (o.has("cId")) o.getLong("cId") else null,
            lastClosedStart = time("cStart"),
            lastClosedEnd = time("cEnd"),
        )
    }

    private companion object {
        const val KEY = "state"
    }
}
