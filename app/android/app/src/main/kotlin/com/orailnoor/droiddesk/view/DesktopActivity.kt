package com.orailnoor.droiddesk.view

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import android.view.SurfaceHolder
import android.widget.FrameLayout
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import java.io.File
import com.termux.x11.MainActivity as TermuxMainActivity
import com.termux.x11.LorieView
import com.termux.x11.CmdEntryPoint
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime

class DesktopActivity : Activity() {
    private var lorieView: LorieView? = null
    private var isX11Started = false
    private var isSetupDone = false
    private var shouldStartSession = false
    private var sessionMode = "termux"
    private var desktopEnv = "xfce4"
    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var placeholder: FrameLayout

    companion object {
        private const val TAG = "DesktopActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)
        shouldStartSession = intent.getBooleanExtra("startSession", false)
        sessionMode = intent.getStringExtra("mode") ?: if (chrootRuntime.hasRoot()) "chroot" else "termux"
        desktopEnv = intent.getStringExtra("de") ?: "xfce4"

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        placeholder = FrameLayout(this)
        placeholder.setBackgroundColor(Color.RED)
        setContentView(placeholder)

        Log.i(TAG, "DesktopActivity created mode=$sessionMode startSession=$shouldStartSession")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isSetupDone) {
            isSetupDone = true
            Log.i(TAG, "Window focused — setting up LorieView")
            setupLorieView()
        }
    }

    private fun setupLorieView() {
        Log.i(TAG, "Setting up LorieView")
        TermuxMainActivity.getInstance().initLorieView(this)
        lorieView = TermuxMainActivity.getInstance().lorieView

        // Restore setZOrderOnTop to see if the SurfaceView is transparent
        lorieView!!.setZOrderOnTop(true)
        placeholder.setBackgroundColor(Color.BLUE)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        placeholder.addView(lorieView, params)
        Log.i(TAG, "LorieView added to placeholder")

        // Start X server only after the Surface is actually created/changed.
        lorieView!!.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceCreated")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "LorieView surfaceChanged ${width}x${height}")
                synchronized(this@DesktopActivity) {
                    if (!isX11Started && !LorieView.connected()) {
                        isX11Started = true
                        startNativeX11()
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceDestroyed")
                synchronized(this@DesktopActivity) {
                    isX11Started = false
                }
            }
        })
    }

    private fun startNativeX11() {
        if (LorieView.connected()) {
            lorieView?.requestFocus()
            return
        }

        val xServerThread = android.os.HandlerThread("XServerThread")
        xServerThread.start()
        Handler(xServerThread.looper).post {
            try {
                val appTmpDir = File(filesDir, "tmp").apply { mkdirs() }
                val x11Dir = File(appTmpDir, ".X11-unix")
                x11Dir.mkdirs()

                // Set X server environment before starting CmdEntryPoint
                Os.setenv("TMPDIR", appTmpDir.absolutePath, true)
                Os.setenv("XDG_RUNTIME_DIR", appTmpDir.absolutePath, true)
                Os.setenv("PREFIX", File(filesDir, "usr").absolutePath, true)
                Os.setenv("HOME", filesDir.absolutePath, true)

                var xkbRoot = File(filesDir, "usr/share/X11/xkb")
                if (!xkbRoot.exists()) {
                    xkbRoot = File(filesDir, "rootfs/usr/share/X11/xkb")
                }
                if (xkbRoot.exists()) {
                    Os.setenv("XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)
                } else {
                    Log.w(TAG, "xkb config root not found at ${xkbRoot.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set environment", e)
            }

            // Clean up any stale socket from previous runs
            val appTmpDir = File(filesDir, "tmp")
            val oldSocket = File(appTmpDir, ".X11-unix/X0")
            if (oldSocket.exists()) {
                oldSocket.delete()
                Log.i(TAG, "Deleted stale X11 socket file")
            }

            Log.i(TAG, "Starting X server with :0 -nolock -extension MIT-SHM")

            val success = CmdEntryPoint.start(arrayOf(":0", "-nolock", "-extension", "MIT-SHM", "-nogpu"))
            Log.i(TAG, "X server start returned: $success")

            if (success) {
                val cmdEntryPoint = CmdEntryPoint()
                val fd = cmdEntryPoint.xConnection
                val logcatFd = cmdEntryPoint.logcatOutput

                // Let libXlorie handle the logcat output natively
                // (Java reading thread removed)

                if (fd != null) {
                    val rawFd = fd.detachFd()
                    
                    val rawLogcatFd = logcatFd?.detachFd() ?: -1
                    
                    Handler(Looper.getMainLooper()).post {
                        try {
                            LorieView.connect(rawFd)
                            if (rawLogcatFd != -1) {
                                LorieView.startLogcat(rawLogcatFd)
                            }
                            Log.i(TAG, "LorieView connect and startLogcat called on MAIN thread!")
                            
                            // Trigger callback immediately without sleeping to avoid deadlock!
                            lorieView?.triggerCallback()
                            lorieView?.requestFocus()
                            Log.i(TAG, "LorieView focused and viewport updated after connection!")
                            
                            // Start the desktop session now that the X server is ready
                            if (shouldStartSession) {
                                shouldStartSession = false
                                Thread {
                                    Log.i(TAG, "Starting Linux desktop session after X server ready")
                                    if (sessionMode == "chroot") {
                                        chrootRuntime.startSession(desktopEnv)
                                    } else {
                                        linuxRuntime.startSession(desktopEnv, "x11")
                                    }
                                }.start()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to connect LorieView", e)
                        }
                    }
                } else {
                    Log.e(TAG, "getXConnection returned null")
                    Handler(Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(this@DesktopActivity, "X11 Error: getXConnection null", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
