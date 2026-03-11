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
import com.selfcode.xmusic.databinding.DialogEditPlaylistBinding
import com.selfcode.xmusic.databinding.DialogRecognitionBinding
import com.selfcode.xmusic.data.MusicRecognizer
import com.selfcode.xmusic.data.RecognitionResult
import com.selfcode.xmusic.data.EqualizerManager
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.selfcode.xmusic.service.MusicService
import android.widget.LinearLayout
import android.widget.TextView as AndroidTextView
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
    private var playlistSource: Int = 0
    private var currentOpenPlaylistId: String? = null
    private var musicService: MusicService? = null
    private var serviceBound = false
    private var currentCoverBitmap: Bitmap? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            val service = (binder as MusicService.MusicBinder).getService()
            musicService = service
            serviceBound = true
            service.callback = object : MusicService.ServiceCallback {
                override fun onServicePlayPause() {
                    runOnUiThread { if (isPlaying) pauseTrack() else resumeTrack() }
                }
                override fun onServicePrev() {
                    runOnUiThread { if (currentPlaylistIdx > 0) playFromPlaylist(currentPlaylistIdx - 1) }
                }
                override fun onServiceNext() {
                    runOnUiThread { if (currentPlaylistIdx < currentPlaylist.size - 1) playFromPlaylist(currentPlaylistIdx + 1) }
                }
                override fun onServiceStop() {
                    runOnUiThread { pauseTrack() }
                }
            }
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            musicService = null
            serviceBound = false
        }
    }

    private val dp24 by lazy { 24f * resources.displayMetrics.density } // 0=search, 1=favorites, 2=playlist

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

    private val micPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecognition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MusicStorage.init(this)
        EqualizerManager.init(this)
        repeatMode = MusicStorage.getRepeatMode()

        val serviceIntent = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        requestNotificationPermission()

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
            },
            onShare = { track -> shareTrack(track) }
        )

        favAdapter = FavoriteTrackAdapter(
            onPlay = { track, idx ->
                currentPlaylist = favAdapter.getItems()
                playlistSource = if (currentOpenPlaylistId != null) 2 else 1
                playFromPlaylist(idx)
            },
            onRemove = { track ->
                val plId = currentOpenPlaylistId
                if (plId != null) {
                    MusicStorage.removeTrackFromPlaylist(plId, track)
                    val updated = MusicStorage.getPlaylistTracks(plId)
                    favAdapter.submit(updated)
                    currentPlaylist = updated
                    Toast.makeText(this, "Удалено из плейлиста", Toast.LENGTH_SHORT).show()
                } else {
                    MusicStorage.removeFavorite(track)
                    refreshFavorites()
                    Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show()
                }
            },
            onAddToPlaylist = { track -> showAddToPlaylistDialog(track) },
            onShare = { track -> shareTrack(track) }
        )

        playlistAdapter = PlaylistAdapter(
            onClick = { pl -> openPlaylistTracks(pl) },
            onEdit = { pl -> showEditPlaylistDialog(pl) },
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
        binding.recycler.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val total = lm.itemCount
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= total - 5 && binding.btnLoadMore.tag == true) {
                    val state = vm.searchState.value
                    if (state is SearchState.Success && state.hasMore) {
                        vm.loadMore()
                    }
                }
            }
        })
        binding.btnPickFolder.setOnClickListener { dirPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.btnRecognize.setOnClickListener { requestMicAndRecognize() }
        binding.btnEqualizer.setOnClickListener { showEqualizerDialog() }
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
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
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
            binding.btnSearch, binding.tabSearch, binding.tabDownloaded, binding.tabPlaylists,
            binding.btnRecognize,
            binding.btnEqualizer
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
                EqualizerManager.attachToSession(mp.audioSessionId)
                updateWidget()
                updateServiceNotification()
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
        updateWidget()
        updateServiceNotification()
    }

    private fun resumeTrack() {
        mediaPlayer?.start(); isPlaying = true
        updatePlayPauseIcons(R.drawable.ic_pause); updateAdapterPlaying(currentPlaylistIdx)
        binding.visualizer.startAnimation()
        updateWidget()
        updateServiceNotification()
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
        binding.btnLoadMore.isVisible = false
        binding.tvEmptyFavorites.isVisible = false

        when (tab) {
            1 -> { currentOpenPlaylistId = null; refreshFavorites() }
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
        currentOpenPlaylistId = pl.id
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
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

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

    private fun showEditPlaylistDialog(pl: MusicStorage.Playlist) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val db = DialogEditPlaylistBinding.inflate(layoutInflater)
        dialog.setContentView(db.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        db.etPlaylistName.setText(pl.name)
        if (pl.coverPath.isNotEmpty() && File(pl.coverPath).exists()) {
            db.imgCoverPreview.setImageURI(Uri.fromFile(File(pl.coverPath)))
        }

        dialogCoverUri = null
        dialogCoverPreview = db.imgCoverPreview

        db.btnPickCover.setOnClickListener { dialogCoverPicker.launch("image/*") }
        db.btnCancel.setOnClickListener { dialog.dismiss() }
        db.btnSave.setOnClickListener {
            val name = db.etPlaylistName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MusicStorage.renamePlaylist(pl.id, name)
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
            Toast.makeText(this, "Плейлист обновлён", Toast.LENGTH_SHORT).show()
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
                override fun onResourceReady(r: Bitmap, t: Transition<in Bitmap>?) {
                    currentCoverBitmap = r
                    extractAndApplyColors(r)
                    updateServiceNotification()
                }
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

    private fun shareTrack(track: Track) {
        val name = "${track.artist} - ${track.title}"
            .replace(Regex("""[\\/:*?"<>|]"""), "")
            .replace(" ", "+")
        val link = "smusic://track/${name}.mp3"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Послушай: ${track.artist} - ${track.title}\n$link")
        }
        startActivity(Intent.createChooser(shareIntent, "Поделиться треком"))
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "smusic" || uri.host != "track") return

        val path = uri.path?.removePrefix("/")?.removeSuffix(".mp3") ?: return
        val query = path.replace("+", " ")
            .replace(Regex("""[\\/:*?"<>|]"""), "")

        if (query.isBlank()) return

        binding.etSearch.setText(query)
        if (currentTab != 0) switchToTab(0)
        doSearch()
        Toast.makeText(this, "Ищу: $query", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private fun updateServiceNotification() {
        val track = if (currentPlaylistIdx in currentPlaylist.indices) currentPlaylist[currentPlaylistIdx] else null
        musicService?.updateNotification(
            track?.title ?: "Self Music",
            track?.artist ?: "by SelfCode",
            isPlaying,
            currentCoverBitmap
        )
    }

    private fun updateWidget() {
        val track = if (currentPlaylistIdx in currentPlaylist.indices) currentPlaylist[currentPlaylistIdx] else null
        PlayerWidgetProvider.updateWidget(this, track?.title ?: "Self Music", track?.artist ?: "by SelfCode", isPlaying)
    }

    private fun showEqualizerDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val db = com.selfcode.xmusic.databinding.DialogEqualizerBinding.inflate(layoutInflater)
        dialog.setContentView(db.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        db.switchEq.isChecked = EqualizerManager.isEnabled()
        db.seekBass.progress = EqualizerManager.getBassStrength()
        val currentPreset = EqualizerManager.getCurrentPreset()

        val chipViews = mutableListOf<AndroidTextView>()
        EqualizerManager.presets.forEachIndexed { idx, preset ->
            val chip = AndroidTextView(this).apply {
                text = preset.name
                setTextColor(if (idx == currentPreset) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())
                textSize = 12f
                background = resources.getDrawable(R.drawable.bg_eq_chip, null)
                isSelected = idx == currentPreset
                setPadding(32, 16, 32, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 8
                layoutParams = lp
                setOnClickListener {
                    chipViews.forEachIndexed { i, c ->
                        c.isSelected = i == idx
                        c.setTextColor(if (i == idx) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())
                    }
                    EqualizerManager.applyPreset(idx)
                    updateEqBands(db)
                }
            }
            chipViews.add(chip)
            db.presetContainer.addView(chip)
        }

        buildEqBands(db)

        db.switchEq.setOnCheckedChangeListener { _, checked ->
            EqualizerManager.applyEnabled(checked)
        }

        db.seekBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) EqualizerManager.applyBassStrength(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        db.btnEqClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun buildEqBands(db: com.selfcode.xmusic.databinding.DialogEqualizerBinding) {
        db.bandsContainer.removeAllViews()
        val numBands = EqualizerManager.getBandCount()
        val range = EqualizerManager.getBandLevelRange()
        val preset = EqualizerManager.getCurrentPreset()
        val bands = if (preset in EqualizerManager.presets.indices) EqualizerManager.presets[preset].bands else EqualizerManager.getCustomBands()

        for (i in 0 until numBands) {
            val bandLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            val seekBar = android.widget.SeekBar(this).apply {
                max = (range.second - range.first).toInt()
                progress = ((bands.getOrElse(i) { 0 }) - range.first).toInt()
                rotation = 270f
                layoutParams = LinearLayout.LayoutParams(140, 0, 1f)
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF7C3AFF.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFF7C3AFF.toInt())
                tag = i
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val level = (p + range.first).toShort()
                            EqualizerManager.applyBandLevel(tag as Int, level)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }

            val freq = EqualizerManager.getBandFreq(i)
            val label = AndroidTextView(this).apply {
                text = if (freq >= 1000) "${freq / 1000}k" else "${freq}"
                setTextColor(0x55FFFFFF)
                textSize = 10f
                gravity = android.view.Gravity.CENTER
            }

            bandLayout.addView(seekBar)
            bandLayout.addView(label)
            db.bandsContainer.addView(bandLayout)
        }
    }

    private fun updateEqBands(db: com.selfcode.xmusic.databinding.DialogEqualizerBinding) {
        buildEqBands(db)
    }

    private fun requestMicAndRecognize() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecognition()
    }

    private fun startRecognition() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        val db = DialogRecognitionBinding.inflate(layoutInflater)
        dialog.setContentView(db.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        db.recVisualizer.setColors(0xFF7C3AFF.toInt(), 0xFFC850C0.toInt())
        db.recVisualizer.startAnimation()
        db.tvRecTitle.text = "Распознавание..."
        db.tvRecStatus.text = "Слушаю музыку..."

        db.btnRecClose.setOnClickListener {
            MusicRecognizer.cancelRecording()
            dialog.dismiss()
        }

        dialog.show()

        lifecycleScope.launch {
            db.tvRecStatus.text = "Записываю 7 сек..."

            val result = MusicRecognizer.recognize(cacheDir)

            db.recVisualizer.stopAnimation()

            result.onSuccess { rec ->
                if (rec == null) {
                    db.tvRecTitle.text = "Не удалось распознать"
                    db.tvRecStatus.text = "Попробуйте ещё раз ближе к источнику звука"
                    db.recVisualizer.visibility = View.GONE
                    db.btnRecClose.text = "Закрыть"
                    db.btnRecClose.setOnClickListener { dialog.dismiss() }
                } else {
                    showRecognitionResult(dialog, db, rec)
                }
            }

            result.onFailure { e ->
                db.tvRecTitle.text = "Ошибка"
                db.tvRecStatus.text = e.message ?: "Неизвестная ошибка"
                db.recVisualizer.visibility = View.GONE
                db.btnRecClose.text = "Закрыть"
                db.btnRecClose.setOnClickListener { dialog.dismiss() }
            }
        }
    }

    private fun showRecognitionResult(dialog: Dialog, db: DialogRecognitionBinding, rec: RecognitionResult) {
        db.tvRecTitle.text = rec.title
        db.tvRecStatus.visibility = View.GONE
        db.recVisualizer.visibility = View.GONE

        db.tvRecArtist.text = rec.artist
        db.tvRecArtist.visibility = View.VISIBLE

        if (rec.album.isNotEmpty()) {
            db.tvRecAlbum.text = rec.album
            db.tvRecAlbum.visibility = View.VISIBLE
        }

        if (rec.coverUrl.isNotEmpty()) {
            db.imgRecCover.visibility = View.VISIBLE
            Glide.with(this).load(rec.coverUrl)
                .placeholder(R.drawable.ic_music_placeholder)
                .error(R.drawable.ic_music_placeholder)
                .centerCrop().into(db.imgRecCover)
        }

        db.recActions.visibility = View.VISIBLE

        db.btnRecSearch.setOnClickListener {
            dialog.dismiss()
            binding.etSearch.setText("${rec.artist} ${rec.title}")
            if (currentTab != 0) switchToTab(0)
            doSearch()
        }

        db.btnRecLike.setOnClickListener {
            dialog.dismiss()
            binding.etSearch.setText("${rec.artist} ${rec.title}")
            if (currentTab != 0) switchToTab(0)
            doSearch()
            Toast.makeText(this, "Ищу «${rec.title}» для добавления", Toast.LENGTH_SHORT).show()
        }

        db.btnRecClose.text = "Закрыть"
        db.btnRecClose.setOnClickListener { dialog.dismiss() }
        dialog.setCancelable(true)
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
                    is SearchState.LoadingMore -> { binding.progressSearch.isVisible = true; binding.btnLoadMore.isVisible = false }
                    is SearchState.Success -> {
                        binding.progressSearch.isVisible = false; binding.btnSearch.isEnabled = true; binding.tvStatus.isVisible = false; hideShimmer()
                        adapter.submit(state.tracks)
                        binding.tvCount.text = "Найдено: ${state.tracks.size} треков"; binding.tvCount.isVisible = currentTab == 0
                        binding.tvCount.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.fade_in))
                        binding.btnLoadMore.isVisible = false; binding.btnLoadMore.tag = state.hasMore
                        
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(seekRunnable)
        EqualizerManager.release()
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
        mediaPlayer?.release()
        mediaPlayer = null
    }
    private fun hideKeyboard() { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.etSearch.windowToken, 0) }
    private fun getPathLabel(uri: Uri): String { val p = uri.lastPathSegment ?: return "Папка выбрана"; return p.replace("primary:", "").replace(":", "/").take(25) }
    private fun formatTime(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
