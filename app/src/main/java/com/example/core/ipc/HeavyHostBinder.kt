package com.example.core.ipc

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/**
 * Android Binder implementation hosted on the Heavy process.
 * Dispatches incoming commands, snapshots, and lifecycle calls to IHeavyHostHandler.
 */
class HeavyHostBinder(
    private val handler: IHeavyHostHandler
) : Binder(), IHeavyHostContract {

    init {
        attachInterface(null, IpcTransactions.DESCRIPTOR)
    }

    override fun sendCommand(commandJson: String): String {
        return handler.onSendCommand(commandJson)
    }

    override fun syncSnapshot(snapshotJson: String): String {
        return handler.onSyncSnapshot(snapshotJson)
    }

    override fun ping(): Boolean {
        return handler.onPing()
    }

    override fun registerCallback(callbackBinder: IBinder): Boolean {
        handler.onCallbackRegistered(MainCallbackProxy(callbackBinder))
        return true
    }

    override fun unregisterCallback(): Boolean {
        handler.onCallbackUnregistered()
        return true
    }

    override fun createSurfacePackage(params: android.os.Bundle): android.os.Bundle? {
        return handler.onCreateSurfacePackage(params)
    }

    override fun relayoutSurfaceHost(pageId: String, width: Int, height: Int): Boolean {
        return handler.onRelayoutSurfaceHost(pageId, width, height)
    }

    override fun releaseSurfaceHost(pageId: String): Boolean {
        return handler.onReleaseSurfaceHost(pageId)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            IBinder.INTERFACE_TRANSACTION -> {
                reply?.writeString(IpcTransactions.DESCRIPTOR)
                true
            }
            IpcTransactions.TRANSACTION_SEND_COMMAND -> {
                data.enforceInterface(IpcTransactions.DESCRIPTOR)
                val cmdJson = data.readString() ?: ""
                val resultJson = handler.onSendCommand(cmdJson)
                reply?.writeNoException()
                reply?.writeString(resultJson)
                true
            }
            IpcTransactions.TRANSACTION_SYNC_SNAPSHOT -> {
                data.enforceInterface(IpcTransactions.DESCRIPTOR)
                val snapshotJson = data.readString() ?: ""
                val resultJson = handler.onSyncSnapshot(snapshotJson)
                reply?.writeNoException()
                reply?.writeString(resultJson)
                true
            }
            IpcTransactions.TRANSACTION_PING -> {
                data.enforceInterface(IpcTransactions.DESCRIPTOR)
                val pingOk = handler.onPing()
                reply?.writeNoException()
                reply?.writeInt(if (pingOk) 1 else 0)
                true
            }
            IpcTransactions.TRANSACTION_REGISTER_CALLBACK -> {
                data.enforceInterface(IpcTransactions.DESCRIPTOR)
                val callbackBinder = data.readStrongBinder()
                if (callbackBinder != null) {
                    handler.onCallbackRegistered(MainCallbackProxy(callbackBinder))
                }
                reply?.writeNoException()
                reply?.writeInt(1)
                true
            }
            IpcTransactions.TRANSACTION_UNREGISTER_CALLBACK -> {
                data.enforceInterface(IpcTransactions.DESCRIPTOR)
                handler.onCallbackUnregistered()
                reply?.writeNoException()
                reply?.writeInt(1)
                true
            }
            IpcTransactions.TRANSACTION_CREATE_SURFACE_PACKAGE -> {
                data.enforceInterface(IpcTransactions.DESCRIPTOR)
                val params = data.readBundle(javaClass.classLoader) ?: android.os.Bundle()
                val replyBundle = handler.onCreateSurfacePackage(params)
                reply?.writeNoException()
                reply?.writeBundle(replyBundle)
                true
            }
            IpcTransactions.TRANSACTION_RELAYOUT_SURFACE_HOST -> {
                data.enforceInterface(IpcTransactions.DESCRIPTOR)
                val pageId = data.readString() ?: ""
                val width = data.readInt()
                val height = data.readInt()
                val success = handler.onRelayoutSurfaceHost(pageId, width, height)
                reply?.writeNoException()
                reply?.writeInt(if (success) 1 else 0)
                true
            }
            IpcTransactions.TRANSACTION_RELEASE_SURFACE_HOST -> {
                data.enforceInterface(IpcTransactions.DESCRIPTOR)
                val pageId = data.readString() ?: ""
                val success = handler.onReleaseSurfaceHost(pageId)
                reply?.writeNoException()
                reply?.writeInt(if (success) 1 else 0)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }
}

/**
 * Proxy operating in the Main process that sends commands across the Android Binder boundary to Heavy.
 */
class HeavyHostProxy(
    val remote: IBinder
) : IHeavyHostContract {

    override fun sendCommand(commandJson: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.DESCRIPTOR)
            data.writeString(commandJson)
            remote.transact(IpcTransactions.TRANSACTION_SEND_COMMAND, data, reply, 0)
            reply.readException()
            reply.readString() ?: IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Empty reply from Heavy").toJson()
        } catch (e: DeadObjectException) {
            IpcResult.error(IpcErrorCode.DEAD_BINDER, "Heavy process binder is dead").toJson()
        } catch (e: RemoteException) {
            IpcResult.error(IpcErrorCode.HEAVY_UNAVAILABLE, e.message ?: "RemoteException").toJson()
        } catch (e: Throwable) {
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "Unknown error").toJson()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun syncSnapshot(snapshotJson: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.DESCRIPTOR)
            data.writeString(snapshotJson)
            remote.transact(IpcTransactions.TRANSACTION_SYNC_SNAPSHOT, data, reply, 0)
            reply.readException()
            reply.readString() ?: IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Empty reply from Heavy").toJson()
        } catch (e: DeadObjectException) {
            IpcResult.error(IpcErrorCode.DEAD_BINDER, "Heavy process binder is dead").toJson()
        } catch (e: RemoteException) {
            IpcResult.error(IpcErrorCode.HEAVY_UNAVAILABLE, e.message ?: "RemoteException").toJson()
        } catch (e: Throwable) {
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "Unknown error").toJson()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun ping(): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.DESCRIPTOR)
            remote.transact(IpcTransactions.TRANSACTION_PING, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (e: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun registerCallback(callbackBinder: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            remote.transact(IpcTransactions.TRANSACTION_REGISTER_CALLBACK, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (e: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun unregisterCallback(): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.DESCRIPTOR)
            remote.transact(IpcTransactions.TRANSACTION_UNREGISTER_CALLBACK, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (e: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun createSurfacePackage(params: android.os.Bundle): android.os.Bundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.DESCRIPTOR)
            data.writeBundle(params)
            remote.transact(IpcTransactions.TRANSACTION_CREATE_SURFACE_PACKAGE, data, reply, 0)
            reply.readException()
            reply.readBundle(javaClass.classLoader)
        } catch (e: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun relayoutSurfaceHost(pageId: String, width: Int, height: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.DESCRIPTOR)
            data.writeString(pageId)
            data.writeInt(width)
            data.writeInt(height)
            remote.transact(IpcTransactions.TRANSACTION_RELAYOUT_SURFACE_HOST, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (e: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun releaseSurfaceHost(pageId: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.DESCRIPTOR)
            data.writeString(pageId)
            remote.transact(IpcTransactions.TRANSACTION_RELEASE_SURFACE_HOST, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (e: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
