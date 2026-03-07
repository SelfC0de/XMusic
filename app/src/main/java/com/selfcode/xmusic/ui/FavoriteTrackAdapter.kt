package com.selfcode.xmusic.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.selfcode.xmusic.R
import com.selfcode.xmusic.data.Track
import com.selfcode.xmusic.databinding.ItemFavoriteTrackBinding
import com.selfcode.xmusic.ui.views.BounceEffect

class FavoriteTrackAdapter(
    private val onPlay: (Track, Int) -> Unit,
    private val onRemove: (Track) -> Unit,
    private val onAddToPlaylist: (Track) -> Unit,
    private val onShare: (Track) -> Unit = {}
) : RecyclerView.Adapter<FavoriteTrackAdapter.VH>() {

    private val items = mutableListOf<Track>()
    private var playingPos = -1

    inner class VH(val b: ItemFavoriteTrackBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFavoriteTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

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

        val isCurrentPlaying = position == playingPos
        b.btnPlay.setImageResource(
            if (isCurrentPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        b.tvTitle.setTextColor(
            if (isCurrentPlaying) 0xFF7C3AFF.toInt() else 0xFFF0F0FF.toInt()
        )

        BounceEffect.apply(b.btnPlay, b.btnRemove, b.btnAddToPlaylist)

        b.cardRoot.setOnClickListener { onPlay(track, holder.adapterPosition) }
        b.cardRoot.setOnLongClickListener { onShare(track); true }
        b.btnPlay.setOnClickListener { onPlay(track, holder.adapterPosition) }
        b.btnRemove.setOnClickListener { onRemove(track) }
        b.btnAddToPlaylist.setOnClickListener { onAddToPlaylist(track) }

        b.root.alpha = 0f
        b.root.translationY = 30f
        b.root.animate()
            .alpha(1f).translationY(0f)
            .setDuration(350)
            .setStartDelay((position % 15 * 30).toLong())
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()
    }

    fun submit(newItems: List<Track>) {
        items.clear()
        items.addAll(newItems)
        playingPos = -1
        notifyDataSetChanged()
    }

    fun getItems(): List<Track> = items.toList()

    fun setPlayingIdx(idx: Int, playing: Boolean) {
        val prev = playingPos
        playingPos = if (playing) idx else -1
        if (prev >= 0 && prev < items.size) notifyItemChanged(prev)
        if (idx >= 0 && idx < items.size) notifyItemChanged(idx)
    }
}
