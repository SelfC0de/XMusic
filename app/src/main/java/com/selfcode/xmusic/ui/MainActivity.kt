package com.selfcode.xmusic.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.selfcode.xmusic.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: MainViewModel
    private lateinit var adapter: TrackAdapter
    private var savePath = ""
    private var showingLogs = false

    private val dirPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                savePath = uri.path ?: ""
                binding.tvSavePath.text = "Папка: ${getPathLabel(uri)}"
            }
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Нужно разрешение для сохранения", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]
        adapter = TrackAdapter { track -> vm.download(track, savePath) }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        binding.btnDownload.setOnClickListener {
            val track = adapter.getSelected()
            if (track == null) {
                Toast.makeText(this, "Выберите трек из списка", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermAndDownload(track)
        }

        binding.btnPickFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            dirPicker.launch(intent)
        }

        binding.tabTracks.setOnClickListener { switchTab(false) }
        binding.tabLogs.setOnClickListener { switchTab(true) }

        observeViewModel()
    }

    private fun switchTab(logs: Boolean) {
        showingLogs = logs
        binding.recycler.isVisible = !logs
        binding.scrollLogs.isVisible = logs
        binding.tabTracks.setTextColor(if (!logs) getColor(R.color.accent) else getColor(R.color.text_hint))
        binding.tabLogs.setTextColor(if (logs) getColor(R.color.accent) else getColor(R.color.text_hint))
        binding.tabTracks.setBackgroundResource(if (!logs) R.drawable.bg_tab_selected else R.drawable.bg_tab_normal)
        binding.tabLogs.setBackgroundResource(if (logs) R.drawable.bg_tab_selected else R.drawable.bg_tab_normal)
    }

    private fun doSearch() {
        val q = binding.etSearch.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        binding.tvLogs.text = "Запрос: $q\n"
        vm.search(q)
    }

    private fun appendLog(text: String) {
        binding.tvLogs.append("$text\n")
        binding.scrollLogs.post { binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun checkPermAndDownload(track: com.selfcode.xmusic.data.Track) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        vm.download(track, savePath)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.searchState.collect { state ->
                when (state) {
                    is SearchState.Idle -> {
                        binding.progressSearch.isVisible = false
                        binding.tvStatus.isVisible = false
                    }
                    is SearchState.Loading -> {
                        binding.progressSearch.isVisible = true
                        binding.tvStatus.isVisible = false
                        binding.btnSearch.isEnabled = false
                        appendLog("Загрузка...")
                    }
                    is SearchState.Success -> {
                        binding.progressSearch.isVisible = false
                        binding.btnSearch.isEnabled = true
                        binding.tvStatus.isVisible = false
                        adapter.submit(state.tracks)
                        binding.tvCount.text = "Найдено: ${state.tracks.size}"
                        binding.tvCount.isVisible = true
                        appendLog("Найдено треков: ${state.tracks.size}")
                        state.tracks.take(5).forEach { appendLog("  • ${it.artist} - ${it.title}") }
                    }
                    is SearchState.Error -> {
                        binding.progressSearch.isVisible = false
                        binding.btnSearch.isEnabled = true
                        binding.tvStatus.text = state.message
                        binding.tvStatus.isVisible = true
                        appendLog("ОШИБКА: ${state.message}")
                    }
                }
            }
        }

        lifecycleScope.launch {
            vm.logMessages.collect { msg ->
                appendLog(msg)
            }
        }

        lifecycleScope.launch {
            vm.downloadState.collect { state ->
                when (state) {
                    is DownloadState.Idle -> {
                        binding.downloadGroup.isVisible = false
                    }
                    is DownloadState.Progress -> {
                        binding.downloadGroup.isVisible = true
                        binding.progressDownload.progress = state.percent
                        binding.tvDownloadStatus.text = "Загрузка: ${state.percent}%"
                        binding.tvFileName.text = state.fileName
                        binding.btnDownload.isEnabled = false
                    }
                    is DownloadState.Done -> {
                        binding.progressDownload.progress = 100
                        binding.tvDownloadStatus.text = "✓ Сохранено"
                        binding.tvFileName.text = state.fileName
                        binding.btnDownload.isEnabled = true
                        Toast.makeText(this@MainActivity, "Сохранено: ${state.fileName}", Toast.LENGTH_LONG).show()
                        kotlinx.coroutines.delay(2000)
                        vm.resetDownload()
                    }
                    is DownloadState.Error -> {
                        binding.tvDownloadStatus.text = "Ошибка: ${state.message}"
                        binding.btnDownload.isEnabled = true
                    }
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    private fun getPathLabel(uri: Uri): String {
        val path = uri.lastPathSegment ?: return uri.toString()
        return path.replace("primary:", "").replace(":", "/")
    }
}
