package cse281.automobile

import android.media.ImageReader

interface FragmentArgumentProvider {
    fun getCameraConnectionCallback() : CameraConnectionFragment.ConnectionCallback

    fun getOnImageAvailableListener() : ImageReader.OnImageAvailableListener
}