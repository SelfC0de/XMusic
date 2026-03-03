package com.selfcode.xmusic.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.Window
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.selfcode.xmusic.R
import com.selfcode.xmusic.data.Track
import com.selfcode.xmusic.databinding.ActivityMainBinding
import com.selfcode.xmusic.databinding.DialogAboutBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: MainViewModel
    private lateinit var adapter: TrackAdapter
    private lateinit var downloadedAdapter: DownloadedTrackAdapter
    private lateinit var bottomSheet: BottomSheetBehavior<View>
    private var savePath: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrackIdx: Int = -1
    private var allTracks: List<Track> = listOf()
    private var isPlaying: Boolean = false
    private var isSearchTab: Boolean = true
    private var playingDownloadedPath: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val seekRunnable = object : Runnable {
        override fun run() {
            if (!userSeeking) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying || isPlaying) {
                        val pos = mp.currentPosition
                        val dur = mp.duration
                        if (dur > 0) {
                            binding.seekBar.progress = (pos.toLong() * 1000 / dur).toInt()
                            binding.tvCurrentTime.text = formatTime(pos)
                            binding.tvTotalTime.text = formatTime(dur)
                        }
                    }
                }
            }
            handler.postDelayed(this, 300)
        }
    }

    private val dirPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                savePath = uri.path ?: ""
                binding.tvSavePath.text = "📁 ${getPathLabel(uri)}"
            }
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Нужно разрешение для сохранения", Toast.LENGTH_SHORT).show()
    }

    private val audioPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadDownloadedTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]
        adapter = TrackAdapter(
            onDownload = { track -> checkPermAndDownload(track) },
            onPlay = { track ->
                val idx = allTracks.indexOf(track)
                if (idx >= 0) playTrack(idx)
            }
        )

        downloadedAdapter = DownloadedTrackAdapter(
            onPlay = { track -> playDownloadedTrack(track) },
            onDelete = { track -> confirmDeleteTrack(track) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recyclerDownloaded.layoutManager = LinearLayoutManager(this)
        binding.recyclerDownloaded.adapter = downloadedAdapter

        setupBottomSheet()
        setupTabs()
        binding.tabSearch.isSelected = true
        setupSeekBar()

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        binding.btnLoadMore.setOnClickListener { vm.loadMore() }
        binding.btnPickFolder.setOnClickListener {
            dirPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }
        binding.btnAbout.setOnClickListener { showAbout() }

        setupPlayerButtons(binding.btnPlayPause, binding.btnPrev, binding.btnNext)
        setupPlayerButtons(binding.btnPlayPauseExpanded, binding.btnPrevExpanded, binding.btnNextExpanded)

        binding.root.alpha = 0f
        ObjectAnimator.ofFloat(binding.root, "alpha", 0f, 1f).apply {
            duration = 500; startDelay = 100; start()
        }

        observeViewModel()
        handler.post(seekRunnable)
    }

    private fun setupPlayerButtons(playPause: View, prev: View, next: View) {
        playPause.setOnClickListener {
            when {
                currentTrackIdx < 0 && allTracks.isNotEmpty() -> playTrack(0)
                isPlaying -> pauseTrack()
                else -> resumeTrack()
            }
        }
        prev.setOnClickListener {
            if (currentTrackIdx > 0) playTrack(currentTrackIdx - 1)
        }
        next.setOnClickListener {
            if (currentTrackIdx < allTracks.size - 1) playTrack(currentTrackIdx + 1)
        }
    }

    private fun setupBottomSheet() {
        bottomSheet = BottomSheetBehavior.from(binding.bottomSheetPlayer)
        bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheet.isFitToContents = true

        bottomSheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheetView: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        binding.expandedContent.visibility = View.VISIBLE
                        binding.miniPlayerRow.alpha = 0f
                        binding.dimOverlay.visibility = View.VISIBLE
                        binding.dimOverlay.isClickable = true
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        binding.expandedContent.visibility = View.GONE
                        binding.miniPlayerRow.alpha = 1f
                        binding.dimOverlay.visibility = View.GONE
                        binding.dimOverlay.isClickable = false
                    }
                }
            }

            override fun onSlide(bottomSheetView: View, slideOffset: Float) {
                val offset = slideOffset.coerceIn(0f, 1f)
                binding.expandedContent.visibility = View.VISIBLE
                binding.expandedContent.alpha = offset
                binding.miniPlayerRow.alpha = 1f - offset
                binding.dimOverlay.visibility = if (offset > 0f) View.VISIBLE else View.GONE
                binding.dimOverlay.alpha = offset * 0.6f
            }
        })

        binding.dimOverlay.setOnClickListener {
            bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun setupTabs() {
        binding.tabSearch.setOnClickListener { switchToTab(true) }
        binding.tabDownloaded.setOnClickListener { switchToTab(false) }
    }

    private fun switchToTab(search: Boolean) {
        isSearchTab = search
        binding.tabSearch.isSelected = search
        binding.tabSearch.setTextColor(if (search) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())
        binding.tabSearch.setTypeface(null, if (search) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.tabDownloaded.isSelected = !search
        binding.tabDownloaded.setTextColor(if (!search) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())
        binding.tabDownloaded.setTypeface(null, if (!search) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        binding.recycler.isVisible = search
        binding.recyclerDownloaded.isVisible = !search
        binding.tvCount.isVisible = search && allTracks.isNotEmpty()
        binding.btnLoadMore.isVisible = search && binding.btnLoadMore.tag == true
        binding.etSearch.isVisible = search
        binding.btnSearch.isVisible = search

        if (!search) {
            binding.tvEmptyDownloads.isVisible = false
            requestAudioPermissionAndLoad()
        } else {
            binding.tvEmptyDownloads.isVisible = false
        }
    }

    private fun requestAudioPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                audioPermLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                return
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                audioPermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                return
            }
        }
        loadDownloadedTracks()
    }

    private fun loadDownloadedTracks() {
        val tracks = mutableListOf<DownloadedTrack>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%.mp3")
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
            cursor?.let {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                while (it.moveToNext()) {
                    val title = it.getString(titleCol) ?: "Unknown"
                    val artist = it.getString(artistCol) ?: "Unknown"
                    val path = it.getString(dataCol) ?: ""
                    val name = it.getString(nameCol) ?: ""
                    val id = it.getLong(idCol)

                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    tracks.add(DownloadedTrack(title, artist, uri, name))
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }

        downloadedAdapter.submit(tracks)
        binding.tvEmptyDownloads.isVisible = tracks.isEmpty() && !isSearchTab
    }

    private fun playDownloadedTrack(track: DownloadedTrack) {
        playingDownloadedPath = track.filePath
        currentTrackIdx = -1
        allTracks = listOf()

        updatePlayerUI(track.title, track.artist, null)
        downloadedAdapter.setPlayingPath(track.filePath)
        adapter.setPlayingIdx(-1, false)

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer()
        mediaPlayer?.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        try {
            mediaPlayer?.setDataSource(this, Uri.parse(track.filePath))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
            return
        }
        mediaPlayer?.setOnPreparedListener { mp ->
            mp.start()
            isPlaying = true
            updatePlayPauseIcons(R.drawable.ic_pause)
        }
        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            updatePlayPauseIcons(R.drawable.ic_play)
            downloadedAdapter.setPlayingPath(null)
            playingDownloadedPath = null
        }
        mediaPlayer?.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
            isPlaying = false
            updatePlayPauseIcons(R.drawable.ic_play)
            true
        }
        mediaPlayer?.prepareAsync()
    }

    private fun confirmDeleteTrack(track: DownloadedTrack) {
        AlertDialog.Builder(this, R.style.Theme_XMusic_Dialog)
            .setTitle("Удалить трек?")
            .setMessage(track.fileName)
            .setPositiveButton("Удалить") { _, _ -> deleteTrack(track) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteTrack(track: DownloadedTrack) {
        try {
            val uri = Uri.parse(track.filePath)
            contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
            try {
                val file = File(track.filePath)
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
        if (playingDownloadedPath == track.filePath) {
            mediaPlayer?.release()
            mediaPlayer = null
            isPlaying = false
            playingDownloadedPath = null
            updatePlayPauseIcons(R.drawable.ic_play)
        }
        loadDownloadedTracks()
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { mp ->
                        val dur = mp.duration
                        if (dur > 0) {
                            binding.tvCurrentTime.text = formatTime((progress.toLong() * dur / 1000).toInt())
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { sb ->
                    mediaPlayer?.let { mp ->
                        val dur = mp.duration
                        if (dur > 0) {
                            mp.seekTo((sb.progress.toLong() * dur / 1000).toInt())
                        }
                    }
                }
                userSeeking = false
            }
        })
    }

    private fun playTrack(idx: Int) {
        if (idx < 0 || idx >= allTracks.size) return
        currentTrackIdx = idx
        playingDownloadedPath = null
        val track = allTracks[idx]

        updatePlayerUI(track.title, track.artist, track.coverUrl)
        adapter.setPlayingIdx(idx, true)
        downloadedAdapter.setPlayingPath(null)

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer()
        mediaPlayer?.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        mediaPlayer?.setDataSource(track.url)
        mediaPlayer?.setOnPreparedListener { mp ->
            mp.start()
            this@MainActivity.isPlaying = true
            updatePlayPauseIcons(R.drawable.ic_pause)
        }
        mediaPlayer?.setOnCompletionListener {
            this@MainActivity.isPlaying = false
            updatePlayPauseIcons(R.drawable.ic_play)
            adapter.setPlayingIdx(idx, false)
            if (this@MainActivity.currentTrackIdx < this@MainActivity.allTracks.size - 1) {
                this@MainActivity.playTrack(this@MainActivity.currentTrackIdx + 1)
            }
        }
        mediaPlayer?.setOnErrorListener { _, _, _ ->
            Toast.makeText(this@MainActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
            this@MainActivity.isPlaying = false
            updatePlayPauseIcons(R.drawable.ic_play)
            true
        }
        mediaPlayer?.prepareAsync()
    }

    private fun pauseTrack() {
        mediaPlayer?.pause()
        isPlaying = false
        updatePlayPauseIcons(R.drawable.ic_play)
        if (currentTrackIdx >= 0) adapter.setPlayingIdx(currentTrackIdx, false)
    }

    private fun resumeTrack() {
        mediaPlayer?.start()
        isPlaying = true
        updatePlayPauseIcons(R.drawable.ic_pause)
        if (currentTrackIdx >= 0) adapter.setPlayingIdx(currentTrackIdx, true)
    }

    private fun updatePlayPauseIcons(resId: Int) {
        binding.btnPlayPause.setImageResource(resId)
        binding.btnPlayPauseExpanded.setImageResource(resId)
    }

    private fun updatePlayerUI(title: String, artist: String, coverUrl: String?) {
        binding.playerTitle.text = title
        binding.playerArtist.text = artist
        binding.expandedTitle.text = title
        binding.expandedArtist.text = artist
        updatePlayPauseIcons(R.drawable.ic_pause)

        if (coverUrl != null) {
            Glide.with(this).load(coverUrl)
                .placeholder(R.drawable.ic_music_placeholder)
                .error(R.drawable.ic_music_placeholder)
                .centerCrop().into(binding.playerCover)
            Glide.with(this).load(coverUrl)
                .placeholder(R.drawable.ic_music_placeholder)
                .error(R.drawable.ic_music_placeholder)
                .centerCrop().into(binding.expandedCover)
        } else {
            binding.playerCover.setImageResource(R.drawable.ic_music_placeholder)
            binding.expandedCover.setImageResource(R.drawable.ic_music_placeholder)
        }

        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = "0:00"
        binding.tvTotalTime.text = "0:00"
    }

    private fun doSearch() {
        val q = binding.etSearch.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        if (!isSearchTab) switchToTab(true)
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
                        allTracks = state.tracks
                        adapter.submit(state.tracks)
                        binding.tvCount.text = "Найдено: ${state.tracks.size} треков"
                        binding.tvCount.isVisible = isSearchTab
                        binding.tvCount.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.fade_in))
                        binding.btnLoadMore.isVisible = state.hasMore && isSearchTab
                        binding.btnLoadMore.tag = state.hasMore
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
                        binding.dividerDownload.isVisible = false
                    }
                    is DownloadState.Progress -> {
                        binding.downloadGroup.isVisible = true
                        binding.dividerDownload.isVisible = true
                        binding.progressDownload.progress = state.percent
                        binding.tvDownloadStatus.text = "${state.percent}%"
                        binding.tvFileName.text = state.fileName
                    }
                    is DownloadState.Done -> {
                        binding.progressDownload.progress = 100
                        binding.tvDownloadStatus.text = "✓"
                        binding.tvFileName.text = state.fileName
                        Toast.makeText(this@MainActivity, "✓ ${state.fileName}", Toast.LENGTH_LONG).show()
                        kotlinx.coroutines.delay(2000)
                        vm.resetDownload()
                    }
                    is DownloadState.Error -> {
                        binding.tvDownloadStatus.text = "✗"
                        binding.tvFileName.text = state.message
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(seekRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    private fun getPathLabel(uri: Uri): String {
        val path = uri.lastPathSegment ?: return "Папка выбрана"
        return path.replace("primary:", "").replace(":", "/").take(25)
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
