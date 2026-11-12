package com.example.feature.pwa.floating

import com.example.feature.floating.MiniAppFactory
import com.example.feature.floating.MiniAppInstance

/**
 * FloatingPwaMiniAppFactory: Factory creating [FloatingPwaMiniAppInstance] for the
 * "floating_pwa" mini-app hosted in :heavy.
 */
class FloatingPwaMiniAppFactory : MiniAppFactory {
    override fun createAppInstance(instanceId: String, appType: String): MiniAppInstance {
        return FloatingPwaMiniAppInstance(instanceId, appType)
    }
}
