package com.example.androidpaperserver.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.androidpaperserver.MainActivity
import com.example.androidpaperserver.data.ServerRepository
import com.example.androidpaperserver.network.PaperApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ServerForegroundService : Service() {
    private val processes = ConcurrentHashMap<String, Process>()
    private lateinit var repository: ServerRepository
    private lateinit var runtime: RuntimeManager

    override fun onCreate() {
        super.onCreate()
        repository = ServerRepository(this)
        runtime = RuntimeManager(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundServiceNotification()
                intent.getStringExtra(EXTRA_SERVER_ID)?.let { startServer(it) }
            }
            ACTION_STOP -> intent.getStringExtra(EXTRA_SERVER_ID)?.let { stopServer(it) }
            ACTION_RESTART -> intent.getStringExtra(EXTRA_SERVER_ID)?.let { restartServer(it) }
            ACTION_COMMAND -> {
                val id = intent.getStringExtra(EXTRA_SERVER_ID); val command = intent.getStringExtra(EXTRA_COMMAND)
                if (id != null && command != null) processes[id]?.outputStream?.let { stream -> stream.write((command.trim() + "\n").toByteArray()); stream.flush() }
            }
        }
        return START_STICKY
    }

    private fun startServer(id: String) {
        if (processes.containsKey(id)) return
        val server = repository.get(id) ?: return
        val root = repository.directory(id)
        setState(root, "STARTING")
        val java = runtime.javaExecutable()
        if (java == null) { writeSystemLog(root, "No Android-compatible Java runtime is installed. Import one in Settings.\n"); setState(root, "CRASHED"); return }
        val javaMajor = runtime.javaMajor()
        val requiredJava = PaperApi.javaMajorFor(server.minecraftVersion)
        if (javaMajor != requiredJava) { writeSystemLog(root, "Java $requiredJava is required for Minecraft ${server.minecraftVersion}; detected Java ${javaMajor ?: "unknown"}.\n"); setState(root, "CRASHED"); return }
        if (!server.eulaAccepted) { writeSystemLog(root, "EULA not accepted. Open the server configuration and accept the Minecraft EULA.\n"); setState(root, "STOPPED"); return }
        val jar = File(root, "paper.jar")
        if (!jar.isFile) { writeSystemLog(root, "Paper server JAR is missing. Reinstall Paper for this server.\n"); setState(root, "STOPPED"); return }

        val command = listOf(java.absolutePath, "-Xms${server.minMemoryMb}M", "-Xmx${server.maxMemoryMb}M", "-jar", jar.absolutePath, "--nogui")
        val process = runCatching {
            ProcessBuilder(command).directory(root).redirectErrorStream(true).start()
        }.getOrElse {
            writeSystemLog(root, "Failed to start Paper: ${it.message}\n"); setState(root, "CRASHED"); return
        }
        processes[id] = process
        setState(root, "RUNNING")
        Thread {
            val log = File(root, "logs/manager-console.log").apply { parentFile?.mkdirs() }
            process.inputStream.bufferedReader().useLines { lines -> log.appendText(lines.joinToString("\n") { "[${java.time.LocalTime.now()}] $it" } + "\n") }
            val code = process.waitFor()
            processes.remove(id)
            setState(root, if (code == 0) "STOPPED" else "CRASHED")
            log.appendText("[${java.time.LocalTime.now()}] Process exited with code $code\n")
            if (processes.isEmpty()) stopForeground(STOP_FOREGROUND_DETACH)
        }.start()
    }

    private fun stopServer(id: String) {
        processes[id]?.let { p ->
            setState(repository.directory(id), "STOPPING")
            runCatching { p.outputStream.write("stop\n".toByteArray()); p.outputStream.flush() }
            Thread { Thread.sleep(8_000); if (p.isAlive) p.destroy() }.start()
        }
    }

    private fun restartServer(id: String) {
        stopServer(id)
        Thread {
            repeat(18) {
                Thread.sleep(500)
                if (!processes.containsKey(id)) {
                    startServer(id)
                    return@Thread
                }
            }
            writeSystemLog(repository.directory(id), "Restart timed out because the old Paper process did not stop.\n")
        }.start()
    }

    private fun setState(root: File, state: String) { File(root, "manager-state.txt").writeText(state) }

    private fun writeSystemLog(root: File, text: String) { File(root, "logs/manager-console.log").apply { parentFile?.mkdirs(); appendText("[${java.time.LocalTime.now()}] $text") } }

    private fun startForegroundServiceNotification() {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Paper server manager")
            .setContentText("A Minecraft server is running") .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Paper servers", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        processes.values.forEach { runCatching { it.outputStream.write("stop\n".toByteArray()); it.outputStream.flush() } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.androidpaperserver.START"
        const val ACTION_STOP = "com.example.androidpaperserver.STOP"
        const val ACTION_RESTART = "com.example.androidpaperserver.RESTART"
        const val ACTION_COMMAND = "com.example.androidpaperserver.COMMAND"
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL = "paper-server"
        private const val NOTIFICATION_ID = 42
    }
}
