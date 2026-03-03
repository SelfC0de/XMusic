package com.selfcode.xmusic.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.selfcode.xmusic.R
import com.selfcode.xmusic.data.Track
import com.selfcode.xmusic.databinding.ItemTrackBinding
import com.selfcode.xmusic.ui.views.BounceEffect

class TrackAdapter(
    private val onDownload: (Track) -> Unit,
    private val onPlay: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = mutableListOf<Track>()
    private var selectedPos = -1
    private var playingPos = -1

    inner class VH(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = items[position]
        val b = holder.b

        b.tvTitle.text = track.title
        b.tvArtist.text = track.artist
        b.tvDuration.text = track.duration

        Glide.with(b.imgCover)
            .load(track.coverUrl)
            .placeholder(R.drawable.ic_music_placeholder)
            .error(R.drawable.ic_music_placeholder)
            .centerCrop()
            .into(b.imgCover)

        b.cardRoot.isSelected = position == selectedPos

        val isCurrentPlaying = position == playingPos
        b.btnPlay.setImageResource(
            if (isCurrentPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        if (isCurrentPlaying) {
            b.cardRoot.alpha = 1f
            b.tvTitle.setTextColor(0xFF7C3AFF.toInt())
        } else {
            b.cardRoot.alpha = 0.9f
            b.tvTitle.setTextColor(0xFFF0F0FF.toInt())
        }

        b.cardRoot.setOnClickListener {
            val prev = selectedPos
            selectedPos = position
            if (prev >= 0) notifyItemChanged(prev)
            notifyItemChanged(position)
        }

        BounceEffect.apply(b.btnPlay, b.btnDownloadItem)

        b.btnPlay.setOnClickListener {
            onPlay(track)
        }

        b.btnDownloadItem.setOnClickListener {
            onDownload(track)
        }

        b.root.alpha = 0f
        b.root.translationY = 30f
        b.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(350)
            .setStartDelay((position % 15 * 30).toLong())
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()
    }

    fun submit(newItems: List<Track>) {
        items.clear()
        items.addAll(newItems)
        selectedPos = -1
        playingPos = -1
        notifyDataSetChanged()
    }

    fun setPlayingIdx(idx: Int, playing: Boolean) {
        val prev = playingPos
        playingPos = if (playing) idx else -1
        if (prev >= 0) notifyItemChanged(prev)
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun getSelected(): Track? = if (selectedPos >= 0) items.getOrNull(selectedPos) else null
}
