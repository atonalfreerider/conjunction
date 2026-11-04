package com.metavirtuoso.conjunction.data

import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chinese.ChineseChart
import com.metavirtuoso.conjunction.core.chinese.ChineseZodiac
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Everything computed on the phone from the birth data (never uploaded as such). */
data class Charts(
    val birth: BirthInput,
    val natal: NatalChart,
    val chinese: ChineseChart,
    val bloodType: BloodType?,
) {
    /** Changes whenever anything a reading depends on changes; used as a cache key. */
    val fingerprint: String =
        listOf(birth.date, birth.time ?: "notime", "%.3f".format(java.util.Locale.ROOT, birth.latitude), "%.3f".format(java.util.Locale.ROOT, birth.longitude), birth.zoneId, bloodType ?: "-")
            .joinToString("|").hashCode().toUInt().toString(16)

    /** The compatibility summary uploaded while Nearby is on. Blood type only with its consent. */
    fun compatProfile(includeBlood: Boolean): CompatProfile = CompatProfile.from(natal, chinese, if (includeBlood) bloodType else null)

    companion object {
        fun compute(birth: BirthInput, bloodType: BloodType?): Charts {
            val data = birth.toBirthData()
            return Charts(birth, NatalChart.compute(data), ChineseZodiac.compute(data), bloodType)
        }
    }
}

/** Charts derived from the stored profile, recomputed off the main thread when birth data changes. */
fun Flow<Profile>.charts(): Flow<Charts?> = this
    .map { it.birth to it.bloodType }
    .distinctUntilChanged()
    .map { (birth, blood) -> birth?.let { runCatching { Charts.compute(it, blood) }.getOrNull() } }
    .flowOn(Dispatchers.Default)
