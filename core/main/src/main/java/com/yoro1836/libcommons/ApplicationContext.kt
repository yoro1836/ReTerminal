package com.yoro1836.libcommons

import android.app.Activity
import android.app.Application
import java.lang.ref.WeakReference

@JvmField
var application:Application? = null


var currentActivity = WeakReference<Activity?>(null)

