package com.metavirtuoso.conjunction.ui.components

import androidx.annotation.DrawableRes
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chart.ZodiacSign
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.chinese.FiveElement

/** Artwork lookups (art/ imported by android/app/tools/import_art.py). */
object Art {
    @DrawableRes
    fun sign(sign: ZodiacSign): Int = when (sign) {
        ZodiacSign.ARIES -> R.drawable.zodiac_aries
        ZodiacSign.TAURUS -> R.drawable.zodiac_taurus
        ZodiacSign.GEMINI -> R.drawable.zodiac_gemini
        ZodiacSign.CANCER -> R.drawable.zodiac_cancer
        ZodiacSign.LEO -> R.drawable.zodiac_leo
        ZodiacSign.VIRGO -> R.drawable.zodiac_virgo
        ZodiacSign.LIBRA -> R.drawable.zodiac_libra
        ZodiacSign.SCORPIO -> R.drawable.zodiac_scorpio
        ZodiacSign.SAGITTARIUS -> R.drawable.zodiac_sagittarius
        ZodiacSign.CAPRICORN -> R.drawable.zodiac_capricorn
        ZodiacSign.AQUARIUS -> R.drawable.zodiac_aquarius
        ZodiacSign.PISCES -> R.drawable.zodiac_pisces
    }

    /** Sign art from a lower-case wire key ("leo"); unknown keys get the brand mark. */
    @DrawableRes
    fun signKey(key: String?): Int = ZodiacSign.entries.firstOrNull { it.key == key?.lowercase() }?.let(::sign) ?: R.drawable.brand_mark

    @DrawableRes
    fun animal(animal: ChineseAnimal): Int = when (animal) {
        ChineseAnimal.RAT -> R.drawable.chinese_rat
        ChineseAnimal.OX -> R.drawable.chinese_ox
        ChineseAnimal.TIGER -> R.drawable.chinese_tiger
        ChineseAnimal.RABBIT -> R.drawable.chinese_rabbit
        ChineseAnimal.DRAGON -> R.drawable.chinese_dragon
        ChineseAnimal.SNAKE -> R.drawable.chinese_snake
        ChineseAnimal.HORSE -> R.drawable.chinese_horse
        ChineseAnimal.GOAT -> R.drawable.chinese_goat
        ChineseAnimal.MONKEY -> R.drawable.chinese_monkey
        ChineseAnimal.ROOSTER -> R.drawable.chinese_rooster
        ChineseAnimal.DOG -> R.drawable.chinese_dog
        ChineseAnimal.PIG -> R.drawable.chinese_pig
    }

    @DrawableRes
    fun animalKey(key: String?): Int = ChineseAnimal.entries.firstOrNull { it.key == key?.lowercase() }?.let(::animal) ?: R.drawable.brand_mark

    @DrawableRes
    fun blood(type: BloodType): Int = when (type) {
        BloodType.A -> R.drawable.blood_a
        BloodType.B -> R.drawable.blood_b
        BloodType.AB -> R.drawable.blood_ab
        BloodType.O -> R.drawable.blood_o
    }

    @DrawableRes
    fun element(element: FiveElement): Int = when (element) {
        FiveElement.WOOD -> R.drawable.element_wood
        FiveElement.FIRE -> R.drawable.element_fire
        FiveElement.EARTH -> R.drawable.element_earth
        FiveElement.METAL -> R.drawable.element_metal
        FiveElement.WATER -> R.drawable.element_water
    }

    fun displaySign(key: String?): String = ZodiacSign.entries.firstOrNull { it.key == key?.lowercase() }?.displayName ?: (key ?: "")
    fun displayAnimal(key: String?): String = ChineseAnimal.entries.firstOrNull { it.key == key?.lowercase() }?.displayName ?: (key ?: "")
}
