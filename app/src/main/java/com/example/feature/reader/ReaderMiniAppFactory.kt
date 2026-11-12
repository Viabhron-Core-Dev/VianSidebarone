package com.example.feature.reader

import com.example.feature.floating.MiniAppFactory
import com.example.feature.floating.MiniAppInstance

/**
 * ReaderMiniAppFactory: Factory creating ReaderMiniAppInstance for "reader" and "ebook_reader" mini-apps.
 */
class ReaderMiniAppFactory : MiniAppFactory {
    override fun createAppInstance(instanceId: String, appType: String): MiniAppInstance {
        return ReaderMiniAppInstance(instanceId, appType)
    }
}
