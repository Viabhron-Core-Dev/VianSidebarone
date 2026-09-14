package com.example.core.ipc

import android.os.IBinder

/**
 * Transaction constants and interface descriptors for Android Binder IPC between Main and Heavy.
 */
object IpcTransactions {
    const val DESCRIPTOR = "com.example.core.ipc.IHeavyHostContract"
    const val CALLBACK_DESCRIPTOR = "com.example.core.ipc.IMainCallbackContract"

    // Main -> Heavy transactions
    const val TRANSACTION_SEND_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_SYNC_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_UNREGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 5

    // Heavy -> Main callback transactions
    const val TRANSACTION_ON_HEAVY_EVENT = IBinder.FIRST_CALL_TRANSACTION + 10
    const val TRANSACTION_REQUEST_MAIN_ACTION = IBinder.FIRST_CALL_TRANSACTION + 11
}

/**
 * Contract implemented by the Heavy process host service and consumed by Main.
 */
interface IHeavyHostContract {
    fun sendCommand(commandJson: String): String
    fun syncSnapshot(snapshotJson: String): String
    fun ping(): Boolean
    fun registerCallback(callbackBinder: IBinder): Boolean
    fun unregisterCallback(): Boolean
}

/**
 * Callback contract implemented by Main process and consumed by Heavy.
 */
interface IMainCallbackContract {
    fun onHeavyEvent(eventJson: String): String
    fun requestMainAction(requestJson: String): String
}

/**
 * Handler interface on the Heavy process to process incoming commands and snapshots.
 */
interface IHeavyHostHandler {
    fun onSendCommand(commandJson: String): String
    fun onSyncSnapshot(snapshotJson: String): String
    fun onPing(): Boolean = true
    fun onCallbackRegistered(callback: IMainCallbackContract) {}
    fun onCallbackUnregistered() {}
}

/**
 * Handler interface on the Main process to process incoming events and action requests from Heavy.
 */
interface IMainCallbackHandler {
    fun onHeavyEvent(eventJson: String): String
    fun onRequestMainAction(requestJson: String): String
}
