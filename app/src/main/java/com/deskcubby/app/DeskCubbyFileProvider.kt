package com.deskcubby.app

import androidx.core.content.FileProvider

/**
 * Concrete provider subclass for OEMs that do not reliably instantiate the AndroidX base class
 * directly from the manifest.
 */
class DeskCubbyFileProvider : FileProvider()
