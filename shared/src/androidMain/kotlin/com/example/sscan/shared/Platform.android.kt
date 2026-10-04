package com.example.sscan.shared

import android.os.Build

actual class Platform actual constructor() {
    actual val name: String = "Android ${Build.VERSION.RELEASE}"
}
