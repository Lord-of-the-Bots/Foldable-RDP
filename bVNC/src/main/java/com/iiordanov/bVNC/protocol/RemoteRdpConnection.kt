package com.iiordanov.bVNC.protocol

import android.content.Context
import android.net.Uri
import android.util.Log
import com.iiordanov.bVNC.App
import com.iiordanov.bVNC.COLORMODEL
import com.iiordanov.bVNC.Constants
import com.iiordanov.bVNC.Utils
import com.iiordanov.bVNC.RdpSessionService
import com.iiordanov.bVNC.SSHConnection
import com.iiordanov.bVNC.input.RemoteRdpKeyboard
import com.iiordanov.bVNC.input.RemoteRdpPointer
import com.undatech.opaque.Connection
import com.undatech.opaque.RdpConfigFile
import com.undatech.opaque.RdpCommunicator
import com.undatech.opaque.Viewable
import com.undatech.remoteClientUi.R

class RemoteRdpConnection(
    context: Context,
    connection: Connection?,
    canvas: Viewable,
    hideKeyboardAndExtraKeys: Runnable,
) : RemoteConnection(context, connection, canvas, hideKeyboardAndExtraKeys) {
    private val tag: String = "RemoteRdpConnection"
    @Volatile private var rdpComm: RdpCommunicator? = null
    private val sessionBinding = RdpSessionService.Binding()

    /**
     * Initializes an RDP connection.
     */
    private fun initializeRdpConnection(): RdpCommunicator {
        Log.i(tag, "initializeRdpConnection: Initializing RDP connection.")
        // A corrected password starts a new transport in the existing activity.
        connectionThread?.interrupt()
        rdpComm?.close()
        sshConnection?.terminateSSHTunnel()
        sshConnection = null
        clipboardMonitorTimer?.cancel()
        val transport = RdpCommunicator(
            connection, context, handler, canvas,
            connection.connectionConfigFile, connection.userName, connection.rdpDomain, connection.password,
            App.debugLog, isRemoteToLocalClipboardIntegrationEnabled
        )
        if (Utils.isFoldable(context)) transport.enableNetworkRecovery()
        rdpComm = transport
        rfbConn = transport
        pointer = RemoteRdpPointer(rfbConn, context, this, canvas, handler, !connection.useDpadAsArrows, App.debugLog)
        keyboard = RemoteRdpKeyboard(
            transport, canvas, this, handler, App.debugLog,
            connection.preferSendingUnicode
        )
        return transport
    }

    /**
     * Starts an RDP connection using the FreeRDP library.
     */
    @Throws(Exception::class)
    private fun startRdpConnection(transport: RdpCommunicator) {
        Log.i(tag, "startRdpConnection: Starting RDP connection.")

        // Get the address and port (based on whether an SSH tunnel is being established or not).
        val size = canvas.awaitRdpDesktopSize(transport)
        val address = address
        val gateway = connection.rdpGatewayEnabled
        var rdpPort = connection.port
        var gatewayPort = connection.rdpGatewayPort
        if (sshTunneled) {
            val tunnel = synchronized(this) {
                checkAttempt(transport)
                SSHConnection(requireNotNull(getSshTunnelTargetAddress()).removeSurrounding("[", "]"), connection, context, handler)
                    .also { sshConnection = it }
            }
            try {
                tunnel.initializeSSHTunnel()
                checkAttempt(transport)
                if (gateway) gatewayPort = tunnel.createLocalPortForward(gatewayPort)
                else rdpPort = tunnel.createLocalPortForward(rdpPort)
                checkAttempt(transport)
            } catch (e: Throwable) {
                tunnel.terminateSSHTunnel()
                throw e
            }
        }
        checkAttempt(transport)
        if (Utils.isFoldable(context)) transport.setPhoneDriveEnabled(com.iiordanov.bVNC.PhoneDrive.enabled(context, connection))
        transport.setConnectionParameters(
            address, rdpPort,
            gateway, getGatewayAddress(), gatewayPort,
            connection.rdpGatewayUsername, connection.rdpGatewayDomain, connection.rdpGatewayPassword,
            connection.nickname, size.x,
            size.y, connection.desktopBackground, connection.fontSmoothing,
            connection.desktopComposition, connection.windowContents,
            connection.menuAnimation, connection.visualStyles,
            connection.redirectSdCard, connection.consoleMode,
            connection.remoteSoundType, connection.enableRecording,
            connection.remoteFx, connection.enableGfx, connection.enableGfxH264,
            connection.rdpColor, connection.desktopScalePercentage, App.debugLog
        )
        val document = connection.connectionConfigFile?.let(Uri::parse)
        if (Utils.isFoldable(context) && document != null && RdpConfigFile.accepts(document)) {
            val copy = RdpConfigFile.copy(context, document)
            try {
                checkAttempt(transport)
                transport.connect(copy.absolutePath)
            } finally {
                copy.delete()
            }
        } else transport.connect()
    }

    private fun checkAttempt(transport: RdpCommunicator) {
        if (!maintainConnection || transport !== rdpComm || Thread.currentThread().isInterrupted)
            throw InterruptedException()
    }

    @Synchronized override fun initializeConnection() {
        super.initializeConnection()
        val transport: RdpCommunicator
        try {
            if (Utils.isFoldable(context)) sessionBinding.acquire(context)
            transport = initializeRdpConnection()
            if (!Utils.isFoldable(context) || !transport.enableSessionClipboard(
                    context.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE),
                    Constants.remoteToLocalClipboardIntegration, Constants.localToRemoteClipboardIntegration))
                initializeClipboardMonitor()
        } catch (e: Throwable) {
            handleUncaughtException(e, R.string.error_rdp_unable_to_connect)
            return
        }
        connectionThread = object : Thread() {
            override fun run() {
                try {
                    startRdpConnection(transport)
                } catch (_: InterruptedException) {
                    interrupt()
                } catch (e: Throwable) {
                    val networkFailure = generateSequence(e) { it.cause }.any {
                        it is java.net.SocketException || it is java.net.SocketTimeoutException ||
                                it is java.net.UnknownHostException || it is java.io.EOFException
                    }
                    handler.post {
                        if (maintainConnection && rdpComm === transport) {
                            if (Utils.isFoldable(context) && networkFailure)
                                handler.sendEmptyMessage(com.undatech.opaque.RemoteClientLibConstants.RDP_NETWORK_LOST)
                            else handleUncaughtException(e, R.string.error_rdp_unable_to_connect)
                        }
                    }
                }
            }
        }
        connectionThread.start()
    }

    @Synchronized override fun closeConnection() {
        try {
            super.closeConnection()
        } finally {
            sessionBinding.release(context)
        }
    }

    /**
     * Returns localhost if using SSH tunnel (without gateway). If the address is prefixed with
     * {@code \\}, strips the prefix and resolves the name via NetBIOS. Otherwise returns the
     * address as-is.
     */
    override fun getAddress(): String? {
        val address = connection.address ?: return null
        val sshTunneledAndNoGatewayConfig = sshTunneled && !connection.rdpGatewayEnabled
        val netBiosResolutionForced = address.startsWith("\\\\")
        val result = when {
            sshTunneledAndNoGatewayConfig -> "127.0.0.1"
            netBiosResolutionForced -> address.substring(2).let { addressWithoutPrefix ->
                Utils.resolveNetbiosAddress(addressWithoutPrefix) ?: addressWithoutPrefix
            }
            else -> address
        }
        return result
    }
    
    /**
     * Returns localhost if using SSH tunnel or saved address.
     */
    private fun getGatewayAddress(): String? {
        return if (sshTunneled) {
            "127.0.0.1"
        } else {
            connection.rdpGatewayHostname
        }
    }

    override fun getSshTunnelTargetAddress(): String? {
        var address = connection.address
        if (connection.rdpGatewayEnabled) {
            address = connection.rdpGatewayHostname
        }
        return address
    }

    override fun isColorModel(cm: COLORMODEL) = false
    override fun setColorModel(cm: COLORMODEL?) {}

    @Throws(java.lang.Exception::class)
    override fun correctAfterRotation() {
    }
}
