package com.selfcode.xmusic.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.animation.AnimationUtils
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
import com.selfcode.xmusic.R
import com.selfcode.xmusic.data.Track
import com.selfcode.xmusic.databinding.ActivityMainBinding
import com.selfcode.xmusic.databinding.DialogAboutBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: MainViewModel
    private lateinit var adapter: TrackAdapter
    private var savePath = ""
    private var mediaPlayer: MediaPlayer? = null

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
        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]
        adapter = TrackAdapter(
            onDownload = { track -> checkPermAndDownload(track) },
            onPlay = { track -> togglePlay(track) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        binding.btnLoadMore.setOnClickListener { vm.loadMore() }
        binding.btnDownload.setOnClickListener {
            val track = adapter.getSelected() ?: run {
                Toast.makeText(this, "Выберите трек", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermAndDownload(track)
        }
        binding.btnPickFolder.setOnClickListener {
            dirPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }
        binding.btnAbout.setOnClickListener { showAbout() }

        // Entrance animation
        binding.root.alpha = 0f
        ObjectAnimator.ofFloat(binding.root, "alpha", 0f, 1f).apply {
            duration = 500
            startDelay = 100
            start()
        }

        observeViewModel()
    }

    private fun togglePlay(track: Track) {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            adapter.stopPlaying()
            return
        }
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build())
            setDataSource(track.url)
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                adapter.stopPlaying()
                release()
                mediaPlayer = null
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                adapter.stopPlaying()
                true
            }
            prepareAsync()
        }
    }

    private fun doSearch() {
        val q = binding.etSearch.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        mediaPlayer?.release()
        mediaPlayer = null
        vm.search(q)
    }

    private fun showAbout() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val db = DialogAboutBinding.inflate(layoutInflater)
        dialog.setContentView(db.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        db.linkTelegram.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/selfcode_dev")))
        }
        db.linkVk.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vk.com/selfcode_dev")))
        }
        dialog.show()
    }

    private fun checkPermAndDownload(track: Track) {
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
                        binding.btnLoadMore.isVisible = false
                    }
                    is SearchState.Loading -> {
                        binding.progressSearch.isVisible = true
                        binding.btnSearch.isEnabled = false
                        binding.btnLoadMore.isVisible = false
                        binding.tvStatus.isVisible = false
                    }
                    is SearchState.LoadingMore -> {
                        binding.progressSearch.isVisible = true
                        binding.btnLoadMore.isEnabled = false
                        binding.btnLoadMore.text = "Загрузка..."
                    }
                    is SearchState.Success -> {
                        binding.progressSearch.isVisible = false
                        binding.btnSearch.isEnabled = true
                        binding.tvStatus.isVisible = false
                        adapter.submit(state.tracks)
                        binding.tvCount.text = "Найдено: ${state.tracks.size} треков"
                        binding.tvCount.isVisible = true
                        binding.tvCount.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.fade_in))
                        binding.btnLoadMore.isVisible = state.hasMore
                        binding.btnLoadMore.isEnabled = true
                        binding.btnLoadMore.text = "Загрузить ещё"
                    }
                    is SearchState.Error -> {
                        binding.progressSearch.isVisible = false
                        binding.btnSearch.isEnabled = true
                        binding.tvStatus.text = state.message
                        binding.tvStatus.isVisible = true
                        binding.tvStatus.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.fade_in))
                        binding.btnLoadMore.isVisible = false
                    }
                }
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
                        Toast.makeText(this@MainActivity, "✓ ${state.fileName}", Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    private fun getPathLabel(uri: Uri): String {
        val path = uri.lastPathSegment ?: return uri.toString()
        return path.replace("primary:", "").replace(":", "/")
    }
}
