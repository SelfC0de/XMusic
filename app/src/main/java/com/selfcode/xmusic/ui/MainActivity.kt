package com.selfcode.xmusic.ui

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.selfcode.xmusic.R
import com.selfcode.xmusic.data.MusicStorage
import com.selfcode.xmusic.data.Track
import com.selfcode.xmusic.databinding.ActivityMainBinding
import com.selfcode.xmusic.databinding.DialogAboutBinding
import com.selfcode.xmusic.databinding.DialogAddToPlaylistBinding
import com.selfcode.xmusic.databinding.DialogCreatePlaylistBinding
import com.selfcode.xmusic.ui.views.BounceEffect
import com.selfcode.xmusic.utils.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: MainViewModel
    private lateinit var adapter: TrackAdapter
    private lateinit var favAdapter: FavoriteTrackAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var bottomSheet: BottomSheetBehavior<View>
    private var savePath: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying: Boolean = false
    private var currentDominantColor: Int = 0xFF7C3AFF.toInt()
    private var currentTab: Int = 0 // 0=search, 1=my music, 2=playlists
    private var repeatMode: Int = 0 // 0=once, 1=all, 2=one

    private var currentPlaylist: List<Track> = listOf()
    private var currentPlaylistIdx: Int = -1
    private var playlistSource: Int = 0 // 0=search, 1=favorites, 2=playlist

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private var pendingCoverPlaylistId: String? = null

    private val coverPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val plId = pendingCoverPlaylistId ?: return@registerForActivityResult
        val dest = File(filesDir, "cover_$plId.jpg")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            MusicStorage.updatePlaylistCover(plId, dest.absolutePath)
            refreshPlaylists()
        } catch (_: Exception) {
            Toast.makeText(this, "Ошибка загрузки обложки", Toast.LENGTH_SHORT).show()
        }
    }

    private var dialogCoverUri: Uri? = null
    private var dialogCoverPreview: ImageView? = null
    private val dialogCoverPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        dialogCoverUri = uri
        dialogCoverPreview?.setImageURI(uri)
    }

    private val seekRunnable = object : Runnable {
        override fun run() {
            if (!userSeeking) {
                mediaPlayer?.let { mp ->
                    try {
                        if (mp.isPlaying || isPlaying) {
                            val pos = mp.currentPosition
                            val dur = mp.duration
                            if (dur > 0) {
                                binding.seekBar.progress = (pos.toLong() * 1000 / dur).toInt()
                                binding.tvCurrentTime.text = formatTime(pos)
                                binding.tvTotalTime.text = formatTime(dur)
                            }
                        }
                    } catch (_: Exception) {}
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

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MusicStorage.init(this)
        repeatMode = MusicStorage.getRepeatMode()

        vm = ViewModelProvider(this)[MainViewModel::class.java]

        adapter = TrackAdapter(
            onDownload = { track -> checkPermAndDownload(track) },
            onPlay = { track ->
                val searchTracks = vm.currentTracks()
                val idx = searchTracks.indexOf(track)
                if (idx >= 0) {
                    currentPlaylist = searchTracks
                    playlistSource = 0
                    playFromPlaylist(idx)
                }
            },
            onLike = { track, liked ->
                if (liked) {
                    cacheTrackInBackground(track)
                    Toast.makeText(this, "Добавлено в Мою музыку", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Удалено из Моей музыки", Toast.LENGTH_SHORT).show()
                }
            }
        )

        favAdapter = FavoriteTrackAdapter(
            onPlay = { track, idx ->
                currentPlaylist = favAdapter.getItems()
                playlistSource = 1
                playFromPlaylist(idx)
            },
            onRemove = { track ->
                MusicStorage.removeFavorite(track)
                refreshFavorites()
                Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylist = { track -> showAddToPlaylistDialog(track) }
        )

        playlistAdapter = PlaylistAdapter(
            onClick = { pl -> openPlaylistTracks(pl) },
            onDelete = { pl ->
                AlertDialog.Builder(this, R.style.Theme_XMusic_Dialog)
                    .setTitle("Удалить «${pl.name}»?")
                    .setPositiveButton("Удалить") { _, _ ->
                        MusicStorage.deletePlaylist(pl.id)
                        refreshPlaylists()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recyclerFavorites.layoutManager = LinearLayoutManager(this)
        binding.recyclerFavorites.adapter = favAdapter
        binding.recyclerPlaylists.layoutManager = LinearLayoutManager(this)
        binding.recyclerPlaylists.adapter = playlistAdapter

        setupBottomSheet()
        setupTabs()
        setupSeekBar()
        setupRepeatButton()
        setupBounceEffects()

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        binding.btnLoadMore.setOnClickListener { vm.loadMore() }
        binding.btnPickFolder.setOnClickListener { dirPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.btnCreatePlaylist.setOnClickListener { showCreatePlaylistDialog() }

        setupPlayerButtons(binding.btnPlayPause, binding.btnPrev, binding.btnNext)
        setupPlayerButtons(binding.btnPlayPauseExpanded, binding.btnPrevExpanded, binding.btnNextExpanded)

        binding.root.alpha = 0f
        ObjectAnimator.ofFloat(binding.root, "alpha", 0f, 1f).apply {
            duration = 600; startDelay = 100; interpolator = AccelerateDecelerateInterpolator(); start()
        }

        observeViewModel()
        handler.post(seekRunnable)
        updateRepeatIcon()
    }

    private fun setupRepeatButton() {
        binding.btnRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3
            MusicStorage.setRepeatMode(repeatMode)
            updateRepeatIcon()
        }
        BounceEffect.apply(binding.btnRepeat)
    }

    private fun updateRepeatIcon() {
        binding.btnRepeat.setImageResource(when (repeatMode) {
            0 -> R.drawable.ic_repeat
            1 -> R.drawable.ic_repeat_all
            2 -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        })
    }

    private fun cacheTrackInBackground(track: Track) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dest = MusicStorage.getCacheFile(track)
                if (!dest.exists() || dest.length() == 0L) {
                    Downloader.download(track.url, dest) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun setupBounceEffects() {
        BounceEffect.apply(
            binding.btnPlayPause, binding.btnPrev, binding.btnNext,
            binding.btnPlayPauseExpanded, binding.btnPrevExpanded, binding.btnNextExpanded,
            binding.btnSearch, binding.tabSearch, binding.tabDownloaded, binding.tabPlaylists
        )
    }

    private fun setupPlayerButtons(playPause: View, prev: View, next: View) {
        playPause.setOnClickListener {
            when {
                currentPlaylistIdx < 0 && currentPlaylist.isNotEmpty() -> playFromPlaylist(0)
                isPlaying -> pauseTrack()
                else -> resumeTrack()
            }
        }
        prev.setOnClickListener { if (currentPlaylistIdx > 0) playFromPlaylist(currentPlaylistIdx - 1) }
        next.setOnClickListener { if (currentPlaylistIdx < currentPlaylist.size - 1) playFromPlaylist(currentPlaylistIdx + 1) }
    }

    private fun playFromPlaylist(idx: Int) {
        if (idx < 0 || idx >= currentPlaylist.size) return
        currentPlaylistIdx = idx
        val track = currentPlaylist[idx]
        updateAdapterPlaying(idx)
        updatePlayerUI(track.title, track.artist, track.coverUrl)

        val cached = MusicStorage.getCachedFile(track)
        val source = cached?.absolutePath ?: track.url

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build())
            setDataSource(source)
            setOnPreparedListener { mp ->
                mp.start()
                this@MainActivity.isPlaying = true
                updatePlayPauseIcons(R.drawable.ic_pause)
                binding.visualizer.startAnimation()
            }
            setOnCompletionListener { onTrackCompleted() }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                this@MainActivity.isPlaying = false
                updatePlayPauseIcons(R.drawable.ic_play)
                binding.visualizer.stopAnimation()
                true
            }
            prepareAsync()
        }
    }

    private fun onTrackCompleted() {
        isPlaying = false
        updatePlayPauseIcons(R.drawable.ic_play)
        binding.visualizer.stopAnimation()
        updateAdapterPlaying(-1)

        when (repeatMode) {
            2 -> playFromPlaylist(currentPlaylistIdx)
            1 -> {
                val next = if (currentPlaylistIdx < currentPlaylist.size - 1) currentPlaylistIdx + 1 else 0
                playFromPlaylist(next)
            }
            0 -> {
                if (currentPlaylistIdx < currentPlaylist.size - 1) {
                    playFromPlaylist(currentPlaylistIdx + 1)
                }
            }
        }
    }

    private fun updateAdapterPlaying(idx: Int) {
        when (playlistSource) {
            0 -> { adapter.setPlayingIdx(idx, idx >= 0); favAdapter.setPlayingIdx(-1, false) }
            1 -> { favAdapter.setPlayingIdx(idx, idx >= 0); adapter.setPlayingIdx(-1, false) }
            2 -> { favAdapter.setPlayingIdx(idx, idx >= 0); adapter.setPlayingIdx(-1, false) }
        }
    }

    private fun pauseTrack() {
        mediaPlayer?.pause(); isPlaying = false
        updatePlayPauseIcons(R.drawable.ic_play); updateAdapterPlaying(-1)
        binding.visualizer.stopAnimation()
    }

    private fun resumeTrack() {
        mediaPlayer?.start(); isPlaying = true
        updatePlayPauseIcons(R.drawable.ic_pause); updateAdapterPlaying(currentPlaylistIdx)
        binding.visualizer.startAnimation()
    }

    private fun setupBottomSheet() {
        bottomSheet = BottomSheetBehavior.from(binding.bottomSheetPlayer)
        bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheet.isFitToContents = true
        bottomSheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(v: View, s: Int) {
                when (s) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        binding.expandedContent.visibility = View.VISIBLE
                        binding.miniPlayerRow.alpha = 0f
                        binding.dimOverlay.visibility = View.VISIBLE; binding.dimOverlay.isClickable = true
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        binding.expandedContent.visibility = View.GONE
                        binding.miniPlayerRow.alpha = 1f
                        binding.dimOverlay.visibility = View.GONE; binding.dimOverlay.isClickable = false
                    }
                }
            }
            override fun onSlide(v: View, o: Float) {
                val offset = o.coerceIn(0f, 1f)
                binding.expandedContent.visibility = View.VISIBLE
                binding.expandedContent.alpha = offset; binding.miniPlayerRow.alpha = 1f - offset
                binding.dimOverlay.visibility = if (offset > 0f) View.VISIBLE else View.GONE
                binding.dimOverlay.alpha = offset * 0.6f
            }
        })
        binding.dimOverlay.setOnClickListener { bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED }
    }

    private fun setupTabs() {
        binding.tabSearch.setOnClickListener { switchToTab(0) }
        binding.tabDownloaded.setOnClickListener { switchToTab(1) }
        binding.tabPlaylists.setOnClickListener { switchToTab(2) }
        binding.tabSearch.isSelected = true
    }

    private fun switchToTab(tab: Int) {
        currentTab = tab
        val tabs = listOf(binding.tabSearch, binding.tabDownloaded, binding.tabPlaylists)
        tabs.forEachIndexed { i, tv ->
            tv.isSelected = i == tab
            tv.setTextColor(if (i == tab) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())
            tv.setTypeface(null, if (i == tab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        binding.recycler.isVisible = tab == 0
        binding.recyclerFavorites.isVisible = tab == 1
        binding.playlistSection.isVisible = tab == 2
        binding.searchRow.isVisible = tab == 0
        binding.tvCount.isVisible = tab == 0 && vm.currentTracks().isNotEmpty()
        binding.btnLoadMore.isVisible = tab == 0 && binding.btnLoadMore.tag == true
        binding.tvEmptyFavorites.isVisible = false

        when (tab) {
            1 -> refreshFavorites()
            2 -> refreshPlaylists()
        }
    }

    private fun refreshFavorites() {
        val favs = MusicStorage.getFavorites()
        favAdapter.submit(favs)
        binding.tvEmptyFavorites.isVisible = favs.isEmpty() && currentTab == 1
    }

    private fun refreshPlaylists() {
        playlistAdapter.submit(MusicStorage.getPlaylists())
    }

    private fun openPlaylistTracks(pl: MusicStorage.Playlist) {
        val tracks = MusicStorage.getPlaylistTracks(pl.id)
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Плейлист пуст", Toast.LENGTH_SHORT).show()
            return
        }
        currentPlaylist = tracks
        playlistSource = 2
        favAdapter.submit(tracks)
        switchToTab(1)
    }

    private fun showCreatePlaylistDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val db = DialogCreatePlaylistBinding.inflate(layoutInflater)
        dialog.setContentView(db.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogCoverUri = null
        dialogCoverPreview = db.imgCoverPreview

        db.btnPickCover.setOnClickListener { dialogCoverPicker.launch("image/*") }
        db.btnCancel.setOnClickListener { dialog.dismiss() }
        db.btnCreate.setOnClickListener {
            val name = db.etPlaylistName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pl = MusicStorage.createPlaylist(name)
            dialogCoverUri?.let { uri ->
                val dest = File(filesDir, "cover_${pl.id}.jpg")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { out -> input.copyTo(out) }
                    }
                    MusicStorage.updatePlaylistCover(pl.id, dest.absolutePath)
                } catch (_: Exception) {}
            }
            refreshPlaylists()
            dialog.dismiss()
            Toast.makeText(this, "Плейлист «$name» создан", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private fun showAddToPlaylistDialog(track: Track) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val db = DialogAddToPlaylistBinding.inflate(layoutInflater)
        dialog.setContentView(db.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val playlists = MusicStorage.getPlaylists()
        db.tvEmpty.isVisible = playlists.isEmpty()

        val pickAdapter = PlaylistAdapter(
            onClick = { pl ->
                MusicStorage.addTrackToPlaylist(pl.id, track)
                Toast.makeText(this, "Добавлено в «${pl.name}»", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            },
            onDelete = {}
        )
        db.recyclerPlaylists.layoutManager = LinearLayoutManager(this)
        db.recyclerPlaylists.adapter = pickAdapter
        pickAdapter.submit(playlists)

        db.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.let { mp ->
                    val d = mp.duration; if (d > 0) binding.tvCurrentTime.text = formatTime((p.toLong() * d / 1000).toInt())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.let { s -> mediaPlayer?.let { mp -> val d = mp.duration; if (d > 0) mp.seekTo((s.progress.toLong() * d / 1000).toInt()) } }
                userSeeking = false
            }
        })
    }

    private fun updatePlayPauseIcons(resId: Int) {
        binding.btnPlayPause.setImageResource(resId); binding.btnPlayPauseExpanded.setImageResource(resId)
    }

    private fun updatePlayerUI(title: String, artist: String, coverUrl: String?) {
        binding.playerTitle.text = title; binding.playerArtist.text = artist
        binding.expandedTitle.text = title; binding.expandedArtist.text = artist
        updatePlayPauseIcons(R.drawable.ic_pause)
        if (!coverUrl.isNullOrEmpty()) {
            Glide.with(this).load(coverUrl).placeholder(R.drawable.ic_music_placeholder).error(R.drawable.ic_music_placeholder).centerCrop().into(binding.playerCover)
            Glide.with(this).load(coverUrl).placeholder(R.drawable.ic_music_placeholder).error(R.drawable.ic_music_placeholder).centerCrop().into(binding.expandedCover)
            Glide.with(this).asBitmap().load(coverUrl).into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(r: Bitmap, t: Transition<in Bitmap>?) { extractAndApplyColors(r) }
                override fun onLoadCleared(p: Drawable?) {}
            })
        } else {
            binding.playerCover.setImageResource(R.drawable.ic_music_placeholder)
            binding.expandedCover.setImageResource(R.drawable.ic_music_placeholder)
            applyGradientColors(0xFF7C3AFF.toInt(), 0xFFC850C0.toInt())
        }
        animateCoverAppear()
        binding.seekBar.progress = 0; binding.tvCurrentTime.text = "0:00"; binding.tvTotalTime.text = "0:00"
    }

    private fun extractAndApplyColors(bmp: Bitmap) {
        Palette.from(bmp).generate { p ->
            val d = p?.getDominantColor(0xFF7C3AFF.toInt()) ?: 0xFF7C3AFF.toInt()
            val v = p?.getVibrantColor(0xFFC850C0.toInt()) ?: 0xFFC850C0.toInt()
            applyGradientColors(d, v)
        }
    }

    private fun applyGradientColors(s: Int, e: Int) {
        val old = currentDominantColor; currentDominantColor = s
        binding.gradientOverlay.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(s, adjustAlpha(e, 0.5f), 0x00000000))
        binding.visualizer.setColors(s, e)
        val dp24 = 24f * resources.displayMetrics.density
        ValueAnimator.ofObject(ArgbEvaluator(), old, s).apply {
            duration = 800
            addUpdateListener { a ->
                binding.bottomSheetPlayer.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(adjustAlpha(a.animatedValue as Int, 0.15f), 0xFF0D0D1A.toInt())).apply {
                    cornerRadii = floatArrayOf(dp24, dp24, dp24, dp24, 0f, 0f, 0f, 0f)
                }
            }
            start()
        }
    }

    private fun animateCoverAppear() {
        binding.expandedCover.scaleX = 0.8f; binding.expandedCover.scaleY = 0.8f
        binding.expandedCover.animate().scaleX(1f).scaleY(1f).setDuration(400).setInterpolator(OvershootInterpolator(1.5f)).start()
    }

    private fun adjustAlpha(c: Int, f: Float) = (c and 0x00FFFFFF) or ((255 * f).toInt() shl 24)

    private fun showShimmer() {
        binding.root.findViewById<ShimmerFrameLayout>(R.id.shimmerLayout)?.let { it.visibility = View.VISIBLE; it.startShimmer() }
        binding.recycler.visibility = View.GONE
    }

    private fun hideShimmer() {
        binding.root.findViewById<ShimmerFrameLayout>(R.id.shimmerLayout)?.let { it.stopShimmer(); it.visibility = View.GONE }
        binding.recycler.visibility = View.VISIBLE
    }

    private fun doSearch() {
        val q = binding.etSearch.text.toString().trim(); if (q.isEmpty()) return
        hideKeyboard(); if (currentTab != 0) switchToTab(0); vm.search(q)
    }

    private fun showAbout() {
        val d = Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val db = DialogAboutBinding.inflate(layoutInflater); d.setContentView(db.root)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        db.linkTelegram.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/selfcode_dev"))) }
        db.linkVk.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vk.com/selfcode_dev"))) }
        d.show()
    }

    private fun checkPermAndDownload(track: Track) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
        }
        vm.download(track, savePath)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.searchState.collect { state ->
                when (state) {
                    is SearchState.Idle -> { binding.progressSearch.isVisible = false; binding.btnLoadMore.isVisible = false; hideShimmer() }
                    is SearchState.Loading -> { binding.progressSearch.isVisible = true; binding.btnSearch.isEnabled = false; binding.btnLoadMore.isVisible = false; binding.tvStatus.isVisible = false; showShimmer() }
                    is SearchState.LoadingMore -> { binding.progressSearch.isVisible = true; binding.btnLoadMore.isEnabled = false; binding.btnLoadMore.text = "Загрузка..." }
                    is SearchState.Success -> {
                        binding.progressSearch.isVisible = false; binding.btnSearch.isEnabled = true; binding.tvStatus.isVisible = false; hideShimmer()
                        adapter.submit(state.tracks)
                        binding.tvCount.text = "Найдено: ${state.tracks.size} треков"; binding.tvCount.isVisible = currentTab == 0
                        binding.tvCount.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.fade_in))
                        binding.btnLoadMore.isVisible = state.hasMore && currentTab == 0; binding.btnLoadMore.tag = state.hasMore
                        binding.btnLoadMore.isEnabled = true; binding.btnLoadMore.text = "Загрузить ещё"
                    }
                    is SearchState.Error -> {
                        binding.progressSearch.isVisible = false; binding.btnSearch.isEnabled = true; hideShimmer()
                        binding.tvStatus.text = state.message; binding.tvStatus.isVisible = true
                        binding.tvStatus.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.fade_in))
                        binding.btnLoadMore.isVisible = false
                    }
                }
            }
        }
        lifecycleScope.launch {
            vm.downloadState.collect { state ->
                when (state) {
                    is DownloadState.Idle -> { binding.downloadGroup.isVisible = false; binding.dividerDownload.isVisible = false }
                    is DownloadState.Progress -> { binding.downloadGroup.isVisible = true; binding.dividerDownload.isVisible = true; binding.progressDownload.progress = state.percent; binding.tvDownloadStatus.text = "${state.percent}%"; binding.tvFileName.text = state.fileName }
                    is DownloadState.Done -> { binding.progressDownload.progress = 100; binding.tvDownloadStatus.text = "✓"; binding.tvFileName.text = state.fileName; Toast.makeText(this@MainActivity, "✓ ${state.fileName}", Toast.LENGTH_LONG).show(); kotlinx.coroutines.delay(2000); vm.resetDownload() }
                    is DownloadState.Error -> { binding.tvDownloadStatus.text = "✗"; binding.tvFileName.text = state.message }
                }
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(seekRunnable); mediaPlayer?.release(); mediaPlayer = null }
    private fun hideKeyboard() { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.etSearch.windowToken, 0) }
    private fun getPathLabel(uri: Uri): String { val p = uri.lastPathSegment ?: return "Папка выбрана"; return p.replace("primary:", "").replace(":", "/").take(25) }
    private fun formatTime(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
