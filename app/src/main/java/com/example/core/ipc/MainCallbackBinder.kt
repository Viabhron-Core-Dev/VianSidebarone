package com.example.core.ipc

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/**
 * Android Binder implementation hosted on the Main process.
 * Dispatches incoming events and action requests from Heavy to IMainCallbackHandler.
 */
class MainCallbackBinder(
    private val handler: IMainCallbackHandler
) : Binder(), IMainCallbackContract {

    init {
        attachInterface(null, IpcTransactions.CALLBACK_DESCRIPTOR)
    }

    override fun onHeavyEvent(eventJson: String): String {
        return handler.onHeavyEvent(eventJson)
    }

    override fun requestMainAction(requestJson: String): String {
        return handler.onRequestMainAction(requestJson)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            IBinder.INTERFACE_TRANSACTION -> {
                reply?.writeString(IpcTransactions.CALLBACK_DESCRIPTOR)
                true
            }
            IpcTransactions.TRANSACTION_ON_HEAVY_EVENT -> {
                data.enforceInterface(IpcTransactions.CALLBACK_DESCRIPTOR)
                val eventJson = data.readString() ?: ""
                val res = handler.onHeavyEvent(eventJson)
                reply?.writeNoException()
                reply?.writeString(res)
                true
            }
            IpcTransactions.TRANSACTION_REQUEST_MAIN_ACTION -> {
                data.enforceInterface(IpcTransactions.CALLBACK_DESCRIPTOR)
                val reqJson = data.readString() ?: ""
                val res = handler.onRequestMainAction(reqJson)
                reply?.writeNoException()
                reply?.writeString(res)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }
}

/**
 * Proxy operating in the Heavy process that reports events and requests actions from Main.
 */
class MainCallbackProxy(
    val remote: IBinder
) : IMainCallbackContract {

    override fun onHeavyEvent(eventJson: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.CALLBACK_DESCRIPTOR)
            data.writeString(eventJson)
            remote.transact(IpcTransactions.TRANSACTION_ON_HEAVY_EVENT, data, reply, 0)
            reply.readException()
            reply.readString() ?: IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Empty reply from Main").toJson()
        } catch (e: DeadObjectException) {
            IpcResult.error(IpcErrorCode.DEAD_BINDER, "Main process binder is dead").toJson()
        } catch (e: RemoteException) {
            IpcResult.error(IpcErrorCode.HEAVY_UNAVAILABLE, e.message ?: "RemoteException").toJson()
        } catch (e: Throwable) {
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "Unknown error").toJson()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun requestMainAction(requestJson: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IpcTransactions.CALLBACK_DESCRIPTOR)
            data.writeString(requestJson)
            remote.transact(IpcTransactions.TRANSACTION_REQUEST_MAIN_ACTION, data, reply, 0)
            reply.readException()
            reply.readString() ?: IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Empty reply from Main").toJson()
        } catch (e: DeadObjectException) {
            IpcResult.error(IpcErrorCode.DEAD_BINDER, "Main process binder is dead").toJson()
        } catch (e: RemoteException) {
            IpcResult.error(IpcErrorCode.HEAVY_UNAVAILABLE, e.message ?: "RemoteException").toJson()
        } catch (e: Throwable) {
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "Unknown error").toJson()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
