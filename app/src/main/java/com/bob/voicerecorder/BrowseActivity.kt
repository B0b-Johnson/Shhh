package com.bob.voicerecorder

import android.app.AlertDialog
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Simple two-level browser:
 *  - Tapping the activity root lists day folders (Voice8-3, Voice8-2, ...)
 *  - Tapping a day folder lists its segment files (seg_HHmmss.m4a)
 *  - Tapping a segment plays/stops it; long-press deletes it
 */
class BrowseActivity : AppCompatActivity() {

    private var player: MediaPlayer? = null
    private var currentDir: File? = null // null = showing day folders

    private lateinit var listView: ListView
    private lateinit var titleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            text = "Recordings"
            textSize = 20f
            setPadding(32, 32, 32, 16)
        }
        listView = ListView(this)
        root.addView(titleView)
        root.addView(listView)
        setContentView(root)

        showDayFolders()

        listView.setOnItemClickListener { _, _, position, _ ->
            val dir = currentDir
            if (dir == null) {
                val folders = recordRoot().listFiles { f -> f.isDirectory }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                showSegments(folders[position])
            } else {
                val segs = dir.listFiles { f -> f.isFile }
                    ?.sortedBy { it.name } ?: emptyList()
                togglePlay(segs[position])
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val dir = currentDir ?: return@setOnItemLongClickListener false
            val segs = dir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
            confirmDelete(segs[position])
            true
        }
    }

    private fun showDayFolders() {
        currentDir = null
        titleView.text = "Recordings — day folders"
        val folders = recordRoot().listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val names = folders.map { it.name }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }

    private fun showSegments(dir: File) {
        currentDir = dir
        titleView.text = dir.name
        val segs = dir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
        val names = segs.map { "${it.name}  (${it.length() / 1024} KB)" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }

    private fun togglePlay(file: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
        Toast.makeText(this, "Playing ${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                currentDir?.let { showSegments(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun recordRoot(): File = File(filesDir, ".record")

    override fun onBackPressed() {
        if (currentDir != null) {
            showDayFolders()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
