package com.undatech.opaque

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.iiordanov.bVNC.ConnectionBean
import com.iiordanov.bVNC.Constants
import com.iiordanov.bVNC.Utils
import com.undatech.opaque.util.ConnectionLoader
import com.undatech.opaque.util.GeneralUtils

class IntentHelper {
    fun getIntent(
        connectionLoader: ConnectionLoader, runtimeId: String, appContext: Context, packageContext: Context
    ): Intent = getIntent(requireNotNull(connectionLoader.connectionsById[runtimeId]), appContext, packageContext)

    fun getIntent(selected: Connection, appContext: Context, packageContext: Context): Intent {
        var intent = getIntentForRemoteCanvasActivity(packageContext)
        if (Utils.isOpaque(packageContext)) {
            val cs = selected as ConnectionSettings
            cs.loadFromSharedPreferences(appContext)
            intent.putExtra(Constants.opaqueConnectionSettingsClassPath, cs)
        } else {
            val conn = selected as ConnectionBean
            if (conn.requiresVpn) conn.load(packageContext)
            if (Utils.isFoldable(packageContext)) com.iiordanov.bVNC.PhoneDrive.prepareSaved(packageContext, conn)
            if (conn.requiresVpn) {
                intent = getUriIntentForVpnClient(packageContext, conn)
            } else {
                intent.putExtra(Utils.getConnectionString(appContext), conn.Gen_getValues())
            }
        }
        return intent
    }

    private fun getIntentForRemoteCanvasActivity(packageContext: Context) =
        Intent(packageContext, GeneralUtils.getClassByName(Constants.remoteCanvasActivityClassPath))

    private fun getUriIntentForVpnClient(
        packageContext: Context, conn: ConnectionBean
    ): Intent {
        val tunneledProtocol = getProtocol(packageContext)
        return Intent(
            Intent.ACTION_VIEW
        ).setData(
            getUri(conn, tunneledProtocol)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private fun getProtocol(packageContext: Context): String {
        var protocol = "VNC"
        if (Utils.isRdp(packageContext)) {
            protocol = "RDP"
        } else if (Utils.isSpice(packageContext)) {
            protocol = "SPICE"
        }
        return protocol
    }

    private fun getUri(conn: ConnectionBean, tunneledProtocol: String): Uri {
        val host = conn.address.removeSurrounding("[", "]")
        val authority = if (':' in host) "[${Uri.encode(host, ":")}]" else Uri.encode(host)
        return Uri.Builder().scheme(conn.vpnUriScheme).encodedAuthority("$authority:${conn.port}")
            .appendPath("tunnel").appendQueryParameter("tunnelAction", "start")
            .appendQueryParameter("remotePort", conn.port.toString())
            .appendQueryParameter("tunneledProtocol", tunneledProtocol)
            .appendQueryParameter("externalId", conn.externalId.orEmpty()).build()
    }

}
