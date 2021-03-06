/*
Copyright 2018 Nicolás Echániz <nicoechaniz@altermundi.net>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/


package net.altermundi.rn_handshake

import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*

class PubKeyReader(private val peerSocket: Socket){
    private val connectionId: Int
    var peerPubKey: String? = null

    init {
        connectionId = ++numConnections
        Log.d(TAG, "Reading PubKey on connection, #$connectionId")
    }

    fun receiveKey() = runBlocking {
        val lines = StringBuilder()
        val br = BufferedReader(InputStreamReader(peerSocket.inputStream))
        val job = launch {
            try {
                while (true) {
                    val line = br.readLine() ?: break
                    lines.append(line)
                }
            } finally {
                br.close()
                peerPubKey = lines.toString()
            }
        }
        Log.d(TAG, "finished receiving peer key")
        job.join()
        peerSocket.close()
        Log.d(TAG, "Closing connection to emitting peer, #$connectionId")
        return@runBlocking(peerPubKey)
    }

    private companion object {
        val TAG = "ReactNative Handshake"
        var numConnections = 0
    }
}

class PubKeyEmitter(private val peerSocket: Socket) {
    private val connectionId: Int

    init {
        connectionId = ++numConnections
        Log.d(TAG, "emitting PubKey on connection, #$connectionId")
    }

    fun emitKey(pubKey: String) {
        val pw = PrintWriter(peerSocket.outputStream, true)
        try {
            pw.write("$pubKey\n")
        }
        finally {
            pw.flush()
            pw.close()
        }
        peerSocket.close()
        Log.d(TAG, "Closing connection to receiving peer #$connectionId")
    }

    private companion object {
        val TAG = "ReactNative Handshake"
        var numConnections = 0
    }
}


class HandshakeModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    var serverSocket = ServerSocket(0)

    init{
        /* forward resume, pause, destroy to controller */
        reactContext.addLifecycleEventListener(object : LifecycleEventListener {
            override fun onHostResume() {
                Log.d(TAG, "host resumed")
            }
            override fun onHostPause() {
                Log.d(TAG, "host paused")
            }
            override fun onHostDestroy() {
                Log.d(TAG, "host destroyed")
                stopServer()
            }
        })
    }

    override fun getName(): String {
        return "Handshake"
    }

    @ReactMethod
    fun startServer(pubKey: String) {
        if (serverSocket.isClosed) serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        GlobalScope.launch {
            try {
                Log.d(TAG, "Server running on port: ${port}")
                while (true) {
                    Log.d(TAG, "Accepting clients")
                    var clientSocket = serverSocket.accept()
                    Log.d(TAG, "Client connected. Port: ${clientSocket.port}")
                    PubKeyEmitter(clientSocket).emitKey(pubKey)
                }
            } catch (e: SocketException) {
                Log.d(TAG, "Socket is closed")
            }
            finally {
                Log.d(TAG, "Closing server socket")
                serverSocket.close()
            }
        }
        Log.d(TAG, "Server coroutine launched")
        var params: WritableMap = Arguments.createMap()
        params.putInt("port", port)
        sendEvent(reactContext, "handshakeServerStarted", params)
    }

    @ReactMethod
    fun stopServer() {
        serverSocket.close()
        var params: WritableMap = Arguments.createMap()
        sendEvent(reactContext, "handshakeServerStopped", params)
    }

    @ReactMethod
    fun receiveKey(host: String, port: Int) {
        Log.d(TAG, "Receiving from $host")
        var peerPubKey: String? = null
        try {
            val socket = Socket(host, port)
            peerPubKey = PubKeyReader(socket).receiveKey()
        } catch (e: java.net.ConnectException) {
            Log.d(TAG, "Could not connect to peer.")
            return
        }
        val params = Arguments.createMap()
        params.putString("key", peerPubKey)
        sendEvent(reactContext, "peerPubKeyReceived", params)
    }

    private fun sendEvent(reactContext: ReactContext, eventName: String, params: WritableMap) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
    }

    companion object {
        // We set this tag to catch the module log output with react-native log-android
        val TAG = "ReactNative Handshake"
    }
}
