package com.qdev.singlesurfacedualquality.utils

import android.os.Build
import java.util.Locale


object Utils {

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.lowercase(Locale.getDefault()).startsWith(manufacturer.lowercase(Locale.getDefault()))) {
            capitalize(model)
        } else {
            capitalize(manufacturer) + " " + model
        }
    }


    private fun capitalize(s: String?): String {
        if (s == null || s.length == 0) {
            return ""
        }
        val first = s[0]
        return if (Character.isUpperCase(first)) {
            s
        } else {
            first.uppercaseChar().toString() + s.substring(1)
        }
    }
}